package geoplanph;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The RFID -> backend bridge as a start/stop-able service.
 *
 * The reader dials in as a TCP client, so this runs a TCP server: accept the
 * reader, parse frames into EPCs, and POST one transaction per tag presence.
 * Extracted from the old RfidBridge.main loop so the tray app and web UI can
 * start, stop and restart it on config changes without killing the process.
 */
public class BridgeService {

    private volatile Config cfg;

    private ServerSocket server;
    private Thread acceptThread;
    private ExecutorService posters;
    private ScheduledExecutorService sweeper;
    private BackendClient backend;
    private SessionTracker tracker;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> readerPeer = new AtomicReference<>(null);
    // Accepted reader sockets, so stop() can close them: closing only the
    // ServerSocket leaves the reader talking to an orphaned handler that still
    // parses frames under the pre-restart config, while the UI shows no reader.
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    // Identifies each accepted connection. The reader dials from a FIXED source
    // port, so peer strings repeat across reconnects and can't tell one
    // connection from the next -- a closing handler would clear the badge of the
    // live connection that replaced it.
    private final AtomicLong connSeq = new AtomicLong(0);
    private final AtomicLong currentConnId = new AtomicLong(-1);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private final AtomicLong transactionsOpened = new AtomicLong(0);
    private final AtomicLong postsOk = new AtomicLong(0);
    private final AtomicLong postsFailed = new AtomicLong(0);
    private volatile long startedAt = 0;

    public BridgeService(Config cfg) { this.cfg = cfg; }

    public boolean isRunning()          { return running.get(); }
    public Config config()              { return cfg; }
    public String readerPeer()          { return readerPeer.get(); }
    public String lastError()           { return lastError.get(); }
    public long transactionsOpened()    { return transactionsOpened.get(); }
    public long postsOk()               { return postsOk.get(); }
    public long postsFailed()           { return postsFailed.get(); }
    public long startedAt()             { return startedAt; }

    public synchronized void start() {
        if (running.get()) return;
        Config c = this.cfg;
        try {
            server = new ServerSocket(c.listenPort);
        } catch (Exception e) {
            lastError.set("Cannot listen on port " + c.listenPort + ": " + e.getMessage());
            RfidBridge.log("START FAILED: %s", lastError.get());
            return;
        }
        backend = new BackendClient(c.backendUrl, c.apiKey);
        posters = Executors.newCachedThreadPool();
        tracker = new SessionTracker(c.sessionGapMs, c.sessionMaxMs);
        lastError.set(null);
        running.set(true);
        startedAt = System.currentTimeMillis();

        RfidBridge.log("RFID bridge starting | listen=:%d  backend=%s  post=%s  apiKey=%s  format=%s  session.gap=%dms  session.max=%s",
                c.listenPort, c.backendUrl, c.postEnabled, c.apiKey.isEmpty() ? "unset" : "set",
                c.frameFormat, c.sessionGapMs, c.sessionMaxMs > 0 ? c.sessionMaxMs + "ms" : "off");

        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (SessionTracker.Session s : tracker.sweepExpired(now)) {
                RfidBridge.log("TRANSACTION CLOSED: %s  reads=%d  duration=%dms  (idle>=%dms)",
                        s.epc, s.reads(), s.durationMs(), c.sessionGapMs);
            }
        }, 1, 1, TimeUnit.SECONDS);

        acceptThread = new Thread(this::acceptLoop, "bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        RfidBridge.log("Listening for reader connections on port %d ...", c.listenPort);
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        // Drop the reader too, so it redials into the restarted listener and
        // picks up the new config. Closing the socket makes the handler's
        // blocking read() throw, which unwinds its thread.
        for (Socket s : liveSockets) {
            try { s.close(); } catch (Exception ignored) { }
        }
        liveSockets.clear();
        if (sweeper != null) sweeper.shutdownNow();
        if (posters != null) posters.shutdownNow();
        currentConnId.set(-1);
        readerPeer.set(null);
        RfidBridge.log("RFID bridge stopped.");
    }

    /** Apply a new config and restart the listener. */
    public synchronized void restart(Config newCfg) {
        this.cfg = newCfg;
        stop();
        start();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket sock;
            try {
                sock = server.accept();
            } catch (Exception e) {
                if (running.get()) RfidBridge.log("Accept error: %s", e.getMessage());
                break; // server closed on stop()
            }
            String peer = String.valueOf(sock.getRemoteSocketAddress());
            long connId = connSeq.incrementAndGet();
            liveSockets.add(sock);
            readerPeer.set(peer);
            currentConnId.set(connId);
            RfidBridge.log("Reader connected: %s", peer);
            Thread t = new Thread(() -> handle(sock, peer, connId), "reader-" + peer + "#" + connId);
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket sock, String peer, long connId) {
        Config c = this.cfg;
        FrameParser parser = new FrameParser(c.frameFormat);
        byte[] buf = new byte[4096];
        try (InputStream in = sock.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (!running.get()) break; // listener stopped/restarted under us
                if (n == 0) continue;
                if (c.logRaw) RfidBridge.log("RX %s  %s", peer, FrameParser.hex(buf, 0, n, true));
                for (FrameParser.TagRead tr : parser.feed(buf, n)) {
                    SessionTracker.Session opened =
                            tracker.onRead(tr.epc, tr.deviceNo, tr.antennaNo, System.currentTimeMillis());
                    if (opened == null) continue; // same presence -> stay quiet

                    transactionsOpened.incrementAndGet();
                    RfidBridge.log("TRANSACTION OPENED: %s   (dev=%d ant=%d)",
                            opened.epc, opened.deviceNo, opened.antennaNo);

                    if (c.postEnabled) {
                        final String epc = opened.epc;
                        posters.submit(() -> {
                            boolean ok = backend.sendRead(epc);
                            if (ok) postsOk.incrementAndGet(); else postsFailed.incrementAndGet();
                        });
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) RfidBridge.log("Reader %s read error: %s", peer, e.getMessage());
        } finally {
            liveSockets.remove(sock);
            try { sock.close(); } catch (Exception ignored) { }
            // Only clear the badge if THIS connection is still the current one.
            if (currentConnId.compareAndSet(connId, -1)) readerPeer.set(null);
            RfidBridge.log("Reader disconnected: %s", peer);
        }
    }

    private boolean isExpresswayTag(String epc) {
        Config c = this.cfg;
        if (!c.ignoreExpresswayTags || epc == null || epc.isEmpty()) return false;
        String u = epc.toUpperCase();
        for (String p : c.expresswayPrefixes.split(",")) {
            String prefix = p.trim().toUpperCase();
            if (!prefix.isEmpty() && u.startsWith(prefix)) return true;
        }
        return false;
    }
}
