import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class RazorpayClient {
    private final String keyId;
    private final String keySecret;
    private final HttpClient http = HttpClient.newHttpClient();

    public RazorpayClient(String keyId, String keySecret) {
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    /**
     * Creates a Razorpay Payment Link and returns the parsed JSON response
     * (contains "short_url" and "id" among other fields).
     * Throws RuntimeException with Razorpay's error message on failure.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPaymentLink(double amountRupees, String description,
                                                  String referenceId, String customerName, String customerPhone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Math.round(amountRupees * 100)); // paise
        body.put("currency", "INR");
        body.put("description", description);
        body.put("reference_id", referenceId);

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", customerName);
        customer.put("contact", customerPhone);
        body.put("customer", customer);

        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("sms", false);
        notify.put("email", false);
        body.put("notify", notify);
        body.put("reminder_enable", false);

        String auth = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.razorpay.com/v1/payment_links"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(resp.body());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Object err = parsed.get("error");
            String msg = "Razorpay error";
            if (err instanceof Map) {
                Object desc = ((Map<String, Object>) err).get("description");
                if (desc != null) msg = desc.toString();
            }
            throw new RuntimeException(msg);
        }
        return parsed;
    }

    /** Verifies an X-Razorpay-Signature header against the raw webhook body. */
    public static boolean verifySignature(byte[] rawBody, String signatureHeader, String webhookSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().equals(signatureHeader);
        } catch (Exception e) {
            return false;
        }
    }
}
