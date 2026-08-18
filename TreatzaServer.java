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

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", TreatzaServer::handleHealth);
        server.createContext("/api/orders", TreatzaServer::handleOrders);
        server.createContext("/api/payment-links", TreatzaServer::handlePaymentLinks);
        server.createContext("/api/razorpay-webhook", TreatzaServer::handleWebhook);
        server.createContext("/api/auth", TreatzaServer::handleAuth);
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
        if (key == null || !key.equals(adminKey)) {
            sendError(ex, 401, "Invalid admin key");
            return false;
        }
        return true;
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
        h.set("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
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
