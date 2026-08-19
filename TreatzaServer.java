import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

public class TreatzaServer {

    static String adminKey;
    static String razorpayKeyId;
    static String razorpaySecret;
    static String razorpayWebhookSecret;
    static OrderStore store;
    static UserStore users;
    static ProductStore products;
    static SettingsStore settings;

    public static void main(String[] args) throws Exception {
        Env env = new Env(".env");
        int port = env.getInt("PORT", 4000);
        adminKey = env.get("ADMIN_KEY", "treatza123");
        razorpayKeyId = env.get("RAZORPAY_KEY_ID", "");
        razorpaySecret = env.get("RAZORPAY_KEY_SECRET", "");
        razorpayWebhookSecret = env.get("RAZORPAY_WEBHOOK_SECRET", "");
        String databaseUrl = env.get("DATABASE_URL", "");
        store = new OrderStore(databaseUrl);
        users = new UserStore(databaseUrl);
        products = new ProductStore(databaseUrl);
        settings = new SettingsStore(databaseUrl);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", TreatzaServer::handleHealth);
        server.createContext("/api/orders", TreatzaServer::handleOrders);
        server.createContext("/api/payment-links", TreatzaServer::handlePaymentLinks);
        server.createContext("/api/razorpay-webhook", TreatzaServer::handleWebhook);
        server.createContext("/api/auth", TreatzaServer::handleAuth);
        server.createContext("/api/products", TreatzaServer::handleProducts);
        server.createContext("/api/admin/products", TreatzaServer::handleAdminProducts);
        server.createContext("/api/store-status", TreatzaServer::handleStoreStatus);
        server.createContext("/api/admin/store-status", TreatzaServer::handleAdminStoreStatus);
        server.createContext("/api/admin/analytics", TreatzaServer::handleAnalytics);
        server.createContext("/api/admin/staff", TreatzaServer::handleStaff);
        server.createContext("/api/admin/reset-codes", TreatzaServer::handleResetCodes);
        server.createContext("/", TreatzaServer::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Treatza backend running on http://localhost:" + port);
        System.out.println("Admin dashboard: http://localhost:" + port + "/admin.html?key=" + adminKey);
    }

    /* ---------------- routes ---------------- */

    static void handleHealth(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("time", Instant.now().toString());
        sendJson(ex, 200, body);
    }

    @SuppressWarnings("unchecked")
    static void handleOrders(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/orders")) {
            if (method.equals("POST")) {
                Map<String, Object> body;
                try {
                    body = (Map<String, Object>) Json.parse(readBody(ex));
                } catch (Exception e) {
                    sendError(ex, 400, "Invalid JSON body");
                    return;
                }
                List<Object> items = (List<Object>) body.get("items");
                if (isBlank(Json.getString(body, "name", "")) || isBlank(Json.getString(body, "phone", ""))
                        || items == null || items.isEmpty()) {
                    sendError(ex, 400, "Missing required order fields");
                    return;
                }
                String payMethod = Json.getString(body, "payMethod", "cod").equals("upi") ? "upi" : "cod";

                // If the customer is logged in (Authorization header present), tag the
                // order with their account so it shows up in "my orders" on any device.
                // Guest checkouts (no/invalid token) still work fine — userId stays null.
                Map<String, Object> loggedInUser = users.userForToken(bearerToken(ex));

                Map<String, Object> order = new LinkedHashMap<>();
                order.put("id", genOrderId());
                order.put("date", Instant.now().toString());
                order.put("name", body.get("name"));
                order.put("phone", body.get("phone"));
                order.put("address", body.getOrDefault("address", ""));
                order.put("note", body.getOrDefault("note", ""));
                order.put("fulfillment", Json.getString(body, "fulfillment", "pickup").equals("delivery") ? "delivery" : "pickup");
                order.put("payMethod", payMethod);
                order.put("items", items);
                order.put("subtotal", Json.getDouble(body, "subtotal", 0));
                order.put("deliveryFee", Json.getDouble(body, "deliveryFee", 0));
                order.put("total", Json.getDouble(body, "total", 0));
                order.put("paymentStatus", payMethod.equals("upi") ? "awaiting_payment" : "cod_pending");
                order.put("orderStatus", "received");
                order.put("razorpayPaymentLinkId", null);
                order.put("userId", loggedInUser == null ? null : loggedInUser.get("id"));

                store.add(order);
                sendJson(ex, 201, order);
                return;
            }
            if (method.equals("GET")) {
                if (!checkAdmin(ex)) return;
                List<Map<String, Object>> orders = store.readAll();
                Collections.reverse(orders);
                sendJson(ex, 200, orders);
                return;
            }
            sendError(ex, 405, "Method not allowed");
            return;
        }

        // /api/orders/mine — logged-in customer's own order history, across devices
        if (path.equals("/api/orders/mine")) {
            if (!method.equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }
            Map<String, Object> user = users.userForToken(bearerToken(ex));
            if (user == null) { sendError(ex, 401, "Not logged in"); return; }
            List<Map<String, Object>> orders = store.findByUserId((String) user.get("id"));
            sendJson(ex, 200, orders);
            return;
        }

        // /api/orders/{id}
        if (path.startsWith("/api/orders/")) {
            String id = path.substring("/api/orders/".length());
            if (method.equals("PATCH")) {
                if (!checkAdmin(ex)) return;
                Map<String, Object> body;
                try {
                    body = (Map<String, Object>) Json.parse(readBody(ex));
                } catch (Exception e) {
                    sendError(ex, 400, "Invalid JSON body");
                    return;
                }
                Map<String, Object> patch = new LinkedHashMap<>();
                if (body.get("orderStatus") != null) patch.put("orderStatus", body.get("orderStatus"));
                Map<String, Object> updated = store.update(id, patch);
                if (updated == null) { sendError(ex, 404, "Order not found"); return; }
                sendJson(ex, 200, updated);
                return;
            }
            sendError(ex, 405, "Method not allowed");
            return;
        }

        sendError(ex, 404, "Not found");
    }

