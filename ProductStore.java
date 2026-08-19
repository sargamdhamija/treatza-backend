import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Stores the product catalog in Postgres so the admin dashboard can edit
 * prices/descriptions, mark items sold out, and upload photos — all of which
 * used to be hardcoded in the app itself.
 *
 * Photos are stored as base64 text directly in the database (in a
 * `photo_base64` column) rather than on the server's local disk, because
 * most hosting platforms (Render, etc.) wipe local files on every redeploy.
 * Keeping them in Postgres means they survive deploys and restarts.
 */
public class ProductStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public ProductStore(String connectionString) {
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
        seedIfEmpty();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS products (" +
                "id TEXT PRIMARY KEY, " +
                "cat TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "description TEXT, " +
                "price DOUBLE PRECISION NOT NULL, " +
                "sold_out BOOLEAN NOT NULL DEFAULT false, " +
                "photo_base64 TEXT, " +
                "photo_mime TEXT, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, " +
                "updated_at TEXT" +
                ")";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Could not set up products table — check DATABASE_URL: " + e.getMessage(), e);
        }
    }

    /** Only runs once — if the table already has products, leaves them exactly as the admin left them. */
    private void seedIfEmpty() {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM products")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        } catch (SQLException e) {
            throw new RuntimeException("Could not read products table: " + e.getMessage(), e);
        }

        String sql = "INSERT INTO products (id, cat, name, description, price, sold_out, sort_order, updated_at) " +
                "VALUES (?,?,?,?,?,false,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            int order = 0;
            for (Object[] p : SEED) {
                ps.setString(1, (String) p[0]);
                ps.setString(2, (String) p[1]);
                ps.setString(3, (String) p[2]);
                ps.setString(4, (String) p[3]);
                ps.setDouble(5, (Double) p[4]);
                ps.setInt(6, order++);
                ps.setString(7, Instant.now().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Could not seed products table: " + e.getMessage(), e);
        }
    }

    /* ---------------- reads ---------------- */

    /** Public catalog listing — never includes the (large) photo_base64 blob, just a photo flag. */
    public List<Map<String, Object>> findAll() {
        String sql = "SELECT id, cat, name, description, price, sold_out, (photo_base64 IS NOT NULL) AS has_photo, sort_order " +
                "FROM products ORDER BY sort_order, name";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("cat", rs.getString("cat"));
                m.put("name", rs.getString("name"));
                m.put("desc", rs.getString("description"));
                m.put("price", rs.getDouble("price"));
                m.put("soldOut", rs.getBoolean("sold_out"));
                m.put("hasPhoto", rs.getBoolean("has_photo"));
                result.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read products: " + e.getMessage(), e);
        }
        return result;
    }

    /** Raw photo bytes + mime type for a product, or null if it has no photo. */
    public String[] getPhoto(String id) {
        String sql = "SELECT photo_base64, photo_mime FROM products WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String base64 = rs.getString("photo_base64");
                if (base64 == null) return null;
                return new String[]{base64, rs.getString("photo_mime")};
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read photo: " + e.getMessage(), e);
        }
    }

    public boolean exists(String id) {
        String sql = "SELECT 1 FROM products WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not check product: " + e.getMessage(), e);
        }
    }

    /* ---------------- admin writes ---------------- */

    public void create(String id, String cat, String name, String desc, double price) {
        String sql = "INSERT INTO products (id, cat, name, description, price, sold_out, sort_order, updated_at) " +
                "VALUES (?,?,?,?,?,false, (SELECT COALESCE(MAX(sort_order),0)+1 FROM products), ?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, cat);
            ps.setString(3, name);
            ps.setString(4, desc);
            ps.setDouble(5, price);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not create product: " + e.getMessage(), e);
        }
    }

    /** Updates only the fields present in `fields` (name/cat/description/price/soldOut). */
    public void update(String id, Map<String, Object> fields) {
        List<String> sets = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (fields.containsKey("name")) { sets.add("name = ?"); values.add(fields.get("name")); }
        if (fields.containsKey("cat")) { sets.add("cat = ?"); values.add(fields.get("cat")); }
        if (fields.containsKey("desc")) { sets.add("description = ?"); values.add(fields.get("desc")); }
        if (fields.containsKey("price")) { sets.add("price = ?"); values.add(((Number) fields.get("price")).doubleValue()); }
        if (fields.containsKey("soldOut")) { sets.add("sold_out = ?"); values.add(fields.get("soldOut")); }
        if (sets.isEmpty()) return;
        sets.add("updated_at = ?");
        values.add(Instant.now().toString());

        String sql = "UPDATE products SET " + String.join(", ", sets) + " WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Object v : values) ps.setObject(i++, v);
            ps.setString(i, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not update product: " + e.getMessage(), e);
        }
    }

    public void setPhoto(String id, String base64, String mime) {
        String sql = "UPDATE products SET photo_base64 = ?, photo_mime = ?, updated_at = ? WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, base64);
            ps.setString(2, mime);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save photo: " + e.getMessage(), e);
        }
    }

    public void removePhoto(String id) {
        String sql = "UPDATE products SET photo_base64 = NULL, photo_mime = NULL, updated_at = ? WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not remove photo: " + e.getMessage(), e);
        }
    }

    public void delete(String id) {
        String sql = "DELETE FROM products WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not delete product: " + e.getMessage(), e);
        }
    }

    /* ---------------- seed data (the bakery's original 57-item price list) ---------------- */

    private static final Object[][] SEED = {
        {"br1","bread","Bread 140 gm","Soft daily-bake loaf",20.0},
        {"br2","bread","Bread 300 gm","Soft daily-bake loaf",35.0},
        {"br3","bread","Bread 400 gm","Soft daily-bake loaf",45.0},
        {"br4","bread","Bread 600 gm","Soft daily-bake loaf",60.0},
        {"br5","bread","Bread 800 gm","Soft daily-bake loaf",90.0},
        {"br6","bread","Bread 1200 gm","Soft daily-bake loaf",120.0},
        {"br7","bread","Wheat Bread 400 gm","Whole wheat, soft crumb",50.0},
        {"br8","bread","Wheat Bread 800 gm","Whole wheat, soft crumb",90.0},
        {"br9","bread","Crustless Bread 300 gm","Soft, no crust, sandwich-ready",45.0},
        {"br10","bread","Crustless Bread 700 gm","Soft, no crust, sandwich-ready",85.0},
        {"br11","bread","Crustless Bread 900 gm","Soft, no crust, sandwich-ready",125.0},
        {"br12","bread","Triangle Bread 1 kg","Large family-size loaf",130.0},
        {"br13","bread","Round Bread","Classic round-shaped loaf",75.0},
        {"br14","bread","Bread Stick 200 gm","Crisp, oven-baked sticks",50.0},
        {"br15","bread","Garlic Loaf","Roasted garlic and herb loaf",45.0},
        {"br16","bread","Big Garlic Loaf","Roasted garlic and herb loaf",60.0},
        {"br17","bread","Round Garlic Bread","Roasted garlic and herb bread",70.0},
        {"br18","bread","French Loaf","Crisp crust, airy crumb",45.0},
        {"br19","bread","Big French Loaf","Crisp crust, airy crumb",60.0},
        {"br20","bread","Foccasia","Olive oil baked flatbread",35.0},
        {"br21","bread","Big Foccasia","Olive oil baked flatbread",60.0},
        {"bn1","bun","Pau (6 pcs)","Soft dinner pau, pack of 6",23.0},
        {"bn2","bun","Pau (9 pcs)","Soft dinner pau, pack of 9",45.0},
        {"bn3","bun","Pau (12 pcs)","Soft dinner pau, pack of 12",45.0},
        {"bn4","bun","Pau (16 pcs)","Soft dinner pau, pack of 16",45.0},
        {"bn5","bun","V.Pau 300 gm","Soft vada-pau style bun",35.0},
        {"bn6","bun","V.Pau 400 gm","Soft vada-pau style bun",45.0},
        {"bn7","bun","Sweet Bun","Lightly sweetened soft bun",25.0},
        {"bn8","bun","Maska Bun","Classic bakery butter bun",15.0},
        {"bn9","bun","Burger Pau","Soft sesame burger bun",45.0},
        {"pz1","pizza","Pizza 6\"","Fresh baked mini pizza base",30.0},
        {"pz2","pizza","Pizza 8\"","Fresh baked pizza base",40.0},
        {"pz3","pizza","Pizza 10\"","Fresh baked pizza base",50.0},
        {"pz4","pizza","Pizza 12\"","Fresh baked large pizza base",60.0},
        {"pz5","pizza","Khari Pizza (Square)","Flaky khari-style pizza",35.0},
        {"pz6","pizza","Khari Pizza 6\"","Flaky khari-style pizza",40.0},
        {"pz7","pizza","Khari Pizza 8\"","Flaky khari-style pizza",45.0},
        {"bg1","burger","Mini Burger","Soft bun, snack-size burger",25.0},
        {"bg2","burger","Burger","Soft bun, regular burger",30.0},
        {"bg3","burger","Big Burger (1 pc)","Soft bun, large burger",25.0},
        {"bg4","burger","Hot Dog","Classic soft hot dog bun",30.0},
        {"bg5","burger","Panini","Pressed Italian-style sandwich bun",45.0},
        {"pf1","puff","Puff","Classic flaky bakery puff",15.0},
        {"pf2","puff","Big Puff","Classic flaky bakery puff",20.0},
        {"pf3","puff","Punjabi Puff","Spiced flaky puff pastry",25.0},
        {"pf4","puff","Chinese Puff","Flaky puff, Chinese-style filling",25.0},
        {"pf5","puff","Italian Puff","Flaky puff, Italian-style filling",25.0},
        {"pf6","puff","Cream Roll","Crisp roll with sweet cream filling",25.0},
        {"pf7","puff","Kulcha 5\" (1 pc)","Soft bakery kulcha",10.0},
        {"pf8","puff","Kulcha (2 pcs)","Soft bakery kulcha, pack of 2",35.0},
        {"ts1","toast","Khari 60 gm","Crisp, flaky bakery khari",15.0},
        {"ts2","toast","Khari 170 gm","Crisp, flaky bakery khari",45.0},
        {"ts3","toast","Jira Khari 170 gm","Crisp khari with cumin",45.0},
        {"ts4","toast","Toast 80 gm","Twice-baked crisp toast",15.0},
        {"ts5","toast","Toast 170 gm","Twice-baked crisp toast",40.0},
        {"ts6","toast","Irani Toast 200 gm","Irani-style crisp toast",45.0},
        {"ts7","toast","Baby Toast 150 gm","Twice-baked crisp toast",40.0},
    };
}
