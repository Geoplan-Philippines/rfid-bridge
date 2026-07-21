package geoplanph;

import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Arrays;

/**
 * Tray-resident entry point for the packaged app.
 *
 * Boots the bridge listener and the local dashboard web server, then parks a
 * system-tray icon so the app keeps running in the background after any window
 * is closed. All operator actions live on the tray menu and in the dashboard.
 */
public class TrayApp {

    private static final int UI_PORT_START = 20080;

    private static BridgeService service;
    private static WebServer web;
    private static TrayIcon trayIcon;

    public static void main(String[] args) {
        Log.init();
        boolean fromStartup = Arrays.asList(args).contains("--startup");

        Config cfg = ConfigStore.load();
        service = new BridgeService(cfg);

        int uiPort = findFreePort(UI_PORT_START);
        web = new WebServer(service, uiPort);
        try {
            web.start();
        } catch (Exception e) {
            Log.error("Dashboard failed to start: " + e.getMessage());
        }

        service.start();

        if (SystemTray.isSupported() && !java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                installTray();
                if (!fromStartup) openDashboard();      // open UI on manual launch only
                else trayIcon.displayMessage("RFID Bridge",
                        "Running in the background. Right-click the tray icon to open the dashboard.",
                        TrayIcon.MessageType.INFO);
            } catch (Exception e) {
                Log.error("Tray icon failed: " + e.getMessage());
                blockForever();
            }
        } else {
            Log.warn("System tray not available; running headless. Dashboard: " + web.url());
            blockForever();
        }
    }

    private static void installTray() throws Exception {
        SystemTray tray = SystemTray.getSystemTray();
        PopupMenu menu = new PopupMenu();

        MenuItem open = new MenuItem("Open Dashboard");
        open.addActionListener(e -> openDashboard());
        menu.add(open);
        menu.addSeparator();

        MenuItem start = new MenuItem("Start listener");
        start.addActionListener(e -> { service.start(); updateTooltip(); });
        MenuItem stop = new MenuItem("Stop listener");
        stop.addActionListener(e -> { service.stop(); updateTooltip(); });
        MenuItem restart = new MenuItem("Restart listener");
        restart.addActionListener(e -> { service.restart(ConfigStore.load()); updateTooltip(); });
        menu.add(start); menu.add(stop); menu.add(restart);
        menu.addSeparator();

        MenuItem logs = new MenuItem("Open logs folder");
        logs.addActionListener(e -> openLogs());
        menu.add(logs);

        CheckboxMenuItem startup = new CheckboxMenuItem("Run at Windows startup", Startup.isEnabled());
        startup.addItemListener(e -> Startup.setEnabled(startup.getState()));
        menu.add(startup);
        menu.addSeparator();

        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> shutdown());
        menu.add(quit);

        trayIcon = new TrayIcon(makeIcon(tray.getTrayIconSize().width), "RFID Bridge", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> openDashboard()); // double-click
        tray.add(trayIcon);
        updateTooltip();
    }

    private static void updateTooltip() {
        if (trayIcon == null) return;
        trayIcon.setToolTip("RFID Bridge — " + (service.isRunning()
                ? "listening on :" + service.config().listenPort
                : "stopped"));
    }

    private static void openDashboard() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(web.url()));
            }
        } catch (Exception e) {
            Log.warn("Could not open browser: " + e.getMessage());
        }
    }

    private static void openLogs() {
        try {
            java.nio.file.Files.createDirectories(Log.logsDir());
            new ProcessBuilder("explorer.exe", Log.logsDir().toString()).start();
        } catch (Exception e) {
            Log.warn("Could not open logs folder: " + e.getMessage());
        }
    }

    private static void shutdown() {
        try { service.stop(); } catch (Exception ignored) { }
        try { web.stop(); } catch (Exception ignored) { }
        try {
            if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        } catch (Exception ignored) { }
        Log.info("RFID Bridge exiting.");
        System.exit(0);
    }

    /** Draw a simple deep-blue rounded tile with a white "R" — matches the app identity. */
    private static Image makeIcon(int size) {
        if (size <= 0) size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int arc = Math.max(2, size / 6);
        g.setColor(new Color(0x0B, 0x4E, 0xA2));
        g.fillRoundRect(0, 0, size - 1, size - 1, arc, arc);
        g.setColor(Color.WHITE);
        int fs = (int) (size * 0.72);
        g.setFont(new Font("Segoe UI", Font.BOLD, fs));
        var fm = g.getFontMetrics();
        String s = "R";
        int tx = (size - fm.stringWidth(s)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(s, tx, ty);
        g.dispose();
        return img;
    }

    private static int findFreePort(int start) {
        for (int p = start; p < start + 40; p++) {
            try (ServerSocket s = new ServerSocket(p)) {
                return s.getLocalPort();
            } catch (Exception ignored) { }
        }
        return start;
    }

    private static void blockForever() {
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) { }
    }
}
