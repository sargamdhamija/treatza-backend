import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores each customer's FCM (Firebase Cloud Messaging) device token, so we
 * know where to send a push notification when their order status changes.
 * One customer can have several tokens (phone + tablet, or after reinstalling
 * the app) — we just send to all of them.
 */
public class DeviceTokenStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public DeviceTokenStore(String connectionString) {
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
            throw new RuntimeException("Postgres JDBC driver not found on the classpath.", e);
        }

        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS device_tokens (" +
                "token TEXT PRIMARY KEY, " +
                "user_id TEXT NOT NULL, " +
                "created_at TEXT" +
                ")";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Could not set up device_tokens table: " + e.getMessage(), e);
        }
    }

    /** Saves a device's push token against a customer — safe to call again with the same token (e.g. on every app launch). */
    public void register(String userId, String token) {
        if (token == null || token.isEmpty()) return;
        String sql = "INSERT INTO device_tokens (token, user_id, created_at) VALUES (?,?,?) " +
                "ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, userId);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save device token: " + e.getMessage(), e);
        }
    }

    /** All of a customer's known device tokens, to push a notification to every device they've used. */
    public List<String> tokensForUser(String userId) {
        List<String> result = new ArrayList<>();
        if (userId == null) return result;
        String sql = "SELECT token FROM device_tokens WHERE user_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("token"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read device tokens: " + e.getMessage(), e);
        }
        return result;
    }

    /** Called when FCM reports a token as no longer valid (app uninstalled, etc) — keeps the table clean. */
    public void remove(String token) {
        String sql = "DELETE FROM device_tokens WHERE token = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not remove device token: " + e.getMessage(), e);
        }
    }
}
