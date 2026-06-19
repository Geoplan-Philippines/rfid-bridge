# RFID → Backend Bridge (Java)

Listens for the UHF reader's TCP connection, parses each tag frame into an EPC,
and POSTs it to the backend as `{"epcId":"<EPC>"}`.

The reader is configured as a **TCP Client** dialing out to `192.168.8.49:20059`,
so this bridge is the **TCP server** it connects to.

## One POST per tag presence (not per read)
The reader fires the *same* EPC many times per second while a tag sits in the field,
but the backend opens a **new transaction on every `/rfid-reads` POST**. To avoid a
flood of duplicate transactions, the bridge groups the stream into **presence sessions**:

- the **first** read of an EPC after silence opens a session → **one POST** (the rising edge),
- repeats of the same EPC while it's still in range are counted but **not** re-POSTed,
- after `session.gap.ms` with no reads the session **closes**; the same EPC reappearing
  later opens a fresh session → a fresh transaction.

Sessions are keyed per EPC, so a different tag (a new truck) opens its own transaction.
The log shows `TRANSACTION OPENED` on the POST and `TRANSACTION CLOSED reads=N duration=…ms`
when the tag leaves.

## Requirements
- JDK 17+ (tested on JDK 26). No external libraries, no Maven needed.

## Build
```
build.bat
```
(or: `javac -d out src/main/java/geoplanph/*.java`)

## Run
```
run.bat
```
Then trigger a read. The first sight of a tag prints:
```
TRANSACTION OPENED: AAAAAA000111   (dev=0 ant=1)
POST AAAAAA000111 -> 201  {"statusCode":201,...}
```
…and when the tag leaves the field:
```
TRANSACTION CLOSED: AAAAAA000111  reads=142  duration=8730ms  (idle>=5000ms)
```

## Test without hardware
In a second window:
```
run-sim.bat
```
A fake reader connects and pushes sample EPCs (incl. `AAAAAA000111`).

## Configuration — `bridge.properties`
| Key | Default | Meaning |
|---|---|---|
| `listen.port` | 20059 | Must equal the reader's Destination Port |
| `backend.url` | http://localhost:8000/api/v1/transactions/rfid-reads | POST target |
| `api.key` | *(empty)* | Device credential sent in `x-api-key`; create one via backend API keys admin endpoint |
| `post.enabled` | true | `false` = only print EPCs, no HTTP (use before backend is ready) |
| `frame.format` | AUTO_DETECT | `AUTO_VAR` \| `AUTO_FIXED12` \| `PROTOCOL_E0` — pin once confirmed from RX logs |
| `session.gap.ms` | 5000 | Quiet time (ms) with no reads before a tag's presence-session closes. Same EPC after this gap = new transaction. (Legacy alias: `dedup.window.ms`.) |
| `session.max.ms` | 0 | Safety cap (ms): force-close a session this long after it opened. `0` = disabled |
| `log.raw` | true | Log every raw byte chunk (commissioning aid) |

Override any key at launch: `java -Dpost.enabled=false -cp out geoplanph.RfidBridge`

## Connecting the real reader
1. This PC must hold IP **192.168.8.49** (the reader's Dest IP) — see ../SETUP.md.
2. Run `run.bat` (listening on :20059).
3. Power/trigger the reader; it connects in and tags start printing.
4. Confirm the `RX ...` bytes match the `AUTO_VAR` layout (`00 dev len EPC ant cs FF`).
   If different, set `frame.format` accordingly.

## Files
- `geoplanph/RfidBridge.java` — TCP server + main loop (opens transactions, sweeps closed sessions, POSTs)
- `geoplanph/SessionTracker.java` — groups the read stream into one session (one transaction) per tag presence
- `geoplanph/FrameParser.java` — streaming frame parser + checksum
- `geoplanph/BackendClient.java` — HTTP POST of `{"epcId":...}`
- `geoplanph/Config.java` — config loader
- `geoplanph/TagSimulator.java` — fake reader for testing
