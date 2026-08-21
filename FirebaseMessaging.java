import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends push notifications through Firebase Cloud Messaging (FCM), using the
 * service account key file from Firebase Console -> Project Settings ->
 * Service Accounts.
 *
 * This hand-rolls the two things the official Firebase Admin SDK would
 * normally do for us — deliberately, to avoid adding a dependency-management
 * tool to a project that's otherwise just plain .java files:
 *  1. Turn the service account's private key into a short-lived Google OAuth2
 *     access token (the "JWT Bearer" flow — sign a JWT with the private key,
 *     trade it in at Google's token endpoint).
 *  2. Call FCM's HTTP v1 "send" endpoint with that access token.
 *
 * If the service account file is missing, every send silently does nothing
 * (logs a message) instead of crashing the server — so this feature can be
 * turned on later without breaking anything in the meantime.
 */
public class FirebaseMessaging {
    private final String projectId;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final HttpClient http = HttpClient.newHttpClient();

    private String cachedAccessToken;
    private long cachedTokenExpiresAt;

    private final boolean enabled;

    public FirebaseMessaging(String serviceAccountPath) {
        String pid = null, email = null;
        PrivateKey key = null;
        boolean ok = false;
        try {
            String json = Files.readString(Path.of(serviceAccountPath));
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) Json.parse(json);
            pid = (String) parsed.get("project_id");
            email = (String) parsed.get("client_email");
            String pem = (String) parsed.get("private_key");
            key = parsePrivateKey(pem);
            ok = true;
        } catch (Exception e) {
            System.out.println("[FirebaseMessaging] Push notifications are OFF — could not load " +
                    serviceAccountPath + " (" + e.getMessage() + "). Everything else still works fine.");
        }
        this.projectId = pid;
        this.clientEmail = email;
        this.privateKey = key;
        this.enabled = ok;
    }

    public boolean isEnabled() { return enabled; }

    /** Sends the same notification to several device tokens (e.g. all of one customer's devices). Never throws. */
    public void sendToTokens(List<String> tokens, String title, String body) {
        if (!enabled || tokens == null) return;
        for (String token : tokens) {
            try {
                sendToToken(token, title, body);
            } catch (Exception e) {
                System.out.println("[FirebaseMessaging] Could not send to a device token: " + e.getMessage());
            }
        }
    }

    private void sendToToken(String token, String title, String body) throws Exception {
        String accessToken = getAccessToken();

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", notification);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new RuntimeException("FCM returned HTTP " + res.statusCode() + ": " + res.body());
        }
    }

    /* ---------------- OAuth2 access token (cached ~55 minutes) ---------------- */

    @SuppressWarnings("unchecked")
    private synchronized String getAccessToken() throws Exception {
        if (cachedAccessToken != null && System.currentTimeMillis() < cachedTokenExpiresAt) {
            return cachedAccessToken;
        }

        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
        claims.put("aud", "https://oauth2.googleapis.com/token");
        claims.put("iat", now);
        claims.put("exp", now + 3600);

        String headerB64 = base64UrlEncode(Json.write(header).getBytes(StandardCharsets.UTF_8));
        String claimsB64 = base64UrlEncode(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + claimsB64;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String signatureB64 = base64UrlEncode(signer.sign());

        String jwt = signingInput + "." + signatureB64;

        String form = "grant_type=" + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
                "&assertion=" + java.net.URLEncoder.encode(jwt, "UTF-8");

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Could not get an access token from Google (HTTP " + res.statusCode() + "): " + res.body());
        }
        Map<String, Object> body = (Map<String, Object>) Json.parse(res.body());
        cachedAccessToken = (String) body.get("access_token");
        long expiresIn = body.get("expires_in") instanceof Number ? ((Number) body.get("expires_in")).longValue() : 3600;
        cachedTokenExpiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L; // refresh 5 min early
        return cachedAccessToken;
    }

    /* ---------------- helpers ---------------- */

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] bytes = Base64.getDecoder().decode(cleaned);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
