package geoplanph;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        log("RFID bridge starting | listen=:%d  backend=%s  post=%s  format=%s  dedup=%dms",
                cfg.listenPort, cfg.backendUrl, cfg.postEnabled, cfg.frameFormat, cfg.dedupWindowMs);

        BackendClient backend = new BackendClient(cfg.backendUrl);
        ExecutorService posters = Executors.newFixedThreadPool(4);
        ConcurrentHashMap<String, Long> lastSent = new ConcurrentHashMap<>();

        try (ServerSocket server = new ServerSocket(cfg.listenPort)) {
            log("Listening for reader connections on port %d ...", cfg.listenPort);
            while (true) {
                Socket sock = server.accept();
                String peer = String.valueOf(sock.getRemoteSocketAddress());
                log("Reader connected: %s", peer);
                Thread t = new Thread(() -> handle(sock, peer, cfg, backend, posters, lastSent),
                        "reader-" + peer);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void handle(Socket sock, String peer, Config cfg, BackendClient backend,
                               ExecutorService posters, ConcurrentHashMap<String, Long> lastSent) {
        FrameParser parser = new FrameParser(cfg.frameFormat);
        byte[] buf = new byte[4096];
        try (InputStream in = sock.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) continue;
                if (cfg.logRaw) log("RX %s  %s", peer, FrameParser.hex(buf, 0, n, true));
                for (FrameParser.TagRead tr : parser.feed(buf, n)) {
                    long now = System.currentTimeMillis();
                    Long prev = lastSent.get(tr.epc);
                    if (prev != null && now - prev < cfg.dedupWindowMs) {
                        continue; // same EPC seen within the dedup window -> skip
                    }
                    lastSent.put(tr.epc, now);

                    // >>> what you asked for: print every detected EPC <<<
                    log("EPC DETECTED: %s   (dev=%d ant=%d)", tr.epc, tr.deviceNo, tr.antennaNo);

                    if (cfg.postEnabled) {
                        final String epc = tr.epc;
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
