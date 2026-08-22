import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Looks up the driving distance between the bakery and a customer's address
 * using Google's Distance Matrix API, so delivery fees can scale with how far
 * away the order is instead of being a single flat rate.
 *
 * Requires a Google Cloud API key with the "Distance Matrix API" enabled and
 * billing set up on the Google Cloud project (Google gives a recurring free
 * monthly credit, but the API itself isn't usable at all without a billing
 * account attached — that's Google's requirement, not something this code
 * can work around).
 */
public class MapsClient {
    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();

    public MapsClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public static class DistanceResult {
        public final boolean found;
        public final double distanceKm;
        public final String errorMessage;
        private DistanceResult(boolean found, double distanceKm, String errorMessage) {
            this.found = found; this.distanceKm = distanceKm; this.errorMessage = errorMessage;
        }
        static DistanceResult ok(double km) { return new DistanceResult(true, km, null); }
        static DistanceResult fail(String msg) { return new DistanceResult(false, 0, msg); }
    }

    /** Driving distance in kilometers from `origin` to `destination`, both as free-text addresses. */
    @SuppressWarnings("unchecked")
    public DistanceResult getDistanceKm(String origin, String destination) {
        if (!isEnabled()) return DistanceResult.fail("Delivery-distance lookup isn't set up yet");
        if (origin == null || origin.trim().isEmpty()) return DistanceResult.fail("Bakery address isn't set — see the admin dashboard's Settings tab");
        if (destination == null || destination.trim().isEmpty()) return DistanceResult.fail("Enter a delivery address");

        try {
            String url = "https://maps.googleapis.com/maps/api/distancematrix/json"
                    + "?origins=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&destinations=" + URLEncoder.encode(destination, StandardCharsets.UTF_8)
                    + "&mode=driving&key=" + apiKey;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = (Map<String, Object>) Json.parse(res.body());

            String topStatus = (String) body.get("status");
            if (!"OK".equals(topStatus)) {
                return DistanceResult.fail("Could not look up distance (" + topStatus + ")");
            }

            List<Object> rows = (List<Object>) body.get("rows");
            Map<String, Object> row = (Map<String, Object>) rows.get(0);
            List<Object> elements = (List<Object>) row.get("elements");
            Map<String, Object> element = (Map<String, Object>) elements.get(0);
            String elementStatus = (String) element.get("status");

            if ("NOT_FOUND".equals(elementStatus) || "ZERO_RESULTS".equals(elementStatus)) {
                return DistanceResult.fail("Could not find that address");
            }
            if (!"OK".equals(elementStatus)) {
                return DistanceResult.fail("Could not look up distance (" + elementStatus + ")");
            }

            Map<String, Object> distance = (Map<String, Object>) element.get("distance");
            double meters = ((Number) distance.get("value")).doubleValue();
            return DistanceResult.ok(meters / 1000.0);
        } catch (Exception e) {
            return DistanceResult.fail("Could not reach the maps service: " + e.getMessage());
        }
    }
}
