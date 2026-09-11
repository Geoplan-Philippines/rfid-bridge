package geoplanph;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Run at startup" toggle.
 *
 * On Linux   — installs a systemd user service (~/.config/systemd/user/rfid-bridge.service).
 * On Windows — writes a value to the per-user Run registry key
 *              (HKCU\Software\Microsoft\Windows\CurrentVersion\Run).
 *
 * Both paths need no admin/root rights.
 */
public final class Startup {

    /* ---- Windows constants ---- */
    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "RfidBridge";

    /* ---- Linux constants ---- */
    private static final String SERVICE_NAME = "rfid-bridge.service";

    private Startup() { }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    // ---- public API (delegates by OS) ----------------------------------------

    public static boolean isEnabled() {
        return isLinux() ? linuxIsEnabled() : windowsIsEnabled();
    }

    public static boolean setEnabled(boolean enabled) {
        return isLinux() ? linuxSetEnabled(enabled) : windowsSetEnabled(enabled);
    }

    // ---- Linux: systemd user service -----------------------------------------

    private static Path serviceDir() {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome == null || configHome.isBlank())
            configHome = System.getProperty("user.home") + "/.config";
        return Path.of(configHome, "systemd", "user");
    }

    private static Path serviceFile() {
        return serviceDir().resolve(SERVICE_NAME);
    }

    /** Build the ExecStart command for the systemd unit. */
    private static String linuxExecStart() {
        // jpackage app-image: this property points at the running launcher binary.
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) return appPath + " --startup";

        String exe = System.getProperty("app.exe");
        if (exe != null && !exe.isBlank()) return exe + " --startup";

        // Dev fallback: relaunch this JVM on the tray main class.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path", "out");
        return java + " -cp " + cp + " geoplanph.TrayApp --startup";
    }

    private static String buildServiceUnit() {
        return """
                [Unit]
                Description=RFID Bridge – Reader to Backend gateway
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                ExecStart=%s
                Restart=on-failure
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(linuxExecStart());
    }

    private static boolean linuxIsEnabled() {
        try {
            Process p = new ProcessBuilder("systemctl", "--user", "is-enabled", SERVICE_NAME)
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            return out.trim().equals("enabled");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean linuxSetEnabled(boolean enabled) {
        try {
            if (enabled) {
                // Write / overwrite the unit file
                Files.createDirectories(serviceDir());
                Files.writeString(serviceFile(), buildServiceUnit(), StandardCharsets.UTF_8);

                // Reload so systemd picks up the new/changed file, then enable + start
                exec("systemctl", "--user", "daemon-reload");
                exec("systemctl", "--user", "enable", SERVICE_NAME);
                exec("systemctl", "--user", "start", SERVICE_NAME);
            } else {
                exec("systemctl", "--user", "stop", SERVICE_NAME);
                exec("systemctl", "--user", "disable", SERVICE_NAME);
                Files.deleteIfExists(serviceFile());
                exec("systemctl", "--user", "daemon-reload");
            }
            Log.info("Run at startup " + (enabled ? "enabled" : "disabled"));
            return true;
        } catch (Exception e) {
            Log.warn("Startup toggle error: " + e.getMessage());
            return false;
        }
    }

    /** Run a command and wait for it; returns exit code. */
    private static int exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        readAll(p);          // drain output so the process doesn't block
        p.waitFor();
        return p.exitValue();
    }

    // ---- Windows: registry ---------------------------------------------------

    /** The command Windows should launch at login: the packaged exe, or a dev java call. */
    private static String windowsLaunchCommand() {
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

    private static boolean windowsIsEnabled() {
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

    private static boolean windowsSetEnabled(boolean enabled) {
        try {
            ProcessBuilder pb = enabled
                    ? new ProcessBuilder("reg", "add", RUN_KEY, "/v", VALUE_NAME,
                            "/t", "REG_SZ", "/d", windowsLaunchCommand(), "/f")
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

    // ---- shared --------------------------------------------------------------

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