    @SuppressWarnings("unchecked")
    static void handlePaymentLinks(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!ex.getRequestMethod().equals("POST")) { sendError(ex, 405, "Method not allowed"); return; }

        if (isBlank(razorpayKeyId) || isBlank(razorpaySecret)) {
            sendError(ex, 500, "Razorpay keys are not configured on the server (.env)");
            return;
        }

        Map<String, Object> body;
        try {
            body = (Map<String, Object>) Json.parse(readBody(ex));
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON body");
            return;
        }

        String orderId = Json.getString(body, "orderId", "");
        double amount = Json.getDouble(body, "amount", 0);
        String name = Json.getString(body, "name", "");
        String phone = Json.getString(body, "phone", "");

        Map<String, Object> order = store.findById(orderId);
        if (order == null) { sendError(ex, 404, "Order not found"); return; }

        try {
            RazorpayClient client = new RazorpayClient(razorpayKeyId, razorpaySecret);
            Map<String, Object> link = client.createPaymentLink(amount, "Treatza order " + orderId, orderId, name, phone);
            store.update(orderId, Collections.singletonMap("razorpayPaymentLinkId", link.get("id")));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("short_url", link.get("short_url"));
            resp.put("id", link.get("id"));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, "Could not create payment link: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static void handleWebhook(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!ex.getRequestMethod().equals("POST")) { sendError(ex, 405, "Method not allowed"); return; }

        byte[] raw = ex.getRequestBody().readAllBytes();
        String signature = ex.getRequestHeaders().getFirst("X-Razorpay-Signature");

        if (!isBlank(razorpayWebhookSecret)) {
            if (signature == null || !RazorpayClient.verifySignature(raw, signature, razorpayWebhookSecret)) {
                sendError(ex, 400, "Invalid webhook signature");
                return;
            }
        }

        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) Json.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            sendError(ex, 400, "Bad payload");
            return;
        }

        if ("payment_link.paid".equals(payload.get("event"))) {
            try {
                Map<String, Object> plPayload = (Map<String, Object>) payload.get("payload");
                Map<String, Object> plWrapper = (Map<String, Object>) plPayload.get("payment_link");
                Map<String, Object> plEntity = (Map<String, Object>) plWrapper.get("entity");
                String plId = (String) plEntity.get("id");
                Map<String, Object> order = store.findByPaymentLinkId(plId);
                if (order != null) {
                    store.update((String) order.get("id"), Collections.singletonMap("paymentStatus", "paid"));
                }
            } catch (Exception ignored) {
                // malformed payload — ignore, nothing to update
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("received", true);
        sendJson(ex, 200, resp);
    }

    /* ---------------- customer auth (signup / login / me / logout) ---------------- */

    @SuppressWarnings("unchecked")
    static void handleAuth(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/auth/signup") && method.equals("POST")) {
            Map<String, Object> body;
            try {
                body = (Map<String, Object>) Json.parse(readBody(ex));
            } catch (Exception e) {
                sendError(ex, 400, "Invalid JSON body");
                return;
            }
            UserStore.AuthResult result = users.signup(
                    Json.getString(body, "name", ""),
                    Json.getString(body, "phone", ""),
                    Json.getString(body, "password", ""));
            if (!result.ok) { sendError(ex, 409, result.errorMessage); return; }
            sendJson(ex, 201, authResponse(result));
            return;
        }

        if (path.equals("/api/auth/login") && method.equals("POST")) {
            Map<String, Object> body;
            try {
                body = (Map<String, Object>) Json.parse(readBody(ex));
            } catch (Exception e) {
                sendError(ex, 400, "Invalid JSON body");
                return;
            }
            UserStore.AuthResult result = users.login(
                    Json.getString(body, "phone", ""),
                    Json.getString(body, "password", ""));
            if (!result.ok) { sendError(ex, 401, result.errorMessage); return; }
            sendJson(ex, 200, authResponse(result));
            return;
        }

        if (path.equals("/api/auth/logout") && method.equals("POST")) {
            users.logout(bearerToken(ex));
            sendJson(ex, 200, Collections.singletonMap("ok", true));
            return;
        }

        if (path.equals("/api/auth/me") && method.equals("GET")) {
            Map<String, Object> user = users.userForToken(bearerToken(ex));
            if (user == null) { sendError(ex, 401, "Not logged in"); return; }
            sendJson(ex, 200, user);
            return;
        }

        if (path.equals("/api/auth/forgot-password") && method.equals("POST")) {
            Map<String, Object> body;
            try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
            catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
            users.generateResetCode(Json.getString(body, "phone", ""));
            // Same response whether or not the phone is registered, so this can't be used
            // to check which numbers have accounts. The bakery owner relays the actual
            // code to the customer directly — see README for why (no SMS/email service set up).
            sendJson(ex, 200, Collections.singletonMap("message",
                    "If this phone number has an account, a reset code was generated. Contact the bakery to get it."));
            return;
        }

        if (path.equals("/api/auth/reset-password") && method.equals("POST")) {
            Map<String, Object> body;
            try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
            catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
            UserStore.AuthResult result = users.resetPassword(
                    Json.getString(body, "phone", ""),
                    Json.getString(body, "code", ""),
                    Json.getString(body, "newPassword", ""));
            if (!result.ok) { sendError(ex, 400, result.errorMessage); return; }
            sendJson(ex, 200, authResponse(result));
            return;
        }

        sendError(ex, 404, "Not found");
    }

    static Map<String, Object> authResponse(UserStore.AuthResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", result.token);
        resp.put("user", result.user);
        return resp;
    }

    static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).trim();
    }

    /* ---------------- product catalog (public reads) ---------------- */

    static void handleProducts(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/products") && method.equals("GET")) {
            sendJson(ex, 200, products.findAll());
            return;
        }

        // /api/products/{id}/photo — raw image bytes, publicly viewable (like any product photo)
        if (path.endsWith("/photo") && method.equals("GET")) {
            String id = path.substring("/api/products/".length(), path.length() - "/photo".length());
            String[] photo = products.getPhoto(id);
            if (photo == null) { sendError(ex, 404, "No photo for this product"); return; }
            byte[] bytes = Base64.getDecoder().decode(photo[0]);
            ex.getResponseHeaders().set("Content-Type", photo[1] == null ? "image/jpeg" : photo[1]);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            return;
        }

        sendError(ex, 404, "Not found");
    }

    /* ---------------- product catalog (admin writes) ---------------- */

    @SuppressWarnings("unchecked")
    static void handleAdminProducts(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!checkAdmin(ex)) return;

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/api/admin/products") && method.equals("POST")) {
            Map<String, Object> body;
            try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
            catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
            String name = Json.getString(body, "name", "").trim();
            String cat = Json.getString(body, "cat", "").trim();
            double price = Json.getDouble(body, "price", -1);
            if (name.isEmpty() || cat.isEmpty() || price < 0) {
                sendError(ex, 400, "name, cat and a valid price are required");
                return;
            }
            String id = "pd-" + Long.toString(System.currentTimeMillis(), 36);
            products.create(id, cat, name, Json.getString(body, "desc", ""), price);
            sendJson(ex, 201, Collections.singletonMap("id", id));
            return;
        }

        // /api/admin/products/{id}/photo
        if (path.endsWith("/photo") && (method.equals("POST") || method.equals("DELETE"))) {
            String id = path.substring("/api/admin/products/".length(), path.length() - "/photo".length());
            if (!products.exists(id)) { sendError(ex, 404, "Product not found"); return; }
            if (method.equals("DELETE")) {
                products.removePhoto(id);
                sendJson(ex, 200, Collections.singletonMap("ok", true));
                return;
            }
            Map<String, Object> body;
            try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
            catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
            String base64 = Json.getString(body, "imageBase64", "");
            String mime = Json.getString(body, "mime", "image/jpeg");
            if (base64.isEmpty()) { sendError(ex, 400, "imageBase64 is required"); return; }
            products.setPhoto(id, base64, mime);
            sendJson(ex, 200, Collections.singletonMap("ok", true));
            return;
        }

        // /api/admin/products/{id}
        if (path.startsWith("/api/admin/products/")) {
            String id = path.substring("/api/admin/products/".length());
            if (id.isEmpty()) { sendError(ex, 404, "Not found"); return; }
            if (!products.exists(id)) { sendError(ex, 404, "Product not found"); return; }

            if (method.equals("PATCH")) {
                Map<String, Object> body;
                try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
                catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
                products.update(id, body);
                sendJson(ex, 200, Collections.singletonMap("ok", true));
                return;
            }
            if (method.equals("DELETE")) {
                products.delete(id);
                sendJson(ex, 200, Collections.singletonMap("ok", true));
                return;
            }
        }

        sendError(ex, 404, "Not found");
    }

    /* ---------------- store open/closed status ---------------- */

    static void handleStoreStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }
        sendJson(ex, 200, settings.getStatus());
    }

    @SuppressWarnings("unchecked")
    static void handleAdminStoreStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!checkAdmin(ex)) return;
        if (!ex.getRequestMethod().equals("PATCH")) { sendError(ex, 405, "Method not allowed"); return; }
        Map<String, Object> body;
        try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
        catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
        settings.setStatus(Json.getBoolean(body, "isOpen", true), Json.getString(body, "message", ""));
        sendJson(ex, 200, settings.getStatus());
    }

    /* ---------------- sales analytics ---------------- */

    static void handleAnalytics(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!checkAdmin(ex)) return;
        if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }
        sendJson(ex, 200, store.analyticsSummary());
    }

    /* ---------------- staff/admin accounts ---------------- */

    @SuppressWarnings("unchecked")
    static void handleStaff(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if (method.equals("GET")) {
            if (!checkAdmin(ex)) return;
            sendJson(ex, 200, users.listStaff());
            return;
        }

        if (method.equals("POST") && path.equals("/api/admin/staff")) {
            // Only the store owner's master key can create new staff/admin logins —
            // an existing staff account can't grant itself or others more access.
            if (!checkMasterAdminKey(ex)) return;
            Map<String, Object> body;
            try { body = (Map<String, Object>) Json.parse(readBody(ex)); }
            catch (Exception e) { sendError(ex, 400, "Invalid JSON body"); return; }
            UserStore.AuthResult result = users.createStaff(
                    Json.getString(body, "name", ""),
                    Json.getString(body, "phone", ""),
                    Json.getString(body, "password", ""),
                    Json.getString(body, "role", "admin"));
            if (!result.ok) { sendError(ex, 409, result.errorMessage); return; }
            sendJson(ex, 201, result.user);
            return;
        }

        if (method.equals("DELETE") && path.startsWith("/api/admin/staff/")) {
            if (!checkMasterAdminKey(ex)) return;
            String id = path.substring("/api/admin/staff/".length());
            users.removeStaff(id);
            sendJson(ex, 200, Collections.singletonMap("ok", true));
            return;
        }

        sendError(ex, 404, "Not found");
    }

    /* ---------------- pending password-reset codes (for the owner to relay) ---------------- */

    static void handleResetCodes(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;
        if (!checkAdmin(ex)) return;
        if (!ex.getRequestMethod().equals("GET")) { sendError(ex, 405, "Method not allowed"); return; }
        sendJson(ex, 200, users.listPendingResetCodes());
    }

    /* ---------------- static file serving (admin.html) ---------------- */

    static void handleStatic(HttpExchange ex) throws IOException {
        addCors(ex);
        if (isPreflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/admin.html";
        if (path.contains("..")) { sendError(ex, 400, "Bad path"); return; }

        Path file = Paths.get("public" + path);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            sendError(ex, 404, "Not found");
            return;
        }

        String contentType = "application/octet-stream";
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) contentType = "text/html; charset=utf-8";
        else if (lower.endsWith(".css")) contentType = "text/css";
        else if (lower.endsWith(".js")) contentType = "application/javascript";
        else if (lower.endsWith(".png")) contentType = "image/png";
        else if (lower.endsWith(".ico")) contentType = "image/x-icon";

        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    /* ---------------- helpers ---------------- */

    static boolean checkAdmin(HttpExchange ex) throws IOException {
        // Prefer the X-Admin-Key header (doesn't get logged in URLs/browser history).
        // The ?key= query param still works too, so old admin.html links keep working.
        String key = ex.getRequestHeaders().getFirst("X-Admin-Key");
        if (key == null) {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            key = query.get("key");
        }
        if (key != null && key.equals(adminKey)) return true;

        // Also accept a logged-in staff/admin account's session token — lets
        // the owner invite staff (POST /api/admin/staff) who can then log in
        // normally with phone+password instead of sharing the master key.
        if (users.tokenHasAdminRole(bearerToken(ex))) return true;

        sendError(ex, 401, "Invalid admin key");
        return false;
    }

    /** True only for the master ADMIN_KEY (from .env) — used to gate creating new staff accounts. */
    static boolean checkMasterAdminKey(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("X-Admin-Key");
        if (key == null) {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            key = query.get("key");
        }
        if (key != null && key.equals(adminKey)) return true;
        sendError(ex, 401, "Only the store owner's master key can do this");
        return false;
    }

    static boolean isPreflight(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    static void addCors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Key");
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void sendJson(HttpExchange ex, int status, Object data) throws IOException {
        byte[] bytes = Json.write(data).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void sendError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        sendJson(ex, status, body);
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (Exception ignored) { }
        }
        return map;
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String genOrderId() {
        String millis = String.valueOf(System.currentTimeMillis());
        return "TZ-" + millis.substring(Math.max(0, millis.length() - 6));
    }
}
