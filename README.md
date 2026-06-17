# RFID → Backend Bridge (Java)

Listens for the UHF reader's TCP connection, parses each tag frame into an EPC,
**prints the EPC**, and POSTs it to the backend as `{"epcId":"<EPC>"}`.

The reader is configured as a **TCP Client** dialing out to `192.168.8.49:20059`,
so this bridge is the **TCP server** it connects to.

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
Then trigger a read. Each tag prints:
```
EPC DETECTED: AAAAAA000111   (dev=0 ant=1)
POST AAAAAA000111 -> 201  {"statusCode":201,...}
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
| `post.enabled` | true | `false` = only print EPCs, no HTTP (use before backend is ready) |
| `frame.format` | AUTO_DETECT | `AUTO_VAR` \| `AUTO_FIXED12` \| `PROTOCOL_E0` — pin once confirmed from RX logs |
| `dedup.window.ms` | 3000 | Ignore the same EPC repeated within this window |
| `log.raw` | true | Log every raw byte chunk (commissioning aid) |

Override any key at launch: `java -Dpost.enabled=false -cp out geoplanph.RfidBridge`

## Connecting the real reader
1. This PC must hold IP **192.168.8.49** (the reader's Dest IP) — see ../SETUP.md.
2. Run `run.bat` (listening on :20059).
3. Power/trigger the reader; it connects in and tags start printing.
4. Confirm the `RX ...` bytes match the `AUTO_VAR` layout (`00 dev len EPC ant cs FF`).
   If different, set `frame.format` accordingly.

## Files
- `geoplanph/RfidBridge.java` — TCP server + main loop (prints EPC, dedups, POSTs)
- `geoplanph/FrameParser.java` — streaming frame parser + checksum
- `geoplanph/BackendClient.java` — HTTP POST of `{"epcId":...}`
- `geoplanph/Config.java` — config loader
- `geoplanph/TagSimulator.java` — fake reader for testing
