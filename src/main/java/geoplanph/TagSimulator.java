package geoplanph;

import java.io.OutputStream;
import java.net.Socket;

/**
 * Fake reader for testing the bridge without hardware.
 * Connects to the bridge (TCP client, like the real reader) and pushes
 * AUTO_VAR tag frames every 1.5s.
 *
 *   java -cp target/classes geoplanph.TagSimulator [host] [port]
 *   (default 127.0.0.1 20059)
 */
public class TagSimulator {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 20059;
        String[] epcs = {"AAAAAA000111", "E20010710000526F", "E3006019D26D1CE9AABBCCDD"};

        try (Socket s = connectWithRetry(host, port); OutputStream out = s.getOutputStream()) {
            System.out.println("Simulator connected to " + host + ":" + port);
            int i = 0;
            while (true) {
                byte[] frame = autoVarFrame(0x00, hexToBytes(epcs[i % epcs.length]), 0x01);
                out.write(frame);
                out.flush();
                System.out.println("sent " + FrameParser.hex(frame, 0, frame.length, true));
                i++;
                Thread.sleep(1500);
            }
        }
    }

    // Retry the connection so start order (bridge vs simulator) doesn't matter.
    static Socket connectWithRetry(String host, int port) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return new Socket(host, port);
            } catch (Exception e) {
                if (attempt >= 20) throw new RuntimeException("cannot connect to " + host + ":" + port, e);
                Thread.sleep(500);
            }
        }
    }

    // 00 dev len EPC ant cs FF  (cs = two's complement of the preceding bytes)
    static byte[] autoVarFrame(int dev, byte[] epc, int ant) {
        int total = 3 + epc.length + 3;
        byte[] f = new byte[total];
        f[0] = 0x00;
        f[1] = (byte) dev;
        f[2] = (byte) epc.length;
        System.arraycopy(epc, 0, f, 3, epc.length);
        f[3 + epc.length] = (byte) ant;
        int sum = 0;
        for (int i = 0; i < total - 2; i++) sum += (f[i] & 0xff);
        f[total - 2] = (byte) (((~sum) + 1) & 0xff);
        f[total - 1] = (byte) 0xFF;
        return f;
    }

    static byte[] hexToBytes(String h) {
        int n = h.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
