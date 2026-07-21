package geoplanph;

/**
 * RFID -> backend bridge (headless CLI entry point).
 *
 * The UHF reader is configured as a TCP *Client* that dials out to this machine
 * (Dest IP 192.168.8.49 : 20059). So the bridge runs a TCP *server*: it accepts
 * the reader's connection, parses each tag frame into an EPC, prints it, and
 * (optionally) POSTs it to the backend as {"epcId":"<EPC hex>"}.
 *
 * The listener logic now lives in {@link BridgeService} so the tray app
 * ({@link TrayApp}) and web UI can start/stop it. This class remains the
 * console entry point (run.bat) and the shared logging helper.
 */
public class RfidBridge {

    /** Formats {@code args} into {@code fmt} and fans the line out to every log sink. */
    public static void log(String fmt, Object... args) {
        Log.line(String.format(fmt, args));
    }

    public static void main(String[] args) throws Exception {
        Log.init();
        Config cfg = ConfigStore.load();
        BridgeService service = new BridgeService(cfg);
        service.start();
        // Keep the console process alive; the accept loop runs on a daemon thread.
        Thread.currentThread().join();
    }
}
