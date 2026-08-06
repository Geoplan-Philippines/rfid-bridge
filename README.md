# RFID → Backend Bridge (Java)

Listens for the UHF reader's TCP connection, parses each tag frame into an EPC,
and POSTs it to the backend as `{"epcId":"<EPC>"}`.

The reader is configured as a **TCP Client** dialing out to `192.168.8.49:20059`,
so this bridge is the **TCP server** it connects to.

## Desktop app (tray + dashboard) — recommended

The bridge ships as a background Windows app with a browser-based control panel
styled to match the RFID authorization client.

- **Build the `.exe`:** run `build-app.ps1` in PowerShell. It compiles, bundles a
  Java runtime, and produces a self-contained app at
  `dist\RfidBridge\RfidBridge.exe` (no Java needed on the target PC).
- **Run it:** launch `RfidBridge.exe`. It sits in the **system tray** and keeps
  running in the background even when the dashboard window is closed. Right-click
  the tray icon → **Open Dashboard**, or browse `http://localhost:20080/`.
- **Configure:** the dashboard edits every `bridge.properties` key with inline
  help, then saves and restarts the listener. Comments in the file are preserved.
- **Logs:** a live log viewer (filter by Info/Warn/Error) is built into the
  dashboard. Everything is also written to
  `%LOCALAPPDATA%\RfidBridge\logs\bridge.log` — tray menu → **Open logs folder**.
- **Run at Windows startup:** toggle in the dashboard (or the tray menu). Adds a
  per-user `HKCU\...\Run` entry; login launches stay silent (no browser popup).
- **Config location:** `bridge.properties` next to the app if present, otherwise
  `%LOCALAPPDATA%\RfidBridge\bridge.properties` (seeded on first run). The
  resolved path is shown in the dashboard header.

Dev shortcut (no packaging): `run-tray.bat` compiles and launches the tray app
from `.\out`.

## Linux (Ubuntu) — run headless as a systemd service

For a server/box with no desktop, the bridge runs as a background **systemd**
service (no tray, no display). The dashboard still runs, bound to loopback.

Everything lives in `linux/`:

- **Build the jar** (portable, runs on any JRE 17+ — you can even build it on
  Windows and copy it over): `bash linux/build-linux.sh` → `dist/rfid-bridge.jar`.
- **Install + start the service** (installs Java if missing, creates the
  `rfidbridge` user, deploys to `/opt/rfid-bridge`, enables on boot):
  ```
  sudo bash linux/install.sh
  ```
- **Configure:** edit `/opt/rfid-bridge/bridge.properties` (same keys as below),
  then `sudo systemctl restart rfid-bridge`. Your edits survive re-installs.
- **Status / logs:**
  ```
  systemctl status rfid-bridge
  journalctl -u rfid-bridge -f
  ```
  (also written to `/opt/rfid-bridge/RfidBridge/logs/bridge.log`)
- **Dashboard:** bound to loopback for safety. Reach it from your PC via an SSH
  tunnel, then open `http://localhost:20080/`:
  ```
  ssh -L 20080:localhost:20080 user@<server-ip>
  ```
- **Uninstall:** `sudo bash linux/uninstall.sh` (add `--purge` to also delete
  `/opt/rfid-bridge` and the service user).

The headless entry point is `geoplanph.Daemon` (bridge listener + dashboard +
clean SIGTERM shutdown, no AWT). Requirements: `openjdk-17-jre-headless` (the
installer adds it automatically).

Two things that look like faults on Linux but aren't:

- `Startup toggle error: Cannot run program "reg"` in the log — the dashboard's
  **Run at Windows startup** toggle shells out to the Windows registry and is
  meaningless here. Boot start is already handled by `systemctl enable`. Ignore it.
- The dashboard shows **No reader** until the reader actually dials in. The
  listener being *Running* only means the socket is open — see below.

### Network setup — the reader must be able to reach *and* answer

This is where commissioning actually goes wrong. The bridge is the **TCP server**;
the reader dials in. Two conditions must both hold:

1. **The host holds the IP the reader is configured to dial** (its Destination IP).
2. **The host has a route back to the reader's own subnet.** If the reader lives on
   a different subnet than the host address it dials, the SYN arrives but the
   SYN-ACK is sent to a default gateway that doesn't exist on an isolated device
   LAN — the handshake never completes and the dashboard sits on *No reader*
   forever, with no error logged. Give the NIC a second address inside the
   reader's subnet and the return path is direct.

