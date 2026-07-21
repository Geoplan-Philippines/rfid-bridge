package geoplanph;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * "Run at Windows startup" toggle, backed by the per-user Run registry key
 * (HKCU\Software\Microsoft\Windows\CurrentVersion\Run). Per-user so it needs no
 * admin rights. Driven through reg.exe to avoid any native dependency.
 */
public final class Startup {

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "RfidBridge";

    private Startup() { }

    /** The command Windows should launch at login: the packaged exe, or a dev java call. */
    private static String launchCommand() {
        // jpackage app-image: this property points at the running .exe launcher.
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) return "\"" + appPath + "\" --startup";

        String exe = System.getProperty("app.exe");
        if (exe != null && !exe.isBlank()) return "\"" + exe + "\" --startup";

        // Dev fallback: relaunch this JVM on the tray main class.
        String javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String cp = System.getProperty("java.class.path", "out");
        return "\"" + javaw + "\" -cp \"" + cp + "\" geoplanph.TrayApp --startup";
    }

    public static boolean isEnabled() {
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", VALUE_NAME)
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            return p.exitValue() == 0 && out.contains(VALUE_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean setEnabled(boolean enabled) {
        try {
            ProcessBuilder pb = enabled
                    ? new ProcessBuilder("reg", "add", RUN_KEY, "/v", VALUE_NAME,
                            "/t", "REG_SZ", "/d", launchCommand(), "/f")
                    : new ProcessBuilder("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
            Process p = pb.redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            boolean ok = p.exitValue() == 0;
            if (ok) Log.info("Run at startup " + (enabled ? "enabled" : "disabled"));
            else    Log.warn("Startup toggle failed: " + out.trim());
            return ok;
        } catch (Exception e) {
            Log.warn("Startup toggle error: " + e.getMessage());
            return false;
        }
    }

    private static String readAll(Process p) throws Exception {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }
}
