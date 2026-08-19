import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Stores orders in a Postgres database instead of a local file, so data
 * survives server restarts/redeploys even on hosts with an ephemeral
 * filesystem (like Render's free tier).
 *
 * Accepts a standard connection string as commonly given by providers like
 * Neon or Supabase, e.g.:
 *   postgresql://user:password@host/dbname?sslmode=require
 * and converts it internally to the JDBC form.
 */
public class OrderStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private static final Map<String, String> FIELD_TO_COLUMN = new HashMap<>();
    static {
        FIELD_TO_COLUMN.put("orderStatus", "order_status");
        FIELD_TO_COLUMN.put("paymentStatus", "payment_status");
        FIELD_TO_COLUMN.put("razorpayPaymentLinkId", "razorpay_payment_link_id");
    }

    public OrderStore(String connectionString) {
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
        String sql = "CREATE TABLE IF NOT EXISTS orders (" +
                "id TEXT PRIMARY KEY, " +
                "order_date TEXT, " +
                "name TEXT, " +
                "phone TEXT, " +
                "address TEXT, " +
                "note TEXT, " +
                "fulfillment TEXT, " +
                "pay_method TEXT, " +
                "items TEXT, " +
                "subtotal DOUBLE PRECISION, " +
                "delivery_fee DOUBLE PRECISION, " +
                "total DOUBLE PRECISION, " +
                "payment_status TEXT, " +
                "order_status TEXT, " +
                "razorpay_payment_link_id TEXT, " +
                "user_id TEXT" +
                ")";
        // ADD COLUMN IF NOT EXISTS also covers databases created before this column
        // existed, so existing orders keep working without any manual migration.
        String addUserIdColumn = "ALTER TABLE orders ADD COLUMN IF NOT EXISTS user_id TEXT";
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
            st.execute(addUserIdColumn);
        } catch (SQLException e) {
            throw new RuntimeException("Could not connect to the database — check DATABASE_URL in .env: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> add(Map<String, Object> order) {
        String sql = "INSERT INTO orders (id, order_date, name, phone, address, note, fulfillment, " +
                "pay_method, items, subtotal, delivery_fee, total, payment_status, order_status, razorpay_payment_link_id, user_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, str(order.get("id")));
            ps.setString(2, str(order.get("date")));
            ps.setString(3, str(order.get("name")));
            ps.setString(4, str(order.get("phone")));
            ps.setString(5, str(order.getOrDefault("address", "")));
            ps.setString(6, str(order.getOrDefault("note", "")));
            ps.setString(7, str(order.get("fulfillment")));
            ps.setString(8, str(order.get("payMethod")));
            ps.setString(9, Json.write(order.get("items")));
            ps.setDouble(10, num(order.get("subtotal")));
            ps.setDouble(11, num(order.get("deliveryFee")));
            ps.setDouble(12, num(order.get("total")));
            ps.setString(13, str(order.get("paymentStatus")));
            ps.setString(14, str(order.get("orderStatus")));
            ps.setString(15, str(order.get("razorpayPaymentLinkId")));
            ps.setString(16, str(order.get("userId"))); // null for guest checkouts
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save order: " + e.getMessage(), e);
        }
        return order;
    }

    /** All orders placed by a logged-in customer, most recent first. */
    public List<Map<String, Object>> findByUserId(String userId) {
        String sql = "SELECT * FROM orders WHERE user_id = ? ORDER BY order_date DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read orders: " + e.getMessage(), e);
        }
        return result;
    }

    public List<Map<String, Object>> readAll() {
        String sql = "SELECT * FROM orders ORDER BY order_date DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(rowToMap(rs));
        } catch (SQLException e) {
            throw new RuntimeException("Could not read orders: " + e.getMessage(), e);
        }
        return result;
    }

    public Map<String, Object> findById(String id) {
        String sql = "SELECT * FROM orders WHERE id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not find order: " + e.getMessage(), e);
        }
        return null;
    }

    public Map<String, Object> findByPaymentLinkId(String plId) {
        String sql = "SELECT * FROM orders WHERE razorpay_payment_link_id = ?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, plId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not find order: " + e.getMessage(), e);
        }
        return null;
    }

    public Map<String, Object> update(String id, Map<String, Object> patch) {
        if (patch.isEmpty()) return findById(id);
        StringBuilder sql = new StringBuilder("UPDATE orders SET ");
        List<Object> values = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String col = FIELD_TO_COLUMN.getOrDefault(e.getKey(), e.getKey());
            if (!first) sql.append(", ");
            sql.append(col).append(" = ?");
            values.add(e.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        values.add(id);

        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
            int updated = ps.executeUpdate();
            if (updated == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("Could not update order: " + e.getMessage(), e);
        }
        return findById(id);
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("date", rs.getString("order_date"));
        m.put("name", rs.getString("name"));
        m.put("phone", rs.getString("phone"));
        m.put("address", rs.getString("address"));
        m.put("note", rs.getString("note"));
        m.put("fulfillment", rs.getString("fulfillment"));
        m.put("payMethod", rs.getString("pay_method"));
        m.put("items", Json.parse(rs.getString("items")));
        m.put("subtotal", rs.getDouble("subtotal"));
        m.put("deliveryFee", rs.getDouble("delivery_fee"));
        m.put("total", rs.getDouble("total"));
        m.put("paymentStatus", rs.getString("payment_status"));
        m.put("orderStatus", rs.getString("order_status"));
        m.put("razorpayPaymentLinkId", rs.getString("razorpay_payment_link_id"));
        m.put("userId", rs.getString("user_id"));
        return m;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double num(Object o) { return (o instanceof Number) ? ((Number) o).doubleValue() : 0; }

    /* ---------------- sales analytics ---------------- */

    /**
     * Summarizes orders into totals, a top-selling-items list, and a per-day
     * breakdown for the last 7 days — everything the admin dashboard's
     * Analytics tab shows. Computed on the fly from the orders table each
     * time (fine at this scale — no separate reporting table needed).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyticsSummary() {
        List<Map<String, Object>> orders = readAll();

        double totalRevenue = 0;
        int totalOrders = orders.size();
        Map<String, double[]> itemTotals = new LinkedHashMap<>(); // name -> [qty, revenue]
        Map<String, double[]> dayTotals = new LinkedHashMap<>();  // yyyy-MM-dd -> [orders, revenue]

        for (Map<String, Object> order : orders) {
            double total = num(order.get("total"));
            totalRevenue += total;

            String dateStr = str(order.get("date"));
            String day = (dateStr != null && dateStr.length() >= 10) ? dateStr.substring(0, 10) : "unknown";
            double[] dayAgg = dayTotals.computeIfAbsent(day, k -> new double[2]);
            dayAgg[0] += 1;
            dayAgg[1] += total;

            Object itemsObj = order.get("items");
            if (itemsObj instanceof List) {
                for (Object itemObj : (List<Object>) itemsObj) {
                    if (!(itemObj instanceof Map)) continue;
                    Map<String, Object> item = (Map<String, Object>) itemObj;
                    String name = str(item.get("name"));
                    if (name == null) continue;
                    double qty = num(item.get("qty"));
                    double price = num(item.get("price"));
                    double[] agg = itemTotals.computeIfAbsent(name, k -> new double[2]);
                    agg[0] += qty;
                    agg[1] += qty * price;
                }
            }
        }

        List<Map<String, Object>> topItems = new ArrayList<>();
        for (Map.Entry<String, double[]> e : itemTotals.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("qty", e.getValue()[0]);
            m.put("revenue", e.getValue()[1]);
            topItems.add(m);
        }
        topItems.sort((a, b) -> Double.compare((double) b.get("qty"), (double) a.get("qty")));
        if (topItems.size() > 10) topItems = topItems.subList(0, 10);

        List<String> last7Days = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        for (int i = 6; i >= 0; i--) last7Days.add(today.minusDays(i).toString());

        List<Map<String, Object>> dailyBreakdown = new ArrayList<>();
        for (String day : last7Days) {
            double[] agg = dayTotals.getOrDefault(day, new double[2]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day);
            m.put("orders", (int) agg[0]);
            m.put("revenue", agg[1]);
            dailyBreakdown.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalOrders", totalOrders);
        result.put("totalRevenue", totalRevenue);
        result.put("topItems", topItems);
        result.put("last7Days", dailyBreakdown);
        return result;
    }
}