Find the reader without knowing its IP — it answers ARP even when it can't
complete a connection:

```bash
ip neigh show dev <iface>          # a resolved lladdr = a live device on the wire
sudo tcpdump -ni <iface> 'tcp port 20059'   # SYNs with no reply = return-path problem
```

Assign the addresses with NetworkManager (survives reboot; `+ipv4.addresses`
*adds* rather than replaces). Clear the gateway if this NIC is an isolated device
LAN — a dead gateway only creates a bogus default route:

```bash
sudo nmcli con mod "<profile>" +ipv4.addresses <host-ip-in-reader-subnet>/24
sudo nmcli con mod "<profile>" ipv4.gateway ""
sudo nmcli con up  "<profile>"
```

Then open the reader's own web UI to confirm its **Destination IP/Port** matches
the host address and `listen.port`.

**Verify end to end:**

```bash
ss -ltn  | grep 20059     # LISTEN *:20059            -> listener up
ss -tn   | grep 20059     # ESTAB ...                 -> reader actually connected
journalctl -u rfid-bridge -f | grep -E 'Reader connected|TRANSACTION'
```

`Reader connected: /<ip>:<port>` in the log — and the reader's IP in the dashboard
badge — is the only real proof the path works.

#### Eagle Cement site values (as commissioned)

| | |
|---|---|
| Interface | `enxfc1928643580` (USB ethernet), NM profile `Wired connection 1` |
| Host addresses | `192.168.8.49/24` (the reader's Destination IP) **and** `192.168.10.50/24` (return path) |
| Gateway | cleared — isolated device LAN |
| Reader | `192.168.10.133`, dials `192.168.8.49:20059` |
| Config | `/opt/rfid-bridge/bridge.properties` |

The second address is the whole fix: the reader dials `192.168.8.49` but lives on
`192.168.10.0/24`, so without `192.168.10.50` on the NIC the replies had nowhere
to go. The original profile shipped with `ipv4.gateway=192.168.10.1` and no
address in that subnet, which is why it never worked.

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
1. This PC must hold the reader's Destination IP (**192.168.8.49** at Eagle Cement)
   *and* have a route back to the reader's own subnet — see
   [Network setup](#network-setup--the-reader-must-be-able-to-reach-and-answer)
   for why both are required and how to configure them.
2. Run `run.bat` (Windows) or start the service (Linux); it listens on :20059.
3. Power/trigger the reader; it connects in and tags start printing. The log line
   `Reader connected: /<ip>:<port>` confirms the handshake.
4. Confirm the `RX ...` bytes match the `AUTO_VAR` layout (`00 dev len EPC ant cs FF`).
   If different, set `frame.format` accordingly.

## Files
- `geoplanph/TrayApp.java` — desktop entry point: system-tray icon + menu, starts the dashboard and bridge
- `geoplanph/Daemon.java` — headless entry point (Linux/systemd): dashboard + bridge, clean SIGTERM shutdown, no tray
- `linux/` — `build-linux.sh` (portable jar), `install.sh`/`uninstall.sh`, and the `rfid-bridge.service` systemd unit
- `geoplanph/BridgeService.java` — the accept loop as a start/stop/restart service (opens transactions, sweeps closed sessions, POSTs)
- `geoplanph/RfidBridge.java` — headless CLI entry point + shared logging helper
- `geoplanph/WebServer.java` — embedded HTTP server: serves the dashboard and JSON API (JDK only, loopback)
- `geoplanph/webui/index.html` — the dashboard UI (config form + live logs), styled like the auth client
- `geoplanph/ConfigStore.java` — resolves/seeds `bridge.properties`, comment-preserving save
- `geoplanph/Log.java` — central log sink: console + rolling file + in-memory ring buffer for the UI
- `geoplanph/Startup.java` — "Run at Windows startup" toggle (per-user `Run` registry entry)
- `geoplanph/Json.java` — tiny JSON escape/parse for the API
- `geoplanph/IconGen.java` — build-time app-icon (`.ico`) generator
- `geoplanph/SessionTracker.java` — groups the read stream into one session (one transaction) per tag presence
- `geoplanph/FrameParser.java` — streaming frame parser + checksum
- `geoplanph/BackendClient.java` — HTTP POST of `{"epcId":...}`
- `geoplanph/Config.java` — config value loader (system prop > env > file > default)
- `geoplanph/TagSimulator.java` — fake reader for testing
- `build-app.ps1` — builds the self-contained `RfidBridge.exe`; `run-tray.bat` — dev launcher
