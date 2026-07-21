package geoplanph;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** POSTs {"epcId":"<EPC>"} to the backend. Failures are logged, never fatal. */
public class BackendClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String url;
    private final String apiKey;

    public BackendClient(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    /** @return true if the backend accepted the read (2xx), false otherwise. */
    public boolean sendRead(String epc) {
        String body = "{\"epcId\":\"" + epc + "\"}";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (!apiKey.isEmpty()) builder.header("x-api-key", apiKey);
        HttpRequest req = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            RfidBridge.log("POST %s -> %d  %s", epc, resp.statusCode(), oneLine(resp.body()));
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            RfidBridge.log("POST %s FAILED: %s", epc, e.getMessage());
            return false;
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }
}
