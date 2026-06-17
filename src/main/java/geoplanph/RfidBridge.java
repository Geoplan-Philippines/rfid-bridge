package geoplanph;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RFID -> backend bridge.
 *
 * The UHF reader is configured as a TCP *Client* that dials out to this machine
 * (Dest IP 192.168.8.49 : 20059). So this bridge runs a TCP *server*: it accepts
 * the reader's connection, parses each tag frame into an EPC, prints it, and
 * (optionally) POSTs it to the backend as {"epcId":"<EPC hex>"}.
 */
public class RfidBridge {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void log(String fmt, Object... args) {
        System.out.println(LocalDateTime.now().format(TS) + "  " + String.format(fmt, args));
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        log("RFID bridge starting | listen=:%d  backend=%s  post=%s  format=%s  session.gap=%dms  session.max=%s",
                cfg.listenPort, cfg.backendUrl, cfg.postEnabled, cfg.frameFormat, cfg.sessionGapMs,
                cfg.sessionMaxMs > 0 ? cfg.sessionMaxMs + "ms" : "off");

        BackendClient backend = new BackendClient(cfg.backendUrl);
        ExecutorService posters = Executors.newFixedThreadPool(4);
        SessionTracker tracker = new SessionTracker(cfg.sessionGapMs, cfg.sessionMaxMs);

        // Sweeper: close presence-sessions whose tag has gone quiet, so the same EPC
        // reappearing later starts a fresh transaction. Logs a summary per close.
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (SessionTracker.Session s : tracker.sweepExpired(now)) {
                log("TRANSACTION CLOSED: %s  reads=%d  duration=%dms  (idle>=%dms)",
                        s.epc, s.reads(), s.durationMs(), cfg.sessionGapMs);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try (ServerSocket server = new ServerSocket(cfg.listenPort)) {
            log("Listening for reader connections on port %d ...", cfg.listenPort);
            while (true) {
                Socket sock = server.accept();
                String peer = String.valueOf(sock.getRemoteSocketAddress());
                log("Reader connected: %s", peer);
                Thread t = new Thread(() -> handle(sock, peer, cfg, backend, posters, tracker),
                        "reader-" + peer);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void handle(Socket sock, String peer, Config cfg, BackendClient backend,
                               ExecutorService posters, SessionTracker tracker) {
        FrameParser parser = new FrameParser(cfg.frameFormat);
        byte[] buf = new byte[4096];
        try (InputStream in = sock.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) continue;
                if (cfg.logRaw) log("RX %s  %s", peer, FrameParser.hex(buf, 0, n, true));
                for (FrameParser.TagRead tr : parser.feed(buf, n)) {
                    // Only the FIRST read of a tag's presence opens a transaction. Repeats of the
                    // same EPC while it's still in the field are counted but never re-POSTed; the
                    // sweeper closes the session once the tag goes quiet (see main()).
                    SessionTracker.Session opened =
                            tracker.onRead(tr.epc, tr.deviceNo, tr.antennaNo, System.currentTimeMillis());
                    if (opened == null) continue; // same presence -> stay quiet

                    log("TRANSACTION OPENED: %s   (dev=%d ant=%d)", opened.epc, opened.deviceNo, opened.antennaNo);

                    if (cfg.postEnabled) {
                        final String epc = opened.epc;
                        posters.submit(() -> backend.sendRead(epc));
                    }
                }
            }
        } catch (Exception e) {
            log("Reader %s read error: %s", peer, e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
            log("Reader disconnected: %s", peer);
        }
    }
}
