package geoplanph;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming parser for the reader's tag frames. Handles partial / concatenated
 * TCP segments and resyncs (drops 1 byte) on any header/checksum mismatch.
 *
 * Supported formats (see Communication Protocol V4.2, section 5):
 *   AUTO_VAR     : 00 dev len EPC(len) ant cs FF
 *   AUTO_FIXED12 : 00 dev EPC(12)      ant cs FF
 *   PROTOCOL_E0  : E0 len 82 dev ant EPC cs        (82 = tag identification)
 *   AUTO_DETECT  : pick by leading byte, validate by checksum (commissioning default)
 *
 * Checksum rule everywhere: sum(bytes[0..csIndex-1]) + bytes[csIndex] == 0 (mod 256).
 */
public class FrameParser {

    public enum Format { AUTO_DETECT, AUTO_VAR, AUTO_FIXED12, PROTOCOL_E0 }

    public static final class TagRead {
        public final String epc;
        public final int deviceNo;
        public final int antennaNo;
        TagRead(String epc, int deviceNo, int antennaNo) {
            this.epc = epc; this.deviceNo = deviceNo; this.antennaNo = antennaNo;
        }
    }

    private final Format fmt;
    private byte[] buf = new byte[0];

    public FrameParser(Format fmt) { this.fmt = fmt; }

    /** Feed n bytes from the socket; return any complete, checksum-valid tag reads. */
    public List<TagRead> feed(byte[] chunk, int n) {
        byte[] nb = new byte[buf.length + n];
        System.arraycopy(buf, 0, nb, 0, buf.length);
        System.arraycopy(chunk, 0, nb, buf.length, n);
        buf = nb;

        List<TagRead> out = new ArrayList<>();
        while (buf.length > 0) {
            R r = tryOne(buf);
            if (r.status == S.INCOMPLETE) break;
            if (r.status == S.BAD) { consume(1); continue; }   // resync
            if (r.epc != null) out.add(new TagRead(r.epc, r.dev, r.ant));
            consume(r.total);
        }
        return out;
    }

    private void consume(int k) {
        byte[] nb = new byte[buf.length - k];
        System.arraycopy(buf, k, nb, 0, nb.length);
        buf = nb;
    }

    private enum S { OK, SKIP, INCOMPLETE, BAD }

    private static final class R {
        S status; int total; String epc; int dev, ant;
        static R incomplete() { R r = new R(); r.status = S.INCOMPLETE; return r; }
        static R bad()        { R r = new R(); r.status = S.BAD;        return r; }
        static R skip(int t)  { R r = new R(); r.status = S.SKIP; r.total = t; return r; }
        static R ok(int t, String epc, int dev, int ant) {
            R r = new R(); r.status = S.OK; r.total = t; r.epc = epc; r.dev = dev; r.ant = ant; return r;
        }
    }

    private R tryOne(byte[] b) {
        int u0 = b[0] & 0xff;
        switch (fmt) {
            case PROTOCOL_E0:  return parseE0(b);
            case AUTO_FIXED12: return parseFixed(b);
            case AUTO_VAR:     return parseVar(b);
            case AUTO_DETECT:
            default:
                if (u0 == 0xE0) return parseE0(b);
                if (u0 == 0x00) {
                    R rv = parseVar(b);
                    if (rv.status == S.OK || rv.status == S.SKIP) return rv;
                    R rf = parseFixed(b);
                    if (rf.status == S.OK) return rf;
                    if (rv.status == S.INCOMPLETE || rf.status == S.INCOMPLETE) return R.incomplete();
                    return R.bad();
                }
                return R.bad();
        }
    }

    // 00 dev len EPC(len) ant cs FF
    private R parseVar(byte[] b) {
        if (b.length < 3) return R.incomplete();
        if ((b[0] & 0xff) != 0x00) return R.bad();
        int len = b[2] & 0xff;
        if (len < 1 || len > 64) return R.bad();
        int total = 3 + len + 3;
        if (b.length < total) return R.incomplete();
        if ((b[total - 1] & 0xff) != 0xFF) return R.bad();
        if (!checksumOk(b, total - 2)) return R.bad();
        return R.ok(total, hex(b, 3, len), b[1] & 0xff, b[3 + len] & 0xff);
    }

    // 00 dev EPC(12) ant cs FF
    private R parseFixed(byte[] b) {
        if (b.length < 17) return R.incomplete();
        if ((b[0] & 0xff) != 0x00) return R.bad();
        if ((b[16] & 0xff) != 0xFF) return R.bad();
        if (!checksumOk(b, 15)) return R.bad();
        return R.ok(17, hex(b, 2, 12), b[1] & 0xff, b[14] & 0xff);
    }

    // E0 len 82 dev ant EPC cs
    private R parseE0(byte[] b) {
        if (b.length < 2) return R.incomplete();
        if ((b[0] & 0xff) != 0xE0) return R.bad();
        int total = (b[1] & 0xff) + 2;
        if (total < 6) return R.bad();
        if (b.length < total) return R.incomplete();
        if (!checksumOk(b, total - 1)) return R.bad();
        if ((b[2] & 0xff) != 0x82) return R.skip(total);   // valid frame, not a tag id
        int epcLen = total - 1 - 5;
        if (epcLen < 1) return R.bad();
        return R.ok(total, hex(b, 5, epcLen), b[3] & 0xff, b[4] & 0xff);
    }

    private static boolean checksumOk(byte[] b, int csIndex) {
        int sum = 0;
        for (int i = 0; i < csIndex; i++) sum += (b[i] & 0xff);
        return ((sum + (b[csIndex] & 0xff)) & 0xff) == 0;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) sb.append(String.format("%02X", b[off + i] & 0xff));
        return sb.toString();
    }

    public static String hex(byte[] b, int off, int len, boolean spaced) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0 && spaced) sb.append(' ');
            sb.append(String.format("%02X", b[off + i] & 0xff));
        }
        return sb.toString();
    }
}
