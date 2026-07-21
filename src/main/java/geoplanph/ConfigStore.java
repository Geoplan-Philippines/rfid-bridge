package geoplanph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves, loads and saves {@code bridge.properties} for both the console run
 * and the packaged tray app.
 *
 * Location precedence:
 *   1. -Dbridge.config=&lt;path&gt;                (explicit override)
 *   2. ./bridge.properties in the working dir  (dev / run.bat)
 *   3. %LOCALAPPDATA%\RfidBridge\bridge.properties  (packaged; Program Files is read-only)
 *
 * Saving rewrites only the managed key values and preserves every comment,
 * because the file's inline docs are the operator's reference.
 */
public final class ConfigStore {

    /** Keys the UI/form manages. Order = order appended when a key is missing. */
    public static final String[] MANAGED = {
            "listen.port", "backend.url", "api.key", "post.enabled",
            "frame.format", "session.gap.ms", "session.max.ms", "log.raw"
    };

    private ConfigStore() { }

    private static Path resolved;

    public static synchronized Path path() {
        if (resolved != null) return resolved;
        String override = System.getProperty("bridge.config");
        if (override != null && !override.isBlank()) {
            resolved = Path.of(override);
            return resolved;
        }
        Path cwd = Path.of("bridge.properties");
        if (Files.exists(cwd)) {
            resolved = cwd.toAbsolutePath();
            return resolved;
        }
        resolved = Log.appDir().resolve("bridge.properties");
        seedIfMissing(resolved);
        return resolved;
    }

    private static void seedIfMissing(Path p) {
        if (Files.exists(p)) return;
        try {
            Files.createDirectories(p.getParent());
            byte[] template = readTemplate();
            Files.write(p, template);
            Log.info("Seeded default config at " + p);
        } catch (IOException e) {
            Log.error("Could not seed config at " + p + ": " + e.getMessage());
        }
    }

    private static byte[] readTemplate() throws IOException {
        try (InputStream in = ConfigStore.class.getResourceAsStream("/geoplanph/default-bridge.properties")) {
            if (in != null) return in.readAllBytes();
        }
        // Dev fallback: read from the source tree if the resource isn't on the classpath.
        Path devSrc = Path.of("src/main/resources/geoplanph/default-bridge.properties");
        if (Files.exists(devSrc)) return Files.readAllBytes(devSrc);
        return "# RFID bridge configuration\nlisten.port=20059\n".getBytes(StandardCharsets.UTF_8);
    }

    public static Config load() {
        return Config.load(path());
    }

    /** Current file contents as a key -> value map (managed keys only, for the form). */
    public static Map<String, String> currentValues() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path p = path();
        if (!Files.exists(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            String val = t.substring(eq + 1).trim();
            for (String m : MANAGED) if (m.equals(key)) out.put(key, val);
        }
        return out;
    }

    /**
     * Update the managed key values in place, preserving all comments and layout.
     * Keys not already present are appended at the end. Unmanaged keys are ignored.
     */
    public static synchronized void save(Map<String, String> updates) throws IOException {
        Path p = path();
        List<String> lines = Files.exists(p)
                ? new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8))
                : new ArrayList<>();

        Map<String, String> remaining = new LinkedHashMap<>();
        for (String key : MANAGED) {
            if (updates.containsKey(key)) remaining.put(key, updates.get(key));
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            if (remaining.containsKey(key)) {
                lines.set(i, key + "=" + remaining.remove(key));
            }
        }

        // Append any managed key that wasn't already in the file.
        if (!remaining.isEmpty()) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) lines.add("");
            for (Map.Entry<String, String> e : remaining.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
        }

        Files.createDirectories(p.getParent() == null ? Path.of(".") : p.getParent());
        Files.write(p, String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
        Log.info("Configuration saved to " + p);
    }
}
