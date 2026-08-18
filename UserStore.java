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
                "created_at TEXT" +
                ")";
        String sessions = "CREATE TABLE IF NOT EXISTS sessions (" +
                "token TEXT PRIMARY KEY, " +
                "user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                "created_at TEXT, " +
                "expires_at BIGINT" +
                ")";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(users);
            st.execute(sessions);
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
        phone = normalizePhone(phone);
        if (phone.isEmpty()) return AuthResult.fail("Phone number is required");
        if (name == null || name.trim().isEmpty()) return AuthResult.fail("Name is required");
        if (plainPassword == null || plainPassword.length() < 6) return AuthResult.fail("Password must be at least 6 characters");

        if (findByPhone(phone) != null) return AuthResult.fail("An account with this phone number already exists");

        byte[] salt = randomSalt();
        String hash = hash(plainPassword, salt);
        String id = "U-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();

        String sql = "INSERT INTO users (id, name, phone, password_hash, password_salt, created_at) VALUES (?,?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name.trim());
            ps.setString(3, phone);
            ps.setString(4, hash);
            ps.setString(5, encodeSalt(salt));
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create account: " + e.getMessage(), e);
        }

        Map<String, Object> user = publicUser(id, name.trim(), phone);
        String token = createSession(id);
        return AuthResult.success(user, token);
    }

    public AuthResult login(String phone, String plainPassword) {
        phone = normalizePhone(phone);
        Map<String, Object> row = findRawByPhone(phone);
        if (row == null) return AuthResult.fail("Invalid phone number or password");

        String storedHash = (String) row.get("password_hash");
        byte[] salt = decodeSalt((String) row.get("password_salt"));
        String attemptHash = hash(plainPassword == null ? "" : plainPassword, salt);

        if (!constantTimeEquals(storedHash, attemptHash)) {
            return AuthResult.fail("Invalid phone number or password");
        }

        String id = (String) row.get("id");
        Map<String, Object> user = publicUser(id, (String) row.get("name"), phone);
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
        String sql = "SELECT u.id, u.name, u.phone, s.expires_at FROM sessions s " +
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
                return publicUser(rs.getString("id"), rs.getString("name"), rs.getString("phone"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not validate session: " + e.getMessage(), e);
        }
    }

    /* ---------------- lookups ---------------- */

    private Map<String, Object> findByPhone(String phone) {
        Map<String, Object> row = findRawByPhone(phone);
        if (row == null) return null;
        return publicUser((String) row.get("id"), (String) row.get("name"), (String) row.get("phone"));
    }

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

    private static Map<String, Object> publicUser(String id, String name, String phone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("phone", phone);
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
