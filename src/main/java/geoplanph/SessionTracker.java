package geoplanph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Collapses the reader's continuous stream into one "session" per tag presence.
 *
 * The UHF reader fires the same EPC many times per second while a tag sits in
 * the field. The backend, however, opens a NEW transaction on every POST to
 * /rfid-reads. So we must POST exactly once per presence, not once per read.
 *
 * A session:
 *   - OPENS the first time an EPC is seen after silence (the "rising edge"),
 *   - stays open while that EPC keeps streaming in (reads are just counted),
 *   - CLOSES after {@code gapMs} with no further reads (the tag left), or after
 *     {@code maxMs} total if a cap is set.
 *
 * Only the rising edge maps to a transaction: the caller POSTs once when
 * {@link #onRead} returns a Session. After a session closes, the same EPC
 * reappearing opens a fresh session -> a fresh transaction.
 *
 * One tag is at the gate at a time, but sessions are keyed by EPC so brief
 * interleaving between two tags can't merge them or spawn spurious transactions.
 */
public class SessionTracker {

    public static final class Session {
        public final String epc;
        public final int deviceNo;
        public final int antennaNo;
        public final long openedAt;
        long lastSeen;
        long reads;

        Session(String epc, int deviceNo, int antennaNo, long now) {
            this.epc = epc;
            this.deviceNo = deviceNo;
            this.antennaNo = antennaNo;
            this.openedAt = now;
            this.lastSeen = now;
            this.reads = 1;
        }

        public long reads()      { return reads; }
        public long durationMs() { return lastSeen - openedAt; }
    }

    private final long gapMs;
    private final long maxMs; // 0 = no cap
    private final Map<String, Session> open = new HashMap<>();

    public SessionTracker(long gapMs, long maxMs) {
        this.gapMs = gapMs;
        this.maxMs = maxMs;
    }

    /**
     * Record one read. Returns the Session if this read OPENED a new presence
     * (rising edge -> caller should POST a new transaction); returns null if the
     * read just continues an already-open session (no POST).
     */
    public synchronized Session onRead(String epc, int deviceNo, int antennaNo, long now) {
        Session s = open.get(epc);
        if (s == null) {
            Session ns = new Session(epc, deviceNo, antennaNo, now);
            open.put(epc, ns);
            return ns; // rising edge -> new transaction
        }
        s.lastSeen = now;
        s.reads++;
        return null; // same presence -> stay quiet
    }

    /**
     * Remove and return sessions that have gone idle longer than gapMs (the tag
     * left the field) or exceeded maxMs total. Run periodically from a sweeper.
     * Returned sessions are detached from the map, so reading their fields is safe.
     */
    public synchronized List<Session> sweepExpired(long now) {
        List<Session> closed = new ArrayList<>();
        for (Iterator<Map.Entry<String, Session>> it = open.entrySet().iterator(); it.hasNext();) {
            Session s = it.next().getValue();
            boolean idle   = now - s.lastSeen >= gapMs;
            boolean tooOld = maxMs > 0 && now - s.openedAt >= maxMs;
            if (idle || tooOld) {
                it.remove();
                closed.add(s);
            }
        }
        return closed;
    }
}
