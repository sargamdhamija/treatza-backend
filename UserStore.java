import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores customer accounts + login sessions in Postgres.
 *
 * - Passwords are never stored in plain text. Each password is hashed with
 *   PBKDF2WithHmacSHA256 (120,000 iterations) + a random per-user salt, using
 *   only classes built into the JDK — no extra library/download needed.
 * - Logging in creates a random session token (like a temporary key) that the
 *   app/browser stores and sends back on future requests as:
 *     Authorization: Bearer <token>
 *   Tokens expire after 30 days and can be invalidated early via logout().
 */
public class UserStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final long SESSION_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000; // 30 days

    public UserStore(String connectionString) {
        if (connectionString == null || connectionString.trim().isEmpty()) {
            throw new RuntimeException("DATABASE_URL is not set in .env — see README.md");
        }
        try {
            String normalized = connectionString.replaceFirst("^postgres://", "postgresql://");
            URI uri = new URI(normalized);
            String userInfo = uri.getUserInfo();
            String u = "", p = "";
            if (userInfo != null) {
                int c = userInfo.indexOf(':');
                u = URLDecoder.decode(c >= 0 ? userInfo.substring(0, c) : userInfo, StandardCharsets.UTF_8);
                p = c >= 0 ? URLDecoder.decode(userInfo.substring(c + 1), StandardCharsets.UTF_8) : "";
            }
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            String db = (path != null && path.startsWith("/")) ? path.substring(1) : path;
            String query = uri.getQuery();

            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host);
            if (port != -1) jdbc.append(':').append(port);
            jdbc.append('/').append(db);
            if (query != null && !query.isEmpty()) jdbc.append('?').append(query);

            this.jdbcUrl = jdbc.toString();
            this.user = u;
            this.password = p;
        } catch (Exception e) {
            throw new RuntimeException("Could not parse DATABASE_URL: " + e.getMessage(), e);
        }

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                "Postgres JDBC driver not found on the classpath. See README.md — " +
                "you need to download the driver .jar and include it with -cp", e);
        }

        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void initSchema() {
        String users = "CREATE TABLE IF NOT EXISTS users (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT, " +
                "phone TEXT UNIQUE NOT NULL, " +
                "password_hash TEXT NOT NULL, " +
                "password_salt TEXT NOT NULL, " +
                "role TEXT NOT NULL DEFAULT 'customer', " +
                "created_at TEXT" +
                ")";
        String sessions = "CREATE TABLE IF NOT EXISTS sessions (" +
                "token TEXT PRIMARY KEY, " +
                "user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "created_at TEXT, " +
                "expires_at BIGINT" +
                ")";
        // A short-lived numeric code used for the "forgot password" flow — see
        // generateResetCode()/resetPassword() below for how this is used.
        String resets = "CREATE TABLE IF NOT EXISTS password_resets (" +
                "phone TEXT PRIMARY KEY, " +
                "code TEXT NOT NULL, " +
                "user_id TEXT NOT NULL, " +
                "expires_at BIGINT NOT NULL" +
                ")";
        String addRoleColumn = "ALTER TABLE users ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'customer'";
        // Firebase-authenticated accounts (email/password or Google, via the app's
        // new login screen) don't have a local password or necessarily a phone
        // number yet — phone is now collected as a mandatory follow-up step after
        // first login instead of at signup, so these columns must allow NULL.
        String addFirebaseColumns = "ALTER TABLE users ADD COLUMN IF NOT EXISTS firebase_uid TEXT UNIQUE";
        String addEmailColumn = "ALTER TABLE users ADD COLUMN IF NOT EXISTS email TEXT";
        String phoneNullable = "ALTER TABLE users ALTER COLUMN phone DROP NOT NULL";
        String passHashNullable = "ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL";
        String passSaltNullable = "ALTER TABLE users ALTER COLUMN password_salt DROP NOT NULL";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(users);
            st.execute(sessions);
            st.execute(resets);
            st.execute(addRoleColumn);
            st.execute(addFirebaseColumns);
            st.execute(addEmailColumn);
            st.execute(phoneNullable);
            st.execute(passHashNullable);
            st.execute(passSaltNullable);
        } catch (SQLException e) {
            throw new RuntimeException("Could not set up users/sessions tables — check DATABASE_URL: " + e.getMessage(), e);
        }
    }

    /* ---------------- signup / login / logout ---------------- */

    public static class AuthResult {
        public final boolean ok;
        public final String errorMessage;
        public final Map<String, Object> user;
        public final String token;

        private AuthResult(boolean ok, String errorMessage, Map<String, Object> user, String token) {
            this.ok = ok;
            this.errorMessage = errorMessage;
            this.user = user;
            this.token = token;
        }
        static AuthResult fail(String msg) { return new AuthResult(false, msg, null, null); }
        static AuthResult success(Map<String, Object> user, String token) { return new AuthResult(true, null, user, token); }
    }

    public AuthResult signup(String name, String phone, String plainPassword) {
        return createAccount(name, phone, plainPassword, "customer");
    }

    /**
     * Creates a bakery staff/admin account. Only meant to be called after the
     * caller has already verified the request came from the store owner (the
     * master ADMIN_KEY) — see TreatzaServer's /api/admin/staff route.
     */
    public AuthResult createStaff(String name, String phone, String plainPassword, String role) {
        if (!role.equals("admin") && !role.equals("super_admin")) role = "admin";
        return createAccount(name, phone, plainPassword, role);
    }

    private AuthResult createAccount(String name, String phone, String plainPassword, String role) {
        phone = normalizePhone(phone);
        if (phone.isEmpty()) return AuthResult.fail("Phone number is required");
        if (name == null || name.trim().isEmpty()) return AuthResult.fail("Name is required");
        if (plainPassword == null || plainPassword.length() < 6) return AuthResult.fail("Password must be at least 6 characters");

        if (findRawByPhone(phone) != null) return AuthResult.fail("An account with this phone number already exists");

        byte[] salt = randomSalt();
        String hash = hash(plainPassword, salt);
        String id = "U-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();

        String sql = "INSERT INTO users (id, name, phone, password_hash, password_salt, role, created_at) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name.trim());
            ps.setString(3, phone);
            ps.setString(4, hash);
            ps.setString(5, encodeSalt(salt));
            ps.setString(6, role);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create account: " + e.getMessage(), e);
        }

        Map<String, Object> user = publicUser(id, name.trim(), phone, role, null);
        String token = createSession(id);
        return AuthResult.success(user, token);
    }

    public AuthResult login(String phone, String plainPassword) {
        phone = normalizePhone(phone);
        Map<String, Object> row = findRawByPhone(phone);
        if (row == null) return AuthResult.fail("Invalid phone number or password");

        String storedHash = (String) row.get("password_hash");
        if (storedHash == null || storedHash.isEmpty()) return AuthResult.fail("Invalid phone number or password");
        byte[] salt = decodeSalt((String) row.get("password_salt"));
        String attemptHash = hash(plainPassword == null ? "" : plainPassword, salt);

        if (!constantTimeEquals(storedHash, attemptHash)) {
            return AuthResult.fail("Invalid phone number or password");
        }

        String id = (String) row.get("id");
        Map<String, Object> user = publicUser(id, (String) row.get("name"), phone, (String) row.get("role"), (String) row.get("email"));
        String token = createSession(id);
        return AuthResult.success(user, token);
    }

    public void logout(String token) {
        if (token == null || token.isEmpty()) return;
        String sql = "DELETE FROM sessions WHERE token = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not log out: " + e.getMessage(), e);
        }
    }

    /** Returns the logged-in user for a valid, non-expired token, or null. */
    public Map<String, Object> userForToken(String token) {
        if (token == null || token.isEmpty()) return null;
        String sql = "SELECT u.id, u.name, u.phone, u.role, u.email, s.expires_at FROM sessions s " +
                "JOIN users u ON u.id = s.user_id WHERE s.token = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long expiresAt = rs.getLong("expires_at");
                if (expiresAt < System.currentTimeMillis()) {
                    logout(token); // expired — clean it up
                    return null;
                }
                return publicUser(rs.getString("id"), rs.getString("name"), rs.getString("phone"), rs.getString("role"), rs.getString("email"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not validate session: " + e.getMessage(), e);
        }
    }

    /** True if the token belongs to a logged-in admin or super_admin. */
    public boolean tokenHasAdminRole(String token) {
        Map<String, Object> u = userForToken(token);
        if (u == null) return false;
        String role = (String) u.get("role");
        return "admin".equals(role) || "super_admin".equals(role);
    }

    /* ---------------- forgot / reset password ---------------- */

    private static final long RESET_CODE_TTL_MILLIS = 15L * 60 * 1000; // 15 minutes

    /**
     * Generates a 6-digit reset code for the given phone (if an account exists)
     * and stores it for 15 minutes. There's no SMS/email service wired up, so
     * this alone doesn't notify the customer — see /api/admin/reset-codes,
     * which lets the bakery owner look up a pending code and relay it to the
     * customer directly (phone call, WhatsApp, in person, etc).
     *
     * Returns true either way (whether or not the phone is registered) so the
     * API response can't be used to check which phone numbers have accounts.
     */
    public boolean generateResetCode(String phone) {
        phone = normalizePhone(phone);
        Map<String, Object> row = findRawByPhone(phone);
        if (row == null) return true; // don't reveal whether this phone has an account

        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        long expiresAt = System.currentTimeMillis() + RESET_CODE_TTL_MILLIS;
        String sql = "INSERT INTO password_resets (phone, code, user_id, expires_at) VALUES (?,?,?,?) " +
                "ON CONFLICT (phone) DO UPDATE SET code = EXCLUDED.code, user_id = EXCLUDED.user_id, expires_at = EXCLUDED.expires_at";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, phone);
            ps.setString(2, code);
            ps.setString(3, (String) row.get("id"));
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create reset code: " + e.getMessage(), e);
        }
        return true;
    }

    public AuthResult resetPassword(String phone, String code, String newPassword) {
        phone = normalizePhone(phone);
        if (newPassword == null || newPassword.length() < 6) return AuthResult.fail("Password must be at least 6 characters");

        String sql = "SELECT code, user_id, expires_at FROM password_resets WHERE phone = ?";
        String userId = null;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return AuthResult.fail("Invalid or expired code");
                if (rs.getLong("expires_at") < System.currentTimeMillis()) return AuthResult.fail("This code has expired — request a new one");
                if (!constantTimeEquals(rs.getString("code"), code == null ? "" : code.trim())) return AuthResult.fail("Invalid or expired code");
                userId = rs.getString("user_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not check reset code: " + e.getMessage(), e);
        }

        byte[] salt = randomSalt();
        String hash = hash(newPassword, salt);
        String update = "UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(update)) {
            ps.setString(1, hash);
            ps.setString(2, encodeSalt(salt));
            ps.setString(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not update password: " + e.getMessage(), e);
        }

        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM password_resets WHERE phone = ?")) {
            ps.setString(1, phone);
            ps.executeUpdate();
        } catch (SQLException e) { /* non-fatal */ }

        String token = createSession(userId);
        Map<String, Object> row = findRawByPhone(phone);
        Map<String, Object> user = publicUser(userId, (String) row.get("name"), phone, (String) row.get("role"), (String) row.get("email"));
        return AuthResult.success(user, token);
    }

    /** For the admin dashboard — lists phones with an unexpired reset code pending, so the owner can relay it. */
    public List<Map<String, Object>> listPendingResetCodes() {
        String sql = "SELECT r.phone, r.code, r.expires_at, u.name FROM password_resets r " +
                "JOIN users u ON u.id = r.user_id WHERE r.expires_at > ? ORDER BY r.expires_at DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("phone", rs.getString("phone"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("expiresAt", rs.getLong("expires_at"));
                    result.add(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not list reset codes: " + e.getMessage(), e);
        }
        return result;
    }

    /* ---------------- staff/admin account listing ---------------- */

    public List<Map<String, Object>> listStaff() {
        String sql = "SELECT id, name, phone, role, created_at FROM users WHERE role IN ('admin','super_admin') ORDER BY created_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("name", rs.getString("name"));
                m.put("phone", rs.getString("phone"));
                m.put("role", rs.getString("role"));
                result.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not list staff: " + e.getMessage(), e);
        }
        return result;
    }

    public void removeStaff(String userId) {
        String sql = "DELETE FROM users WHERE id = ? AND role IN ('admin','super_admin')";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not remove staff account: " + e.getMessage(), e);
        }
    }

    /* ---------------- Firebase-authenticated accounts (email/password or Google) ---------------- */

    /**
     * Looks up (or creates on first login) the local account matching a verified
     * Firebase user. Called after TreatzaServer verifies the ID token the app
     * sent — this method itself does no verification, it trusts the caller.
     * Phone number isn't collected by Firebase, so a fresh account starts with
     * phone = null; the app should prompt for it right after (see setPhone()).
     */
    public AuthResult findOrCreateFirebaseUser(String firebaseUid, String email, String name) {
        if (firebaseUid == null || firebaseUid.isEmpty()) return AuthResult.fail("Invalid Firebase account");

        String findSql = "SELECT id, name, phone, role, email FROM users WHERE firebase_uid = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(findSql)) {
            ps.setString(1, firebaseUid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> user = publicUser(rs.getString("id"), rs.getString("name"),
                            rs.getString("phone"), rs.getString("role"), rs.getString("email"));
                    String token = createSession(rs.getString("id"));
                    return AuthResult.success(user, token);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not look up account: " + e.getMessage(), e);
        }

        String id = "U-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        String displayName = (name == null || name.trim().isEmpty()) ? (email == null ? "Customer" : email) : name.trim();
        String insertSql = "INSERT INTO users (id, name, phone, role, firebase_uid, email, created_at) " +
                "VALUES (?,?,NULL,'customer',?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setString(1, id);
            ps.setString(2, displayName);
            ps.setString(3, firebaseUid);
            ps.setString(4, email);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create account: " + e.getMessage(), e);
        }

        Map<String, Object> user = publicUser(id, displayName, null, "customer", email);
        String token = createSession(id);
        return AuthResult.success(user, token);
    }

    /** Saves the customer's mandatory phone number after their first Firebase login. */
    public AuthResult setPhone(String userId, String phone) {
        phone = normalizePhone(phone);
        if (!phone.matches("[0-9]{10}")) return AuthResult.fail("Enter a valid 10-digit phone number");

        String checkSql = "SELECT id FROM users WHERE phone = ? AND id != ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, phone);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return AuthResult.fail("This phone number is already linked to another account");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not check phone number: " + e.getMessage(), e);
        }

        String sql = "UPDATE users SET phone = ? WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, phone);
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save phone number: " + e.getMessage(), e);
        }

        String findSql = "SELECT name, role, email FROM users WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(findSql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> user = publicUser(userId, rs.getString("name"), phone, rs.getString("role"), rs.getString("email"));
                return AuthResult.success(user, null);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read updated account: " + e.getMessage(), e);
        }
    }

    /* ---------------- lookups ---------------- */

    private Map<String, Object> findRawByPhone(String phone) {
        String sql = "SELECT * FROM users WHERE phone = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("name", rs.getString("name"));
                m.put("phone", rs.getString("phone"));
                m.put("password_hash", rs.getString("password_hash"));
                m.put("password_salt", rs.getString("password_salt"));
                m.put("role", rs.getString("role"));
                m.put("email", rs.getString("email"));
                return m;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not look up account: " + e.getMessage(), e);
        }
    }

    private String createSession(String userId) {
        String token = randomToken();
        long expiresAt = System.currentTimeMillis() + SESSION_TTL_MILLIS;
        String sql = "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, userId);
            ps.setString(3, Instant.now().toString());
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create session: " + e.getMessage(), e);
        }
        return token;
    }

    /* ---------------- helpers ---------------- */

    private static Map<String, Object> publicUser(String id, String name, String phone, String role, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("phone", phone);
        m.put("role", role == null ? "customer" : role);
        m.put("email", email);
        m.put("needsPhone", phone == null || phone.trim().isEmpty());
        return m;
    }

    private static String normalizePhone(String phone) {
        if (phone == null) return "";
        return phone.trim().replaceAll("[\\s-]", "");
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String plainPassword, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plainPassword.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Password hashing failed: " + e.getMessage(), e);
        }
    }

    private static String encodeSalt(byte[] salt) { return Base64.getEncoder().encodeToString(salt); }
    private static byte[] decodeSalt(String s) { return Base64.getDecoder().decode(s); }

    /** Avoids leaking timing information about how much of the hash matched. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int result = 0;
        for (int i = 0; i < x.length; i++) result |= x[i] ^ y[i];
        return result == 0;
    }
}
