import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Verifies Firebase Authentication ID tokens sent by the app after a customer
 * logs in with email/password or Google Sign-In.
 *
 * This is a small hand-written implementation of what the official Firebase
 * Admin SDK does — deliberately kept dependency-free (uses only classes built
 * into the JDK) to match the rest of this project, which avoids adding a
 * build tool just to pull in one library.
 *
 * How it works: Firebase ID tokens are JWTs signed by Google with a key that
 * rotates periodically. Google publishes the current public keys (as X.509
 * certificates) at a fixed URL — we fetch and cache those, use the token's
 * "kid" header to pick the right one, and verify the RS256 signature plus
 * the standard claims (issuer, audience, expiry).
 */
public class FirebaseAuth {
    private static final String CERTS_URL =
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
    private static final long CERTS_CACHE_MILLIS = 60L * 60 * 1000; // 1 hour

    private final String projectId;
    private final HttpClient http = HttpClient.newHttpClient();

    private Map<String, Object> cachedCerts = null;
    private long cachedAt = 0;

    public FirebaseAuth(String projectId) {
        this.projectId = projectId;
    }

    public static class VerifiedUser {
        public final String uid;
        public final String email;
        public final String name;
        public VerifiedUser(String uid, String email, String name) {
            this.uid = uid; this.email = email; this.name = name;
        }
    }

    /** Returns the verified user, or throws with a human-readable reason if the token is invalid. */
    @SuppressWarnings("unchecked")
    public VerifiedUser verifyIdToken(String idToken) {
        if (idToken == null || idToken.isEmpty()) throw new RuntimeException("Missing ID token");
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) throw new RuntimeException("Malformed token");

        Map<String, Object> header = (Map<String, Object>) Json.parse(new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8));
        Map<String, Object> payload = (Map<String, Object>) Json.parse(new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8));
        byte[] signature = base64UrlDecode(parts[2]);
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);

        if (!"RS256".equals(header.get("alg"))) throw new RuntimeException("Unexpected signing algorithm");
        String kid = (String) header.get("kid");
        if (kid == null) throw new RuntimeException("Token has no key id");

        PublicKey publicKey = getPublicKey(kid);
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) throw new RuntimeException("Invalid token signature");
        } catch (Exception e) {
            throw new RuntimeException("Could not verify token signature: " + e.getMessage(), e);
        }

        String expectedIssuer = "https://securetoken.google.com/" + projectId;
        Object iss = payload.get("iss");
        Object aud = payload.get("aud");
        Object exp = payload.get("exp");
        Object sub = payload.get("sub");
        if (!expectedIssuer.equals(iss)) throw new RuntimeException("Unexpected token issuer");
        if (!projectId.equals(aud)) throw new RuntimeException("Unexpected token audience");
        if (sub == null || sub.toString().isEmpty()) throw new RuntimeException("Token has no subject");
        long expSeconds = exp instanceof Number ? ((Number) exp).longValue() : 0;
        if (expSeconds < Instant.now().getEpochSecond()) throw new RuntimeException("Token has expired");

        String email = payload.get("email") == null ? null : payload.get("email").toString();
        Object nameObj = payload.get("name");
        String name = nameObj == null ? null : nameObj.toString();
        return new VerifiedUser(sub.toString(), email, name);
    }

    /* ---------------- Google's public certs (cached) ---------------- */

    @SuppressWarnings("unchecked")
    private PublicKey getPublicKey(String kid) {
        if (cachedCerts == null || System.currentTimeMillis() - cachedAt > CERTS_CACHE_MILLIS) {
            refreshCerts();
        }
        Object pem = cachedCerts.get(kid);
        if (pem == null) {
            // Key rotated since our last fetch — refresh once more before giving up.
            refreshCerts();
            pem = cachedCerts.get(kid);
        }
        if (pem == null) throw new RuntimeException("Unknown signing key — could not verify token");

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(pem.toString().getBytes(StandardCharsets.UTF_8)));
            return cert.getPublicKey();
        } catch (Exception e) {
            throw new RuntimeException("Could not parse Google's signing certificate: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void refreshCerts() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CERTS_URL)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new RuntimeException("Could not fetch Google's signing certs (HTTP " + res.statusCode() + ")");
            cachedCerts = (Map<String, Object>) Json.parse(res.body());
            cachedAt = System.currentTimeMillis();
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch Google's signing certs: " + e.getMessage(), e);
        }
    }

    /* ---------------- helpers ---------------- */

    private static byte[] base64UrlDecode(String s) {
        String padded = s;
        int mod = s.length() % 4;
        if (mod != 0) padded += "====".substring(mod);
        return Base64.getUrlDecoder().decode(padded);
    }
}
