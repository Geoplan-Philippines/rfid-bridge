package geoplanph;

import java.net.ServerSocket;

/**
 * Headless entry point for running the bridge as a background service
 * (systemd on Linux, or any process supervisor). No system tray, no AWT.
 *
 * Boots the bridge listener and the local dashboard web server, installs a
 * shutdown hook so a SIGTERM (what systemd sends on stop) closes the listener
 * and HTTP server cleanly, then parks the main thread.
 *
 * The dashboard binds to loopback only (see {@link WebServer}); reach it from a
 * remote machine with an SSH tunnel, e.g.:
 *     ssh -L 20080:localhost:20080 user@server
 */
public class Daemon {

    private static final int UI_PORT_START = 20080;

    public static void main(String[] args) {
        Log.init();
        Log.info("RFID Bridge daemon starting (headless).");

        Config cfg = ConfigStore.load();
        BridgeService service = new BridgeService(cfg);

        int uiPort = findFreePort(UI_PORT_START);
        WebServer web = new WebServer(service, uiPort);
        try {
            web.start();
        } catch (Exception e) {
            Log.error("Dashboard failed to start: " + e.getMessage());
        }

        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("RFID Bridge daemon stopping.");
            try { service.stop(); } catch (Exception ignored) { }
            try { web.stop(); } catch (Exception ignored) { }
        }, "shutdown"));

        // Park the main thread; the accept loop and HTTP server run on their own threads.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int findFreePort(int start) {
        for (int p = start; p < start + 40; p++) {
            try (ServerSocket s = new ServerSocket(p)) {
                return s.getLocalPort();
            } catch (Exception ignored) { }
        }
        return start;
    }
}
