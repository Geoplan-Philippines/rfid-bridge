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

    public BackendClient(String url) { this.url = url; }

    public void sendRead(String epc) {
        String body = "{\"epcId\":\"" + epc + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            RfidBridge.log("POST %s -> %d  %s", epc, resp.statusCode(), oneLine(resp.body()));
        } catch (Exception e) {
            RfidBridge.log("POST %s FAILED: %s", epc, e.getMessage());
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }
}
