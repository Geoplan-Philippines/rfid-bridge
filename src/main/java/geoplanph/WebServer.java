package geoplanph;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny embedded HTTP server (JDK's com.sun.net.httpserver, no dependencies) that
 * serves the configuration/log dashboard and a small JSON API. Bound to loopback
 * only, so it is never exposed off the machine.
 */
public class WebServer {

    private final BridgeService service;
    private final int port;
    private HttpServer http;

    public WebServer(BridgeService service, int port) {
        this.service = service;
        this.port = port;
    }

    public int port() { return port; }
    public String url() { return "http://localhost:" + port + "/"; }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/", this::serveIndex);
        http.createContext("/api/status", this::apiStatus);
        http.createContext("/api/config", this::apiConfig);
        http.createContext("/api/logs", this::apiLogs);
        http.createContext("/api/control", this::apiControl);
        http.createContext("/api/startup", this::apiStartup);
        http.createContext("/api/open-logs", this::apiOpenLogs);
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        http.start();
        Log.info("Dashboard available at " + url());
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ---- handlers -----------------------------------------------------------

    private void serveIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
        byte[] page = readWebAsset("index.html");
        sendBytes(ex, 200, "text/html; charset=utf-8", page);
    }

    private void apiStatus(HttpExchange ex) throws IOException {
        Config c = service.config();
        long uptime = service.isRunning() ? System.currentTimeMillis() - service.startedAt() : 0;
        StringBuilder j = new StringBuilder("{");
        j.append("\"running\":").append(service.isRunning());
        j.append(",\"port\":").append(c.listenPort);
        j.append(",\"backendUrl\":\"").append(esc(c.backendUrl)).append('"');
        j.append(",\"postEnabled\":").append(c.postEnabled);
        j.append(",\"frameFormat\":\"").append(esc(c.frameFormat.name())).append('"');
        j.append(",\"readerPeer\":").append(service.readerPeer() == null ? "null" : "\"" + esc(service.readerPeer()) + "\"");
        j.append(",\"lastError\":").append(service.lastError() == null ? "null" : "\"" + esc(service.lastError()) + "\"");
        j.append(",\"transactionsOpened\":").append(service.transactionsOpened());
        j.append(",\"postsOk\":").append(service.postsOk());
        j.append(",\"postsFailed\":").append(service.postsFailed());
        j.append(",\"uptimeMs\":").append(uptime);
        j.append(",\"startupEnabled\":").append(Startup.isEnabled());
        j.append(",\"logFile\":\"").append(esc(Log.logsDir().resolve("bridge.log").toString())).append('"');
        j.append(",\"configPath\":\"").append(esc(ConfigStore.path().toString())).append('"');
        j.append("}");
        sendJson(ex, 200, j.toString());
    }

    private void apiConfig(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, String> values;
            try { values = ConfigStore.currentValues(); }
            catch (IOException e) { sendJson(ex, 500, err(e.getMessage())); return; }
            StringBuilder j = new StringBuilder("{\"path\":\"").append(esc(ConfigStore.path().toString())).append("\",\"values\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!first) j.append(',');
                j.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
                first = false;
            }
            j.append("}}");
            sendJson(ex, 200, j.toString());
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, String> updates = Json.parseFlatObject(readBody(ex));
            // Keep only managed keys.
            Map<String, String> clean = new LinkedHashMap<>();
            for (String k : ConfigStore.MANAGED) if (updates.containsKey(k)) clean.put(k, updates.get(k));
            try {
                ConfigStore.save(clean);
                Config fresh = ConfigStore.load();
                service.restart(fresh);
                sendJson(ex, 200, "{\"ok\":true,\"running\":" + service.isRunning()
                        + ",\"error\":" + (service.lastError() == null ? "null" : "\"" + esc(service.lastError()) + "\"") + "}");
            } catch (Exception e) {
                sendJson(ex, 500, err(e.getMessage()));
            }
            return;
        }
        send(ex, 405, "text/plain", "Method not allowed");
    }

    private void apiLogs(HttpExchange ex) throws IOException {
        long since = 0;
        String q = ex.getRequestURI().getQuery();
        if (q != null && q.startsWith("since=")) {
            try { since = Long.parseLong(q.substring("since=".length())); } catch (NumberFormatException ignored) { }
        }
        List<Log.Entry> entries = Log.since(since);
        StringBuilder j = new StringBuilder("{\"last\":").append(Log.lastSeq()).append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            Log.Entry e = entries.get(i);
            if (i > 0) j.append(',');
            j.append("{\"seq\":").append(e.seq)
             .append(",\"time\":\"").append(esc(e.time)).append('"')
             .append(",\"level\":\"").append(esc(e.level)).append('"')
             .append(",\"text\":\"").append(esc(e.text)).append("\"}");
        }
        j.append("]}");
        sendJson(ex, 200, j.toString());
    }

    private void apiControl(HttpExchange ex) throws IOException {
        Map<String, String> body = Json.parseFlatObject(readBody(ex));
        String action = body.getOrDefault("action", "");
        switch (action) {
            case "start"   -> service.start();
            case "stop"    -> service.stop();
            case "restart" -> service.restart(ConfigStore.load());
            default -> { sendJson(ex, 400, err("unknown action")); return; }
        }
        sendJson(ex, 200, "{\"ok\":true,\"running\":" + service.isRunning()
                + ",\"error\":" + (service.lastError() == null ? "null" : "\"" + esc(service.lastError()) + "\"") + "}");
    }

    private void apiStartup(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, String> body = Json.parseFlatObject(readBody(ex));
            boolean enabled = "true".equalsIgnoreCase(body.getOrDefault("enabled", "false"));
            Startup.setEnabled(enabled);
        }
        sendJson(ex, 200, "{\"enabled\":" + Startup.isEnabled() + "}");
    }

    private void apiOpenLogs(HttpExchange ex) throws IOException {
        try {
            Files.createDirectories(Log.logsDir());
            new ProcessBuilder("explorer.exe", Log.logsDir().toString()).start();
        } catch (Exception ignored) { }
        sendJson(ex, 200, "{\"ok\":true}");
    }

    // ---- asset loading ------------------------------------------------------

    private byte[] readWebAsset(String name) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/geoplanph/webui/" + name)) {
            if (in != null) return in.readAllBytes();
        }
        Path dev = Path.of("src/main/resources/geoplanph/webui/" + name);
        if (Files.exists(dev)) return Files.readAllBytes(dev);
        return ("<h1>UI asset missing: " + name + "</h1>").getBytes(StandardCharsets.UTF_8);
    }

    // ---- low-level http helpers ---------------------------------------------

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        sendBytes(ex, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static String err(String msg) {
        return "{\"ok\":false,\"error\":\"" + esc(msg == null ? "error" : msg) + "\"}";
    }

    private static String esc(String s) { return Json.escape(s); }
}
