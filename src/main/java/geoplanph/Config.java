package geoplanph;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Config precedence: -Dsystem.property  >  ENV_VAR  >  bridge.properties  >  default.
 * ENV var name = key uppercased with '.' -> '_'  (e.g. listen.port -> LISTEN_PORT).
 */
public class Config {

    public int listenPort = 20059;
    public String backendUrl = "http://localhost:8000/api/v1/transactions/rfid-reads";
    // Shared device credential sent to the backend in the x-api-key header. Empty = no header.
    public String apiKey = "";
    public boolean postEnabled = true;
    public FrameParser.Format frameFormat = FrameParser.Format.AUTO_DETECT;
    // Quiet gap (ms) with no reads of an EPC before its presence-session closes.
    // One session = one /rfid-reads POST = one backend transaction.
    public long sessionGapMs = 5000;
    // Safety cap (ms): force-close a session this long after it opened. 0 = disabled.
    public long sessionMaxMs = 0;
    public boolean logRaw = true;

    public static Config load() {
        Config c = new Config();
        Properties p = new Properties();
        Path f = Path.of("bridge.properties");
        if (Files.exists(f)) {
            try (FileInputStream in = new FileInputStream(f.toFile())) {
                p.load(in);
            } catch (Exception e) {
                System.err.println("Failed to read bridge.properties: " + e.getMessage());
            }
        }
        String v;
        if ((v = get(p, "listen.port")) != null)     c.listenPort = (int) parseLong(v, c.listenPort);
        if ((v = get(p, "backend.url")) != null)      c.backendUrl = v.trim();
        if ((v = get(p, "api.key")) != null)          c.apiKey = v.trim();
        if ((v = get(p, "post.enabled")) != null)     c.postEnabled = Boolean.parseBoolean(v.trim());
        if ((v = get(p, "frame.format")) != null)     c.frameFormat = FrameParser.Format.valueOf(v.trim().toUpperCase());
        if ((v = get(p, "session.gap.ms")) != null)   c.sessionGapMs = parseLong(v, c.sessionGapMs);
        else if ((v = get(p, "dedup.window.ms")) != null) c.sessionGapMs = parseLong(v, c.sessionGapMs); // legacy alias
        if ((v = get(p, "session.max.ms")) != null)   c.sessionMaxMs = parseLong(v, c.sessionMaxMs);
        if ((v = get(p, "log.raw")) != null)          c.logRaw = Boolean.parseBoolean(v.trim());
        return c;
    }

    private static String get(Properties p, String key) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        String env = System.getenv(key.toUpperCase().replace('.', '_'));
        if (env != null) return env;
        return p.getProperty(key);
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }
}
