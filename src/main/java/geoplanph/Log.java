package geoplanph;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Central log sink. Every line the bridge produces fans out to three places:
 *   1. the console (so the CLI / packaged console still shows output),
 *   2. a rolling file under %LOCALAPPDATA%\RfidBridge\logs\bridge.log,
 *   3. an in-memory ring buffer the web UI polls (see {@link #since}).
 *
 * Kept deliberately dependency-free (JDK only) so it survives the jpackage
 * bundling with no extra libraries.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int RING_CAPACITY = 2000;          // lines kept for the UI
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024; // rotate at 5 MB

    public static final class Entry {
        public final long seq;
        public final String time;   // "HH:mm:ss.SSS"
        public final String level;  // INFO | WARN | ERROR
        public final String text;
        Entry(long seq, String time, String level, String text) {
            this.seq = seq; this.time = time; this.level = level; this.text = text;
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayList<Entry> RING = new ArrayList<>(RING_CAPACITY);
    private static long seq = 0;
    private static Path logFile;
    private static OutputStream fileOut;

    private Log() { }

    /** Resolve the app data dir; safe to call repeatedly. */
    public static Path appDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) base = System.getProperty("user.home", ".");
        return Path.of(base, "RfidBridge");
    }

    public static Path logsDir() {
        return appDir().resolve("logs");
    }

    /** Open the log file. Called once at startup; failures degrade to console-only. */
    public static synchronized void init() {
        if (fileOut != null) return;
        try {
            Path dir = logsDir();
            Files.createDirectories(dir);
            logFile = dir.resolve("bridge.log");
            rotateIfNeeded();
            fileOut = Files.newOutputStream(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("Log file init failed (console only): " + e.getMessage());
            fileOut = null;
        }
    }

    private static void rotateIfNeeded() {
        try {
            if (logFile != null && Files.exists(logFile) && Files.size(logFile) > MAX_FILE_BYTES) {
                Path bak = logsDir().resolve("bridge.prev.log");
                Files.deleteIfExists(bak);
                Files.move(logFile, bak);
            }
        } catch (IOException ignored) { }
    }

    /** Log a preformatted line (timestamp is added here). Level is auto-detected. */
    public static void line(String message) {
        write(detectLevel(message), message);
    }

    public static void info(String message)  { write("INFO", message); }
    public static void warn(String message)  { write("WARN", message); }
    public static void error(String message) { write("ERROR", message); }

    private static String detectLevel(String m) {
        String u = m.toUpperCase();
        if (u.contains("FAILED") || u.contains("ERROR") || u.contains(" -> 401")
                || u.contains(" -> 500") || u.contains("EXCEPTION")) return "ERROR";
        if (u.contains("DISCONNECT") || u.contains("WARN") || u.contains("RETRY")) return "WARN";
        return "INFO";
    }

    private static void write(String level, String message) {
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        String full = now.format(TS) + "  " + message;

        Entry e;
        synchronized (LOCK) {
            e = new Entry(++seq, time, level, message);
            RING.add(e);
            if (RING.size() > RING_CAPACITY) RING.remove(0);
        }

        System.out.println(full);
        appendToFile(full);
    }

    private static synchronized void appendToFile(String full) {
        if (fileOut == null) return;
        try {
            rotateIfNeeded();
            if (!Files.exists(logFile)) { // rotated/removed: reopen
                fileOut.close();
                fileOut = Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
            fileOut.write((full + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            fileOut.flush();
        } catch (IOException ignored) { }
    }

    /** Entries newer than {@code afterSeq}. Pass 0 for the whole buffer. */
    public static List<Entry> since(long afterSeq) {
        synchronized (LOCK) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : RING) if (e.seq > afterSeq) out.add(e);
            return out;
        }
    }

    public static long lastSeq() {
        synchronized (LOCK) { return seq; }
    }

    /** Clear the in-memory ring buffer and delete log files on disk. */
    public static synchronized void clear() {
        synchronized (LOCK) {
            RING.clear();
        }
        try {
            // Close current file handle
            if (fileOut != null) {
                fileOut.close();
                fileOut = null;
            }
            // Delete log files
            Files.deleteIfExists(logsDir().resolve("bridge.log"));
            Files.deleteIfExists(logsDir().resolve("bridge.prev.log"));
            // Reopen so new logs still get written to file
            logFile = logsDir().resolve("bridge.log");
            fileOut = Files.newOutputStream(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("Failed to clear log files: " + e.getMessage());
        }
    }
}
