import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores simple bakery-wide settings — right now just whether the store is
 * currently accepting orders ("open") and an optional message to show
 * customers (e.g. "Back at 10am tomorrow"). Kept as a single row so the app
 * can show a banner and block checkout when the bakery is closed.
 */
public class SettingsStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public SettingsStore(String connectionString) {
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
        String sql = "CREATE TABLE IF NOT EXISTS store_settings (" +
                "id TEXT PRIMARY KEY, " +
                "is_open BOOLEAN NOT NULL DEFAULT true, " +
                "message TEXT NOT NULL DEFAULT '', " +
                "updated_at TEXT" +
                ")";
        // Delivery pricing — fee = base_fee + (per_km_rate * distance in km),
        // rounded up to the nearest rupee. Orders beyond max_km aren't offered
        // delivery at all. bakery_address is the "origin" point used to look
        // up the distance to the customer via Google's Distance Matrix API.
        String addBakeryAddress = "ALTER TABLE store_settings ADD COLUMN IF NOT EXISTS bakery_address TEXT NOT NULL DEFAULT ''";
        String addBaseFee = "ALTER TABLE store_settings ADD COLUMN IF NOT EXISTS delivery_base_fee DOUBLE PRECISION NOT NULL DEFAULT 20";
        String addPerKmRate = "ALTER TABLE store_settings ADD COLUMN IF NOT EXISTS delivery_per_km_rate DOUBLE PRECISION NOT NULL DEFAULT 8";
        String addMaxKm = "ALTER TABLE store_settings ADD COLUMN IF NOT EXISTS delivery_max_km DOUBLE PRECISION NOT NULL DEFAULT 10";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
            st.execute("INSERT INTO store_settings (id, is_open, message, updated_at) VALUES ('status', true, '', '" +
                    Instant.now() + "') ON CONFLICT (id) DO NOTHING");
            st.execute(addBakeryAddress);
            st.execute(addBaseFee);
            st.execute(addPerKmRate);
            st.execute(addMaxKm);
        } catch (SQLException e) {
            throw new RuntimeException("Could not set up store_settings table: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getStatus() {
        String sql = "SELECT is_open, message FROM store_settings WHERE id = 'status'";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("isOpen", rs.getBoolean("is_open"));
                m.put("message", rs.getString("message"));
            } else {
                m.put("isOpen", true);
                m.put("message", "");
            }
            return m;
        } catch (SQLException e) {
            throw new RuntimeException("Could not read store status: " + e.getMessage(), e);
        }
    }

    public void setStatus(boolean isOpen, String message) {
        String sql = "UPDATE store_settings SET is_open = ?, message = ?, updated_at = ? WHERE id = 'status'";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, isOpen);
            ps.setString(2, message == null ? "" : message);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not update store status: " + e.getMessage(), e);
        }
    }

    /* ---------------- delivery pricing ---------------- */

    public Map<String, Object> getDeliverySettings() {
        String sql = "SELECT bakery_address, delivery_base_fee, delivery_per_km_rate, delivery_max_km FROM store_settings WHERE id = 'status'";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("bakeryAddress", rs.getString("bakery_address"));
                m.put("baseFee", rs.getDouble("delivery_base_fee"));
                m.put("perKmRate", rs.getDouble("delivery_per_km_rate"));
                m.put("maxKm", rs.getDouble("delivery_max_km"));
            }
            return m;
        } catch (SQLException e) {
            throw new RuntimeException("Could not read delivery settings: " + e.getMessage(), e);
        }
    }

    public void setDeliverySettings(String bakeryAddress, double baseFee, double perKmRate, double maxKm) {
        String sql = "UPDATE store_settings SET bakery_address = ?, delivery_base_fee = ?, delivery_per_km_rate = ?, delivery_max_km = ?, updated_at = ? WHERE id = 'status'";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bakeryAddress == null ? "" : bakeryAddress);
            ps.setDouble(2, baseFee);
            ps.setDouble(3, perKmRate);
            ps.setDouble(4, maxKm);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not update delivery settings: " + e.getMessage(), e);
        }
    }
}
