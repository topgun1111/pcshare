#!/usr/bin/env python3
"""LANShare Text - the Windows side of LANShare text sharing, as its own app (pure Python 3.8+, standard library only; Tkinter for the window).

Extracted from pcprint.py (v26+ text inbox: /p/clip receiver, device beacons, send to phones, clipboard) and given the Android app's text viewer:
  * CodeHl.kt  -> `Hl`   (syntax highlighter: comments, strings, numbers, keywords, Types, calls, @meta, attributes, json keys, yaml / ini keys, html tags)
  * MdRender.kt -> `parse_blocks` (fenced code, headings, lists, quotes, tables, `code`, **bold**, *italic*, ~~strike~~, links)
  * InboxActivity.kt -> the window (From / To lines, "To: device" default target, Auto-copy switch, Paste / Send, tap-to-copy, long-press menu)

Look: a Claude-Code-style transcript. Every message has a Copy action, every code block has its own Copy button, line numbers and a language label;
the text is selectable; received text goes to the clipboard at once (Auto-copy, ON by default).

This app is completely separate from pcprint.py (own ports, own files, no shared state). It has NO size limits: any length of text, any number of texts.

Ports (TCP 48558 receiver, UDP 48559 this PC's own beacon; nothing here uses pcprint's 8799):
  * Phones find this PC three ways: (1) its UDP beacon (broadcast on every Wi-Fi / LAN interface, and unicast to every Tailscale peer, every device heard
    before and every address added with "Add device by IP"), (2) the phone probing GET :48558/ping, (3) the phone's own beacons (UDP 48555).
  * /ping?id=..&name=..&port=.. doubles as a handshake: the phone tells this PC who it is, so this PC can answer it (works over Tailscale, where broadcasts do not travel).
  * Phones POST their text to /p/clip (or /clip) on :48558; this PC POSTs to the phone's /p/clip.

Run:  pythonw lanshare_text.py          (window)         python lanshare_text.py --selftest   (no window, checks the logic)
Files: %APPDATA%\\LANShareText\\inbox.json (texts), text.json (settings), log.txt
"""
import ipaddress, json, os, queue, re, shutil, socket, subprocess, sys, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

VERSION = "2"
WIN = os.name == "nt"
PORT = 48558                 # TCP: this app's receiver (phones: GET /ping, POST /p/clip). NOT pcprint.py's 8799
TEXT_BEACON_PORT = 48559     # UDP: this PC's own beacon, sent to phones (the phone app listens here)
BEACON_PORT = 48555          # UDP: beacons of the phones (LANShare protocol)
LOCK_PORT = 48557            # single-instance lock (127.0.0.1 only)
APP = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "LANShareText") if WIN else os.path.expanduser("~/.lanshare_text")
TEXT_FILE = os.path.join(APP, "inbox.json")
TEXT_CFG = os.path.join(APP, "text.json")
LOG_FILE = os.path.join(APP, "log.txt")
DEVICE_TTL = 90              # seconds a device stays in the list after its last beacon / text

# ------------------------------------------------------------------ state
TEXTS = []                   # {"id","ts","out","peer","text"}, oldest first
TEXT_SET = {"autocopy": True, "popup": True, "topmost": False, "def_target": "", "geometry": "", "id": "", "hosts": []}   # autocopy: received text -> clipboard (also quiet clipboard-sync pushes); id: this PC's stable id; hosts: addresses added by hand (IP / MagicDNS name)
DEVICES = {}                 # id -> {"id","name","ip","port","seen"}
_TLOCK = threading.Lock()
_TNEXT = [1]
_TLOADED = [False]
STORE_VER = [0]              # bumped on every change: the window redraws when it moves
CLIPQ = queue.Queue()        # texts waiting to be put on the clipboard (done by the window thread)
EVENTQ = queue.Queue()       # ("text", entry) for the window: new text arrived
SERVER_ERR = [""]            # why the receiver could not start (port busy ...)
_LOGLOCK = threading.Lock()


def log(msg):
    try:
        with _LOGLOCK:
            os.makedirs(APP, exist_ok=True)
            if os.path.isfile(LOG_FILE) and os.path.getsize(LOG_FILE) > 200_000:
                os.replace(LOG_FILE, LOG_FILE + ".old")
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + str(msg) + "\n")
    except OSError:
        pass


def _atomic_json(path, obj):
    try:
        os.makedirs(APP, exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False)
        os.replace(tmp, path)
    except OSError as e:
        log(f"cannot save {os.path.basename(path)}: {e}")


def _text_load():
    if _TLOADED[0]:
        return
    _TLOADED[0] = True
    try:
        with open(TEXT_FILE, "r", encoding="utf-8") as f:
            for o in json.load(f):
                TEXTS.append({"id": int(o.get("id", _TNEXT[0])), "ts": float(o.get("ts", 0)), "out": bool(o.get("out")),
                              "peer": str(o.get("peer", "")), "text": str(o.get("text", ""))})
                _TNEXT[0] = max(_TNEXT[0], TEXTS[-1]["id"] + 1)
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    try:
        with open(TEXT_CFG, "r", encoding="utf-8") as f:
            c = json.load(f)
            for k, dv in TEXT_SET.items():
                if isinstance(c.get(k), type(dv)):
                    TEXT_SET[k] = c[k]
    except (OSError, ValueError, TypeError):
        pass


def text_add(out, peer, text):
    """Adds one inbox entry (out = sent from this PC). The same text twice within 2 s from the same device is kept once. No size / count limits. Returns (entry, is_new) or (None, False)."""
    if not text or not text.strip():
        return None, False
    with _TLOCK:
        _text_load()
        now = time.time()
        last = TEXTS[-1] if TEXTS else None
        if last and last["out"] == out and last["peer"] == peer and last["text"] == text and now - last["ts"] < 2:
            return last, False
        e = {"id": _TNEXT[0], "ts": now, "out": out, "peer": peer, "text": text}
        _TNEXT[0] += 1
        TEXTS.append(e)
        _atomic_json(TEXT_FILE, TEXTS)
        STORE_VER[0] += 1
        return e, True


def text_delete(tid=None):
    with _TLOCK:
        _text_load()
        if tid is None:
            TEXTS.clear()
        else:
            TEXTS[:] = [t for t in TEXTS if t["id"] != tid]
        _atomic_json(TEXT_FILE, TEXTS)
        STORE_VER[0] += 1


def text_list():
    with _TLOCK:
        _text_load()
        return [dict(t) for t in TEXTS]


def settings_get():
    with _TLOCK:
        _text_load()
        return dict(TEXT_SET)


def settings_set(**kw):
    with _TLOCK:
        _text_load()
        for k, v in kw.items():
            if k in TEXT_SET and isinstance(v, type(TEXT_SET[k])):
                TEXT_SET[k] = v
        _atomic_json(TEXT_CFG, TEXT_SET)
        return dict(TEXT_SET)


# ------------------------------------------------------------------ devices (phones heard on the network)
def device_seen(did, name, ip, port):
    if not did or not ip or not isinstance(port, int) or not 0 < port < 65536:
        return
    with _TLOCK:
        DEVICES[did] = {"id": did, "name": (name or ip)[:60], "ip": ip, "port": port, "seen": time.time()}


def devices_now():
    now = time.time()
    with _TLOCK:
        for k in [k for k, d in DEVICES.items() if now - d["seen"] > 600]:
            del DEVICES[k]
        return sorted((dict(d) for d in DEVICES.values() if now - d["seen"] <= DEVICE_TTL), key=lambda d: d["name"].lower())


def beacon_listener():
    """Hears the LANShare beacons (JSON over UDP, every ~2 s from every phone) so the window can offer the phones as targets. Best effort."""
    warned = False
    while True:
        s = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)   # lanshare.py / pcprint.py may listen on the same port on this PC
            s.bind(("", BEACON_PORT))
            s.settimeout(5)
            warned = False
            while True:
                try:
                    data, addr = s.recvfrom(4096)
                except socket.timeout:
                    continue
                try:
                    m = json.loads(data.decode("utf-8", "replace"))
                    if m.get("app") == "lanshare" and isinstance(m.get("id"), str) and isinstance(m.get("port"), int):
                        device_seen(m["id"], str(m.get("name") or ""), addr[0], m["port"])
                except (ValueError, AttributeError):
                    pass
        except Exception as e:
            if not warned:
                log(f"cannot listen for device beacons on UDP {BEACON_PORT} ({e}) - phones that sent text here can still be answered")
                warned = True
            time.sleep(30)
        finally:
            if s:
                try: s.close()
                except OSError: pass


# ------------------------------------------------------------------ this PC's own beacon (so phones find it on Wi-Fi AND over Tailscale)
def my_id():
    """Stable id of this PC (made once, kept in text.json), so a phone keeps it as the default device when the IP changes."""
    with _TLOCK:
        _text_load()
        if not TEXT_SET["id"]:
            TEXT_SET["id"] = uuid.uuid4().hex[:16]
            _atomic_json(TEXT_CFG, TEXT_SET)
        return TEXT_SET["id"]


def own_ips():
    """This PC's IPv4 addresses (Wi-Fi / LAN adapters), without loopback / link-local."""
    ips = set()
    try:
        for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(i[4][0])
    except OSError:
        pass
    for probe in ("8.8.8.8", "10.255.255.255", "192.168.255.255"):   # connect() on UDP sends nothing: it only asks the OS which address it would use
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect((probe, 9))
                ips.add(s.getsockname()[0])
            finally:
                s.close()
        except OSError:
            pass
    return sorted(i for i in ips if not i.startswith(("127.", "169.254.", "0.")))


def tailscale_exe():
    for c in (shutil.which("tailscale"), r"C:\Program Files\Tailscale\tailscale.exe", r"C:\Program Files (x86)\Tailscale\tailscale.exe",
              "/usr/bin/tailscale", "/usr/local/bin/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale"):
        if c and os.path.isfile(c):
            return c
    return None


def tailscale_peers():
    """(own tailscale IPv4 addresses, IPv4 addresses of the tailnet's other devices) from `tailscale status --json`; ([], []) when Tailscale is not installed / running."""
    exe = tailscale_exe()
    if not exe:
        return [], []
    try:
        r = subprocess.run([exe, "status", "--json"], capture_output=True, timeout=6, creationflags=0x08000000 if WIN else 0)
        o = json.loads(r.stdout.decode("utf-8", "replace"))
    except (OSError, ValueError, subprocess.SubprocessError):
        return [], []
    v4 = lambda l: [x for x in (l or []) if isinstance(x, str) and re.match(r"^\d+\.\d+\.\d+\.\d+$", x)]
    me = v4((o.get("Self") or {}).get("TailscaleIPs"))
    peers = []
    for p in (o.get("Peer") or {}).values():
        if isinstance(p, dict) and p.get("Online", True):
            peers += v4(p.get("TailscaleIPs"))
    return me, peers


def beacon_message(extra_ips=()):
    return json.dumps({"app": "lanshare-text", "id": my_id(), "name": socket.gethostname(), "port": PORT, "ips": own_ips() + list(extra_ips)}).encode("utf-8")


def beacon_targets_unicast(ts_peers):
    """Addresses that get the beacon one by one (broadcast does not cross Tailscale / other subnets): tailnet devices, every device heard before, hosts added by hand."""
    t = set(ts_peers)
    for d in devices_now():
        t.add(d["ip"])
    for h in settings_get().get("hosts", []):
        try:
            t.add(socket.gethostbyname(str(h).strip()))
        except OSError:
            pass
    t -= set(own_ips())
    t.discard("")
    return sorted(t)


def beacon_sender(stop=None):
    """Every 2 s: this PC's beacon, broadcast on every adapter and unicast to Tailscale peers / known / added devices. Phones answer with GET /ping?id=.. (the handshake)."""
    last_ts = 0.0
    ts_peers, ts_me = [], []
    warned = False
    while not (stop and stop.is_set()):
        try:
            if time.time() - last_ts > 30:   # `tailscale status` is slow-ish: not on every beat
                last_ts = time.time()
                ts_me, ts_peers = tailscale_peers()
            msg = beacon_message(ts_me)
            for ip in own_ips():
                for dst in ("255.255.255.255", ip.rsplit(".", 1)[0] + ".255"):
                    try:
                        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        try:
                            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                            s.bind((ip, 0))   # one packet per adapter: it must leave on that adapter
                            s.sendto(msg, (dst, TEXT_BEACON_PORT))
                        finally:
                            s.close()
                    except OSError:
                        pass
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                for dst in beacon_targets_unicast(ts_peers):
                    try:
                        u.sendto(msg, (dst, TEXT_BEACON_PORT))
                    except OSError:
                        pass
            finally:
                u.close()
            warned = False
        except Exception as e:
            if not warned:
                log(f"beacon sender: {e!r}")
                warned = True
        for _ in range(20):
            if stop and stop.is_set():
                return
            time.sleep(0.1)


def ensure_firewall():
    """Windows: allow the receiver (TCP 48558) and the beacons (UDP 48559 / 48555) from the LAN and Tailscale. Works when this program runs as administrator
    (right-click > Run as administrator once); otherwise Windows asks about python.exe the first time - answer Allow on private AND public networks."""
    if not WIN:
        return
    for name, proto, port in (("LANShareText-TCP", "TCP", PORT), ("LANShareText-Beacon", "UDP", TEXT_BEACON_PORT), ("LANShareText-PhoneBeacon", "UDP", BEACON_PORT)):
        try:
            if subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={name}"], capture_output=True, creationflags=0x08000000).returncode == 0:
                continue
            r = subprocess.run(["netsh", "advfirewall", "firewall", "add", "rule", f"name={name}", "dir=in", "action=allow", f"protocol={proto}", f"localport={port}",
                                "profile=any", "remoteip=LocalSubnet,100.64.0.0/10"], capture_output=True, creationflags=0x08000000)
            log(f"firewall rule {name}: " + ("added" if r.returncode == 0 else "not added (needs administrator)"))
        except OSError as e:
            log(f"firewall rule {name}: {e}")


# ------------------------------------------------------------------ receive (HTTP, the phones' /p/clip) and send
def text_received(text, peer, quiet=False, dev=None):
    """A phone sent [text]: store it and tell the window (it copies to the clipboard when Auto-copy is on and shows itself)."""
    if dev:
        device_seen(*dev)
    e, new = text_add(False, peer, text)
    if e is None:
        return
    if new:
        auto = settings_get()["autocopy"]
        if auto:
            CLIPQ.put(e["text"])
        EVENTQ.put(("text", dict(e), auto, quiet))
        log(f"text from {peer}: {len(e['text'])} characters" + (" (auto-copy)" if auto else ""))


def text_send(did, text):
    """Sends [text] to a device heard recently. Returns (ok, error message)."""
    if not text or not text.strip():
        return False, "nothing to send"
    with _TLOCK:
        d = dict(DEVICES.get(did) or {})
    if not d:
        return False, "that device is not on the network right now"
    import http.client
    body = json.dumps({"text": text, "from": socket.gethostname(), "id": my_id(), "port": PORT}).encode("utf-8")
    try:
        c = http.client.HTTPConnection(d["ip"], d["port"], timeout=15 + len(body) / 100_000)   # no size limit: the wait grows with the text (about 100 kB/s at the very least)
        try:
            c.request("POST", "/p/clip", body=body, headers={"Content-Type": "application/json"})
            r = c.getresponse()
            r.read()
        finally:
            c.close()
    except (OSError, http.client.HTTPException) as e:
        return False, f"{d['name']} did not answer ({e})"
    if r.status == 404:
        return False, f"{d['name']} runs an older LANShare - update it to receive text"
    if r.status != 200:
        return False, f"{d['name']} answered {r.status}"
    text_add(True, d["name"], text)
    log(f"text sent to {d['name']}: {len(text)} characters")
    return True, None


TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")


def lan_ok(addr):
    """LAN + Tailscale clients only, never the open internet."""
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        return ip.is_private or ip.is_loopback or ip.is_link_local or (ip.version == 4 and ip in TAILSCALE_V4)
    except ValueError:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "LANShareText/" + VERSION
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def reply(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if not lan_ok(self.client_address[0]):
            return self.reply(403, {"error": "local network only"})
        u = urlsplit(self.path)
        if u.path == "/ping":   # what the phone's PcText looks for. ?id=&name=&port= is the handshake: the phone says who it is, so this PC can answer it (also over Tailscale)
            q = {k: v[0] for k, v in parse_qs(u.query).items() if v}
            try:
                port = int(q.get("port", ""))
            except ValueError:
                port = None
            if q.get("id") and port:
                device_seen(q["id"][:80], q.get("name", "")[:60], self.client_address[0], port)
            return self.reply(200, {"ok": True, "id": my_id(), "name": socket.gethostname(), "version": VERSION, "app": "lanshare-text", "clip": True})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if not lan_ok(self.client_address[0]):
            return self.reply(403, {"error": "local network only"})
        if self.path.split("?")[0] not in ("/clip", "/p/clip"):
            return self.reply(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(400, {"error": "missing Content-Length"})
        parts = []   # no size limit
        got = 0
        while got < n:
            chunk = self.rfile.read(min(1 << 20, n - got))
            if not chunk:
                return self.reply(400, {"error": "short body"})
            parts.append(chunk)
            got += len(chunk)
        raw = b"".join(parts)
        try:
            o = json.loads(raw.decode("utf-8"))
        except ValueError:
            return self.reply(400, {"error": "bad JSON"})
        text = o.get("text") if isinstance(o, dict) else None
        if not isinstance(text, str) or not text.strip():
            return self.reply(400, {"error": "empty text"})
        peer = str(o.get("from") or self.client_address[0])[:60]
        port = o.get("port")
        dev = (str(o["id"]), peer, self.client_address[0], port) if isinstance(o.get("id"), str) and isinstance(port, int) else None
        text_received(text, peer, bool(o.get("quiet")), dev)
        self.reply(200, {"ok": True})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False   # on Windows SO_REUSEADDR would let a second program share the port silently


def start_server(port=None):
    """Starts the receiver in a thread. Returns the server, or None (and sets SERVER_ERR) when the port is taken."""
    try:
        srv = Server(("", port if port is not None else PORT), Handler)
    except OSError as e:
        SERVER_ERR[0] = f"Cannot receive: port {port if port is not None else PORT} is in use ({e.strerror or e}). Is another copy of LANShare Text (or another program) using it?"
        log(SERVER_ERR[0])
        return None
    SERVER_ERR[0] = ""
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ------------------------------------------------------------------ clipboard
def win_set_clipboard(text):
    """Plain Win32 clipboard write (CF_UNICODETEXT): the text stays on the clipboard after this program closes."""
    import ctypes
    k32, u32 = ctypes.windll.kernel32, ctypes.windll.user32
    k32.GlobalAlloc.restype = ctypes.c_void_p
    k32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
    k32.GlobalLock.restype = ctypes.c_void_p
    k32.GlobalLock.argtypes = [ctypes.c_void_p]
    k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    k32.GlobalFree.argtypes = [ctypes.c_void_p]
    u32.OpenClipboard.argtypes = [ctypes.c_void_p]
    u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
    u32.SetClipboardData.restype = ctypes.c_void_p
    data = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\0").encode("utf-16-le")
    for _ in range(20):
        if u32.OpenClipboard(None):
            break
        time.sleep(0.05)
    else:
        return False
    try:
        u32.EmptyClipboard()
        h = k32.GlobalAlloc(0x0002, len(data))   # GMEM_MOVEABLE
        if not h:
            return False
        p = k32.GlobalLock(h)
        ctypes.memmove(p, data, len(data))
        k32.GlobalUnlock(h)
        if not u32.SetClipboardData(13, h):      # CF_UNICODETEXT; the system owns the memory after success
            k32.GlobalFree(h)
            return False
        return True
    finally:
        u32.CloseClipboard()


def cli_set_clipboard(text):
    """Fallbacks: PowerShell on Windows, wl-copy / xclip / pbcopy elsewhere. True when it worked."""
    try:
        if WIN:
            r = subprocess.run(["powershell", "-NoProfile", "-STA", "-Command", "$t=[Console]::In.ReadToEnd(); Set-Clipboard -Value $t"],
                               input=text.encode("utf-8"), capture_output=True, timeout=20, creationflags=0x08000000)
            return r.returncode == 0
        for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"], ["pbcopy"]):
            if shutil.which(cmd[0]):
                return subprocess.run(cmd, input=text.encode("utf-8"), timeout=10, capture_output=True).returncode == 0
    except Exception as e:
        log(f"clipboard copy failed: {e}")
    return False


def copy_text(root, text):
    """Puts [text] on the clipboard (call from the window thread). Returns True when one of the methods worked."""
    if WIN:
        try:
            if win_set_clipboard(text):
                return True
        except Exception as e:
            log(f"win clipboard failed: {e}")
    try:
        root.clipboard_clear()
        root.clipboard_append(text)
        root.update()
        return True
    except Exception as e:
        log(f"tk clipboard failed: {e}")
    return cli_set_clipboard(text)


def link_of(t):
    m = re.search(r"https?://[^\s<>\"']+", t or "")
    return m.group(0).rstrip(".,;:!?)") if m else None


# ================================================================== syntax highlighter (port of CodeHl.kt)
COMMENT, STRING, NUMBER, KEYWORD, TYPE, FUNC, ATTR, META = range(8)
KIND_NAMES = ("comment", "string", "number", "keyword", "type", "func", "attr", "meta")
# the Android DARK palette (CodeHl.DARK): the viewer is dark
KIND_COLORS = ("#8B949E", "#7EE787", "#FFA657", "#FF7B72", "#79C0FF", "#D2A8FF", "#E3B341", "#FF9BCE")
MAX_TOKENS = 40_000


class _Lang:
    def __init__(self, kw=(), line=(), block=False, quotes="\"'", tags=False, json=False, keyline=False, md=False, triple=False,
                 dollar=False, php=False, ci=False, dash=False, hex=False):
        self.kw = frozenset(kw)
        self.line, self.block, self.quotes, self.tags, self.json, self.keyline = tuple(line), block, quotes, tags, json, keyline
        self.md, self.triple, self.dollar, self.php, self.ci, self.dash, self.hex = md, triple, dollar, php, ci, dash, hex


def _w(s):
    return s.split()


_JAVAISH = _w("package import class interface object fun val var if else when for while do return break continue in is as try catch finally throw null true false this super override open abstract final private public protected internal data sealed enum companion lateinit by init constructor suspend inline static void new extends implements instanceof int long double float boolean byte short char String throws synchronized volatile const typealias namespace using var async await readonly string bool foreach struct")
_JS = _w("var let const function return if else for while do switch case default break continue new delete typeof instanceof in of this class extends super import export from as async await yield try catch finally throw null undefined true false void static get set interface type enum implements public private protected readonly abstract declare namespace")
_PY = _w("def class return if elif else for while in not and or is import from as with try except finally raise pass break continue lambda yield None True False global nonlocal assert del async await self")
_PHP = _w("function class extends implements interface trait namespace use new return if elseif else foreach for while do switch case default break continue try catch finally throw public private protected static final abstract const var null true false array isset unset empty require require_once include include_once as instanceof global fn match and or xor echo print")
_SH = _w("if then else elif fi for while do done case esac in function return exit echo export local readonly set unset source alias cd")
_SQL = _w("select from where and or not insert into values update set delete create table alter drop index view join left right inner outer full on as group by order having limit offset union all distinct null is in like between exists case when then else end primary key foreign references default unique check constraint begin commit rollback true false asc desc")
_GO = _w("package import func var const type struct interface map chan go defer return if else for range switch case default break continue fallthrough select nil true false")
_RUST = _w("fn let mut pub struct enum impl trait use mod crate self Self super match if else for while loop return break continue as in ref move const static type where async await dyn unsafe true false")
_C = _w("if else for while do switch case default break continue return goto struct enum union typedef static const volatile extern sizeof void int char short long float double unsigned signed auto register inline class public private protected virtual new delete this namespace using template typename try catch throw true false null nullptr NULL bool")
_RUBY = _w("def class module end if elsif else unless while until for in do begin rescue ensure return yield self nil true false and or not require include extend attr_accessor puts then case when")
_LUA = _w("function end if then else elseif for while do repeat until return break local nil true false and or not in")
_DATA = _w("true false null yes no on off")

LANGS = {
    "java": _Lang(_JAVAISH, ("//",), block=True),
    "js": _Lang(_JS, ("//",), block=True, quotes="\"'`"),
    "py": _Lang(_PY, ("#",), triple=True),
    "php": _Lang(_PHP, ("//", "#"), block=True, dollar=True, php=True),
    "sh": _Lang(_SH, ("#",), dollar=True),
    "sql": _Lang(_SQL, ("--",), block=True, quotes="\"'`", ci=True),
    "go": _Lang(_GO, ("//",), block=True, quotes="\"'`"),
    "rust": _Lang(_RUST, ("//",), block=True, quotes="\""),
    "c": _Lang(_C, ("//",), block=True),
    "ruby": _Lang(_RUBY, ("#",)),
    "lua": _Lang(_LUA, ("--",)),
    "css": _Lang((), (), block=True, keyline=True, dash=True, hex=True),
    "json": _Lang(_DATA, (), quotes="\"", json=True),
    "yaml": _Lang(_DATA, ("#",), keyline=True),
    "ini": _Lang(_DATA, ("#", ";"), keyline=True),
    "html": _Lang(tags=True),
    "md": _Lang(md=True),
}
ALIAS = {}
for _k, _names in (("java", "java kt kts kotlin gradle cs csharp scala dart swift groovy"), ("js", "js mjs cjs jsx ts tsx javascript typescript"),
                   ("py", "py pyw python"), ("php", "php phtml"), ("sh", "sh bash zsh shell console bat cmd ps1 powershell"), ("sql", "sql"),
                   ("go", "go golang"), ("rust", "rs rust"), ("c", "c h cpp cc cxx hpp hh cplusplus"), ("ruby", "rb ruby"), ("lua", "lua"),
                   ("css", "css scss less"), ("json", "json jsonc json5 geojson"), ("yaml", "yml yaml"),
                   ("ini", "ini cfg conf properties toml env"), ("html", "html htm xhtml xml svg xaml plist vue"), ("md", "md markdown mdx")):
    for _n in _names.split():
        ALIAS[_n] = _k


class _Out:
    def __init__(self):
        self.a = []

    def add(self, s, e, k):
        if e > s:
            self.a.append((s, e, k))

    def full(self):
        return len(self.a) >= MAX_TOKENS


class Hl:
    @staticmethod
    def key(s):
        """Language key for a fence tag or a file extension, None = unknown."""
        return ALIAS.get((s or "").strip().lower())

    @staticmethod
    def scan(t, key):
        """[(start, end, kind)] of the coloured tokens only (plain text gets no entry); None for an unknown language."""
        l = LANGS.get(key)
        if l is None:
            return None
        out = _Out()
        if l.md:
            Hl._md(t, out)
        elif l.tags:
            Hl._tags(t, out)
        else:
            Hl._generic(t, l, out, 0)
        return out.a

    @staticmethod
    def _generic(t, l, out, off):
        n = len(t)
        i = 0
        at_line = True

        def ident_start(c):
            return c.isalpha() or c == "_" or (c == "$" and not l.dollar)

        def ident(c):
            return c.isalnum() or c == "_" or (c == "$" and not l.dollar) or (c == "-" and l.dash)

        while i < n and not out.full():
            c = t[i]
            if c == "\n":
                at_line = True
                i += 1
                continue
            if at_line:
                if c in " \t\r":
                    i += 1
                    continue
                at_line = False
                if l.keyline:
                    p = i
                    if c == "-" and p + 1 < n and t[p + 1] == " ":   # yaml list item
                        p += 2
                        while p < n and t[p] == " ":
                            p += 1
                    if p < n and t[p] == "[" and not l.dash:         # ini / toml [section]
                        e = t.find("]", p)
                        nl = t.find("\n", p)
                        if e > 0 and (nl < 0 or e < nl):
                            out.add(off + p, off + e + 1, META)
                            i = e + 1
                            continue
                    j = p
                    while j < n and (t[j].isalnum() or t[j] in "_.-"):
                        j += 1
                    if j > p:
                        k = j
                        while k < n and t[k] in " \t":
                            k += 1
                        if k < n and (t[k] == "=" or (t[k] == ":" and (k + 1 >= n or t[k + 1] in " \n\r"))):
                            out.add(off + p, off + j, ATTR)
                            i = j
                            continue
            # comments
            if l.block and c == "/" and i + 1 < n and t[i + 1] == "*":
                e = t.find("*/", i + 2)
                end = n if e < 0 else e + 2
                out.add(off + i, off + end, COMMENT)
                i = end
                continue
            if any(t.startswith(p, i) for p in l.line):
                e = t.find("\n", i)
                end = n if e < 0 else e
                out.add(off + i, off + end, COMMENT)
                i = end
                continue
            if l.php and (t.startswith("<?php", i) or t.startswith("<?=", i) or t.startswith("?>", i)):
                ln = 5 if t.startswith("<?php", i) else 3 if t.startswith("<?=", i) else 2
                out.add(off + i, off + i + ln, META)
                i += ln
                continue
            # strings
            if c in l.quotes:
                if l.triple and (t.startswith('"""', i) or t.startswith("'''", i)):
                    q = t[i:i + 3]
                    e = t.find(q, i + 3)
                    end = n if e < 0 else e + 3
                    out.add(off + i, off + end, STRING)
                    i = end
                    continue
                j = i + 1
                while j < n:
                    ch = t[j]
                    if ch == "\\":
                        j += 2
                        continue
                    if ch == c:
                        j += 1
                        break
                    if ch == "\n" and c != "`":
                        break
                    j += 1
                if j > n:
                    j = n
                kind = STRING
                if l.json:
                    k = j
                    while k < n and t[k] in " \t":
                        k += 1
                    if k < n and t[k] == ":":
                        kind = ATTR
                out.add(off + i, off + j, kind)
                i = j
                continue
            # numbers / colours
            if "0" <= c <= "9" and (i == 0 or not ident(t[i - 1])):
                j = i + 1
                while j < n and (t[j].isalnum() or t[j] in "._"):
                    j += 1
                out.add(off + i, off + j, NUMBER)
                i = j
                continue
            if l.hex and c == "#":
                j = i + 1
                while j < n and t[j].isalnum():
                    j += 1
                ln = j - i - 1
                if ln in (3, 4, 6, 8) and all(h in "0123456789abcdefABCDEF" for h in t[i + 1:j]):
                    out.add(off + i, off + j, NUMBER)
                    i = j
                    continue
                i += 1
                continue
            # @annotation / @at-rule
            if c == "@" and i + 1 < n and t[i + 1].isalpha():
                j = i + 1
                while j < n and (t[j].isalnum() or t[j] in "_." or (t[j] == "-" and l.dash)):
                    j += 1
                out.add(off + i, off + j, META)
                i = j
                continue
            # $variable
            if l.dollar and c == "$":
                j = i + 1
                if j < n and t[j] == "{":
                    e = t.find("}", j)
                    j = j if (e < 0 or e - i > 80) else e + 1
                else:
                    while j < n and (t[j].isalnum() or t[j] == "_"):
                        j += 1
                if j > i + 1:
                    out.add(off + i, off + j, ATTR)
                i = max(j, i + 1)
                continue
            # identifiers
            if ident_start(c):
                j = i + 1
                while j < n and ident(t[j]):
                    j += 1
                word = t[i:j]
                if (word.lower() if l.ci else word) in l.kw:
                    out.add(off + i, off + j, KEYWORD)
                elif c.isupper() and len(word) > 1:
                    out.add(off + i, off + j, TYPE)
                else:
                    k = j
                    while k < n and t[k] in " \t":
                        k += 1
                    if k < n and t[k] == "(":
                        out.add(off + i, off + j, FUNC)
                i = j
                continue
            i += 1

    @staticmethod
    def _tags(t, out):
        n = len(t)
        i = 0
        while i < n and not out.full():
            lt = t.find("<", i)
            if lt < 0:
                break
            i = lt
            if t.startswith("<!--", i):
                e = t.find("-->", i + 4)
                end = n if e < 0 else e + 3
                out.add(i, end, COMMENT)
                i = end
                continue
            j = i + 1
            if j < n and t[j] in "/!?":
                j += 1
            ns = j
            while j < n and (t[j].isalnum() or t[j] in ":-_."):
                j += 1
            if j == ns:   # a lone '<' (e.g. "a < b")
                i += 1
                continue
            name = t[ns:j].lower()
            closing = t[i + 1] == "/"
            out.add(i, j, KEYWORD)
            while j < n and t[j] not in "><":
                ch = t[j]
                if ch in "\"'":
                    e = t.find(ch, j + 1)
                    end = n if e < 0 else e + 1
                    out.add(j, end, STRING)
                    j = end
                elif ch.isalpha() or ch in "_:@":
                    s = j
                    while j < n and (t[j].isalnum() or t[j] in "-_:.@"):
                        j += 1
                    out.add(s, j, ATTR)
                else:
                    j += 1
            if j < n and t[j] == ">":
                out.add(j - 1 if (j > 0 and t[j - 1] == "/") else j, j + 1, KEYWORD)
                j += 1
                if not closing and name in ("script", "style"):   # embedded JS / CSS
                    e = t.lower().find("</" + name, j)
                    stop = n if e < 0 else e
                    if stop > j:
                        Hl._generic(t[j:stop], LANGS["js" if name == "script" else "css"], out, j)
                    j = stop
            i = max(j, i + 1)

    _MD_CODE = re.compile(r"`[^`\n]+`")

    @staticmethod
    def _md(t, out):
        pos, fence, n = 0, False, len(t)
        while pos <= n and not out.full():
            nl = t.find("\n", pos)
            end = n if nl < 0 else nl
            ln = t[pos:end]
            s = ln.lstrip()
            if s.startswith("```") or s.startswith("~~~"):
                out.add(pos, end, META)
                fence = not fence
            elif not fence:
                if s.startswith("#"):
                    out.add(pos, end, KEYWORD)
                elif s.startswith(">"):
                    out.add(pos, end, COMMENT)
                else:
                    for m in Hl._MD_CODE.finditer(ln):
                        out.add(pos + m.start(), pos + m.end(), STRING)
            if nl < 0:
                break
            pos = nl + 1


# ================================================================== message -> blocks (port of MdRender.kt, trimmed for chat text)
_INLINE = re.compile(
    r"`([^`\n]+)`"                                     # 1 code
    r"|\*\*(.+?)\*\*"                                  # 2 bold
    r"|__(.+?)__"                                      # 3 bold
    r"|~~(.+?)~~"                                      # 4 strike
    r"|\[([^\]\n]+)\]\(([^)\s]+)[^)]*\)"               # 5+6 [text](url)
    r"|(?<![\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])"  # 7 italic
    r"|(?<!\w)_(?!\s)([^_\n]+?)(?<!\s)_(?!\w)"         # 8 italic
    r"|(https?://[^\s<>\"']+)")                        # 9 bare link


def inline_runs(s, tags=()):
    """[(text, tags)] for one line of running text."""
    out, pos = [], 0
    for m in _INLINE.finditer(s):
        if m.start() > pos:
            out.append((s[pos:m.start()], tags))
        g = m.lastindex
        if g == 1:
            out.append((m.group(1), tags + ("icode",)))
        elif g in (2, 3):
            out.extend(inline_runs(m.group(g), tags + ("bold",)))
        elif g == 4:
            out.extend(inline_runs(m.group(4), tags + ("strike",)))
        elif g in (5, 6):
            out.append((m.group(5), tags + ("link:" + m.group(6),)))
        elif g in (7, 8):
            out.extend(inline_runs(m.group(g), tags + ("italic",)))
        else:
            u = m.group(9)
            trail = ""
            while u and u[-1] in ".,;:!?)":
                trail = u[-1] + trail
                u = u[:-1]
            out.append((u, tags + ("link:" + u,)))
            if trail:
                out.append((trail, tags))
        pos = m.end()
    if pos < len(s):
        out.append((s[pos:], tags))
    return out


_HEAD = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
_HR = re.compile(r"^\s*([-*_])(\s*\1){2,}\s*$")
_LIST = re.compile(r"^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$")
_TASK = re.compile(r"^\[( |x|X)\]\s+(.*)$")
_QUOTE = re.compile(r"^\s{0,3}(>\s?)+")
_TSEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")


def _cells(l):
    l = l.strip()
    if l.startswith("|"):
        l = l[1:]
    if l.endswith("|"):
        l = l[:-1]
    return [c.strip() for c in l.split("|")]


def _table_text(rows):
    w = [max(len(r[i]) if i < len(r) else 0 for r in rows) for i in range(max(len(r) for r in rows))]
    lines = []
    for k, r in enumerate(rows):
        lines.append("  ".join((r[i] if i < len(r) else "").ljust(w[i]) for i in range(len(w))).rstrip())
        if k == 0:
            lines.append("  ".join("─" * x for x in w))
    return "\n".join(lines)


def parse_blocks(src):
    """Markdown-ish text -> blocks. Each line of running text stays its own line (chat text keeps the sender's line breaks).
    Blocks: {"t":"code","lang","code"} {"t":"h","lvl","runs"} {"t":"li","ind","mark","runs"} {"t":"quote","lvl","runs"} {"t":"hr"} {"t":"p","runs"} {"t":"gap"}"""
    blocks = []
    lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    i, n = 0, len(lines)
    while i < n:
        ln = lines[i]
        s = ln.strip()
        if s.startswith("```") or s.startswith("~~~"):
            fc = s[0]
            m = re.match(r"^(`{3,}|~{3,})\s*([^\s`]*)", s)
            fence, lang = m.group(1), m.group(2)
            code = []
            i += 1
            while i < n and not (lines[i].strip().startswith(fence[:3]) and set(lines[i].strip()) <= {fc}):
                code.append(lines[i])
                i += 1
            blocks.append({"t": "code", "lang": lang, "code": "\n".join(code).rstrip("\n")})
            i += 1
            continue
        if not s:
            if blocks and blocks[-1]["t"] != "gap":
                blocks.append({"t": "gap"})
            i += 1
            continue
        if s.startswith("|") and i + 1 < n and _TSEP.match(lines[i + 1]) and "|" in lines[i + 1]:
            rows = [_cells(ln)]
            i += 2
            while i < n and lines[i].strip().startswith("|"):
                rows.append(_cells(lines[i]))
                i += 1
            blocks.append({"t": "code", "lang": "table", "code": _table_text(rows)})
            continue
        m = _HEAD.match(ln)
        if m:
            blocks.append({"t": "h", "lvl": len(m.group(1)), "runs": inline_runs(m.group(2))})
            i += 1
            continue
        if _HR.match(ln):
            blocks.append({"t": "hr"})
            i += 1
            continue
        m = _QUOTE.match(ln)
        if m:
            blocks.append({"t": "quote", "lvl": m.group(0).count(">"), "runs": inline_runs(ln[m.end():])})
            i += 1
            continue
        m = _LIST.match(ln)
        if m:
            body, mark = m.group(3), m.group(2)
            t = _TASK.match(body)
            if t:
                mark, body = ("☑" if t.group(1) != " " else "☐"), t.group(2)
            elif mark in "-*+":
                mark = "•"
            blocks.append({"t": "li", "ind": min(len(m.group(1).replace("\t", "    ")) // 2, 6), "mark": mark, "runs": inline_runs(body)})
            i += 1
            continue
        blocks.append({"t": "p", "runs": inline_runs(ln.rstrip())})
        i += 1
    while blocks and blocks[-1]["t"] == "gap":
        blocks.pop()
    return blocks


# ---- unfenced code: a phone usually sends a pasted snippet without ``` fences, so guess when the whole message is code
_CODE_START = re.compile(r"^\s*(def |class |import |from \S+ import |function |const |let |var |if\s*\(|for\s*\(|while\s*\(|return\b|public |private |protected |"
                         r"#include|#define|using |namespace |package |fun |val |@\w+|<\?php|SELECT |INSERT |UPDATE |DELETE |CREATE |async |await |try:|except|else:|elif |"
                         r"\$\w+\s*=|\w+\(.*\)\s*[{:;]?\s*$|[\w.\[\]]+\s*(=|:=|\+=|-=)\s*\S)")
_CODE_END = re.compile(r"[;{}]\s*$|\)\s*:?\s*$|:\s*$|,\s*$")
_SHELL_START = ("$ ", "pip ", "pip3 ", "python ", "python3 ", "py ", "git ", "npm ", "npx ", "yarn ", "cd ", "dir ", "ls ", "curl ", "wget ", "sudo ", "apt ", "apt-get ",
                "winget ", "docker ", "adb ", "ssh ", "scp ", "chmod ", "mkdir ", "gradle ", "./gradlew", "PS ", "C:\\", "kubectl ", "cargo ", "go ", "pwsh ", "powershell ")


def guess_lang(text):
    """Language key for text that is code but came without a fence, else None for prose."""
    s = text.strip()
    if not s or len(s) > 60_000:
        return None
    first = s.split("\n", 1)[0]
    if (s[0] in "{[" and s[-1] in "}]") and re.search(r'"\s*:|\[\s*[\d"{\[]', s):
        return "json"
    if re.match(r"^\s*<(!doctype|html|\?xml|svg|div|span|body|head|script|style|p|a|ul|table)\b", s, re.I) and s.endswith(">"):
        return "html"
    if "\n" not in s:
        if first.startswith(_SHELL_START) and not first.rstrip().endswith((".", "?", "!")):
            return "sh"
        return None
    rows = [l for l in s.split("\n") if l.strip()]
    if len(rows) < 2:
        return None
    score = 0
    for l in rows:
        codey = bool(_CODE_START.match(l)) or bool(_CODE_END.search(l)) or (l.startswith(("  ", "\t")) and re.search(r"[=(){}\[\]<>;]", l))
        prose = len(l.split()) >= 7 and l.rstrip().endswith((".", "?", "!")) and not re.search(r"[{};=]", l)
        score += 1 if codey and not prose else 0
    if score / len(rows) < 0.6:
        return None
    low = s.lower()
    if "<?php" in low or re.search(r"\$\w+\s*=", s) and ";" in s:
        return "php"
    if re.search(r"^\s*(def |class \w+.*:|import \w+|from \w+ import|elif |print\()", s, re.M) and "{" not in s[:200]:
        return "py"
    if re.search(r"^\s*(select|insert into|update|delete from|create table)\b", low, re.M):
        return "sql"
    if re.search(r"#include|std::|::|->|\bnullptr\b", s):
        return "c"
    if re.search(r"\bfun \w+|\bval \w+|\bvar \w+:|\bclass \w+\(|import (android|java|kotlin)", s):
        return "java"
    if re.search(r"\b(function|const|let|=>|console\.|document\.|require\()", s):
        return "js"
    if re.search(r"^\s*[\w.-]+\s*:\s*\S", s, re.M) and "{" not in s and ";" not in s:
        return "yaml"
    if re.search(r"^\s*[#.]?[\w-]+\s*\{", s, re.M) and re.search(r"[\w-]+\s*:\s*[^;]+;", s):
        return "css"
    return "js" if "{" in s or ";" in s else "py"


# ================================================================== the window (port of InboxActivity.kt, dressed like a Claude Code transcript)
BG, PANEL, PANEL2, BORDER = "#171615", "#201f1d", "#2a2826", "#35322e"
FG, DIM, ACC, GREEN, RED, BLUE, OUTC = "#e8e4dc", "#8d877d", "#d97757", "#7ee787", "#ff7b72", "#6cb6ff", "#8ab4f8"
CODEBG, ICODEBG = "#1d1c1a", "#2d2b28"
CAP_LINES = 30          # code blocks longer than this start collapsed ("Show all N lines")
RENDER_LIMIT = 80       # newest messages drawn at once; "Show older" adds more


def stamp(ts):
    t = time.localtime(ts)
    return time.strftime("%H:%M", t) if time.strftime("%Y%m%d", t) == time.strftime("%Y%m%d") else time.strftime("%d.%m. %H:%M", t)


def run_window(port=None, serve=True):
    import tkinter as tk
    from tkinter import font as tkfont
    import bisect, webbrowser

    if WIN:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)   # crisp text on high-DPI screens
        except Exception:
            pass
    root = tk.Tk()
    root.title("LANShare Text")
    root.configure(bg=BG)
    root.minsize(460, 420)
    scale = max(1.0, root.winfo_fpixels("1i") / 96.0)

    def S(v):
        return int(v * scale)

    fams = set(tkfont.families(root))
    pick = lambda opts: next((f for f in opts if f in fams), opts[-1])
    CODE_FAM = pick(["Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Courier New", "TkFixedFont"])
    BODY_FAM = pick(["Segoe UI", "Helvetica", "DejaVu Sans", "TkDefaultFont"])
    F = {
        "body": tkfont.Font(root, family=BODY_FAM, size=10),
        "bold": tkfont.Font(root, family=BODY_FAM, size=10, weight="bold"),
        "italic": tkfont.Font(root, family=BODY_FAM, size=10, slant="italic"),
        "small": tkfont.Font(root, family=BODY_FAM, size=9),
        "smallb": tkfont.Font(root, family=BODY_FAM, size=9, weight="bold"),
        "code": tkfont.Font(root, family=CODE_FAM, size=10),
        "codesmall": tkfont.Font(root, family=CODE_FAM, size=9),
        "h1": tkfont.Font(root, family=BODY_FAM, size=15, weight="bold"),
        "h2": tkfont.Font(root, family=BODY_FAM, size=13, weight="bold"),
        "h3": tkfont.Font(root, family=BODY_FAM, size=11, weight="bold"),
        "title": tkfont.Font(root, family=BODY_FAM, size=12, weight="bold"),
        "gap": tkfont.Font(root, family=BODY_FAM, size=4),
    }
    cw = F["code"].measure("0")

    st = {"ver": -1, "rendered": [], "limit": RENDER_LIMIT, "modes": {}, "links": {}, "n_link": 0, "frames": [], "fit_job": None, "toast_job": None, "next_dev": 0.0}
    cfg = settings_get()
    resq = queue.Queue()      # callbacks from worker threads, run by tick() on the window thread

    # ---- top bar
    top = tk.Frame(root, bg=BG)
    top.pack(fill="x", padx=S(14), pady=(S(10), S(4)))
    tk.Label(top, text="●", fg=ACC, bg=BG, font=F["title"]).pack(side="left")
    tk.Label(top, text=" LANShare Text", fg=FG, bg=BG, font=F["title"]).pack(side="left")
    info = tk.Label(top, text="", fg=DIM, bg=BG, font=F["small"], anchor="w")
    info.pack(side="left", padx=S(12))
    menu_btn = tk.Label(top, text="⋮", fg=FG, bg=BG, font=F["title"], cursor="hand2", padx=S(8))
    menu_btn.pack(side="right")

    to_line = tk.Label(root, text="", fg=DIM, bg=PANEL, font=F["body"], anchor="w", cursor="hand2", padx=S(14), pady=S(8))
    to_line.pack(fill="x")

    # ---- transcript
    mid_f = tk.Frame(root, bg=BG)
    mid_f.pack(fill="both", expand=True)
    T = tk.Text(mid_f, bg=BG, fg=FG, relief="flat", bd=0, highlightthickness=0, wrap="word", font=F["body"], padx=S(14), pady=S(6),
                cursor="arrow", insertwidth=0, state="disabled", spacing1=S(1), spacing3=S(2), selectbackground="#3d4a63", selectforeground="#ffffff",
                inactiveselectbackground="#3d4a63", exportselection=False, undo=False)
    sb = tk.Scrollbar(mid_f, command=T.yview, troughcolor=BG, bd=0)
    T.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    T.pack(side="left", fill="both", expand=True)

    T.tag_configure("hdr_in", foreground=ACC, font=F["smallb"], spacing1=S(16))
    T.tag_configure("hdr_out", foreground=OUTC, font=F["smallb"], spacing1=S(16))
    T.tag_configure("time", foreground=DIM, font=F["small"])
    T.tag_configure("act", foreground=DIM, font=F["small"])
    T.tag_configure("body", lmargin1=S(22), lmargin2=S(22))
    T.tag_configure("outb", foreground="#cfd8ea")
    for k, ft in (("h1", "h1"), ("h2", "h2"), ("h3", "h3")):
        T.tag_configure(k, font=F[ft], foreground="#ffffff", spacing1=S(6), spacing3=S(2))
    T.tag_configure("bold", font=F["bold"])
    T.tag_configure("italic", font=F["italic"])
    T.tag_configure("strike", overstrike=True, foreground=DIM)
    T.tag_configure("icode", font=F["code"], background=ICODEBG, foreground="#f0c27b")
    T.tag_configure("link", foreground=BLUE, underline=True)
    T.tag_configure("quote", foreground=DIM, lmargin1=S(34), lmargin2=S(34))
    T.tag_configure("quotebar", foreground=BORDER)
    T.tag_configure("hr", foreground=BORDER, lmargin1=S(22), lmargin2=S(22))
    T.tag_configure("gap", font=F["gap"])
    T.tag_configure("hint", foreground=DIM, justify="center", spacing1=S(60), font=F["body"])
    T.tag_configure("older", foreground=BLUE, justify="center", spacing1=S(6), spacing3=S(6), font=F["small"])
    for ind in range(7):
        T.tag_configure(f"li{ind}", lmargin1=S(22 + ind * 20), lmargin2=S(42 + ind * 20), tabs=(S(42 + ind * 20),))
    T.tag_raise("sel")

    # ---- input
    tk.Frame(root, bg=BORDER, height=1).pack(fill="x")
    inrow = tk.Frame(root, bg=BG)
    inrow.pack(fill="x", padx=S(14), pady=(S(8), S(4)))
    tk.Label(inrow, text="❯", fg=ACC, bg=BG, font=F["code"], anchor="n").pack(side="left", anchor="n", pady=S(2))
    inp = tk.Text(inrow, bg=BG, fg=FG, insertbackground=FG, relief="flat", bd=0, highlightthickness=0, wrap="word", height=1, font=F["code"],
                  padx=S(8), pady=S(2), undo=True, selectbackground="#3d4a63", selectforeground="#ffffff")
    btns = tk.Frame(inrow, bg=BG)
    btns.pack(side="right", anchor="n", padx=(S(8), 0))   # packed before the box: the buttons keep their room when the window is narrow
    inp.pack(side="left", fill="x", expand=True)

    def mkbtn(parent, text, cmd, fg=FG, bg=PANEL2):
        b = tk.Label(parent, text=text, fg=fg, bg=bg, font=F["small"], cursor="hand2", padx=S(10), pady=S(4))
        b.bind("<Button-1>", lambda e: cmd())
        b.bind("<Enter>", lambda e: b.configure(bg=BORDER))
        b.bind("<Leave>", lambda e: b.configure(bg=bg))
        return b
    mkbtn(btns, "Paste", lambda: paste()).pack(side="left", padx=(0, S(6)))
    mkbtn(btns, "Send ↵", lambda: send(), fg="#10151f", bg=ACC).pack(side="left")
    toast = tk.Label(root, text="", fg=DIM, bg=BG, font=F["small"], anchor="w", padx=S(14))
    toast.pack(fill="x", pady=(0, S(6)))

    def say(msg, color=DIM, ms=3500):
        toast.configure(text=msg, fg=color)
        if st["toast_job"]:
            root.after_cancel(st["toast_job"])
        st["toast_job"] = root.after(ms, lambda: toast.configure(text=""))

    def post(fn):
        resq.put(fn)

    # ---- helpers
    def item_by_id(mid):
        return next((e for e in text_list() if e["id"] == mid), None)

    def copy_raw(text, what="Copied"):
        ok = copy_text(root, text)
        say(what if ok else "Could not reach the clipboard", GREEN if ok else RED, 2000)
        return ok

    def flash_tag(tag, ms=900):
        try:
            T.tag_configure(tag, foreground=GREEN)
            root.after(ms, lambda: T.tag_configure(tag, foreground=DIM))
        except tk.TclError:
            pass

    def copy_message(mid):
        e = item_by_id(mid)
        if e:
            if copy_raw(e["text"], "Message copied"):
                flash_tag(f"cp{mid}")

    def open_link(url):
        try:
            webbrowser.open(url)
        except Exception:
            say("Cannot open the link", RED)

    def forward_wheel(ev):
        n = getattr(ev, "num", 0)
        d = -3 if n == 4 else 3 if n == 5 else (-int(ev.delta / 120) * 3 if ev.delta else 0)
        T.yview_scroll(d, "units")
        return "break"

    # ---- code block card
    def code_block(mid, lang, code):
        f = tk.Frame(T, bg=BORDER)
        inner = tk.Frame(f, bg=CODEBG)
        inner.pack(fill="both", expand=True, padx=1, pady=1)
        head = tk.Frame(inner, bg=PANEL2)
        head.pack(fill="x")
        lines = code.split("\n")
        n = len(lines)
        key = Hl.key(lang) if lang not in ("", "table", "text") else None
        toks = Hl.scan(code, key) if key and len(code) <= 60_000 else None
        tk.Label(head, text=(lang or "text"), fg=DIM, bg=PANEL2, font=F["smallb"], padx=S(10), pady=S(3)).pack(side="left")
        cpb = tk.Label(head, text="⧉ Copy", fg=DIM, bg=PANEL2, font=F["small"], cursor="hand2", padx=S(10))
        cpb.pack(side="right")
        exp = {"on": n <= CAP_LINES}
        tgl = None
        if n > CAP_LINES:
            tgl = tk.Label(head, text="", fg=BLUE, bg=PANEL2, font=F["small"], cursor="hand2", padx=S(6))
            tgl.pack(side="right")
        body = tk.Frame(inner, bg=CODEBG)
        body.pack(fill="both", expand=True)
        numbered = lang != "table" and n > 1
        gut = None
        if numbered:
            gut = tk.Text(body, width=len(str(n)) + 1, bg=PANEL, fg="#5c5750", relief="flat", bd=0, highlightthickness=0, font=F["code"], padx=S(6),
                          pady=S(4), wrap="none", cursor="arrow", takefocus=0, state="disabled", selectbackground=PANEL, selectforeground="#5c5750")
            gut.tag_configure("r", justify="right")
            gut.pack(side="left", fill="y")
        ct = tk.Text(body, width=60, height=1, bg=CODEBG, fg=FG, relief="flat", bd=0, highlightthickness=0, font=F["code"], padx=S(8), pady=S(4), wrap="none",
                     cursor="arrow", insertwidth=0, state="disabled", selectbackground="#3d4a63", selectforeground="#ffffff", exportselection=False, undo=False)
        ct.pack(side="left", fill="both", expand=True)
        for k, col in enumerate(KIND_COLORS):
            ct.tag_configure(f"k{k}", foreground=col)
        hb = tk.Scrollbar(inner, orient="horizontal", command=ct.xview, bd=0, troughcolor=CODEBG)
        ct.configure(xscrollcommand=hb.set)
        starts = [0]
        for ln in lines:
            starts.append(starts[-1] + len(ln) + 1)

        cur = {"lines": lines}

        def pos(o):
            li = bisect.bisect_right(starts, o) - 1
            return f"{li + 1}.{o - starts[li]}"

        def fill():
            shown = lines if exp["on"] else lines[:CAP_LINES]
            cur["lines"] = shown
            txt = "\n".join(shown)
            ct.configure(state="normal")
            ct.delete("1.0", "end")
            ct.insert("1.0", txt)
            if toks:
                lim = len(txt)
                for s, e, k in toks:
                    if s >= lim:
                        break
                    ct.tag_add(f"k{k}", pos(s), pos(min(e, lim)))
            ct.configure(state="disabled", height=len(shown))
            if gut is not None:
                gut.configure(state="normal")
                gut.delete("1.0", "end")
                gut.insert("1.0", "\n".join(str(i + 1) for i in range(len(shown))), ("r",))
                gut.configure(state="disabled", height=len(shown))
            if tgl is not None:
                tgl.configure(text="▴ Collapse" if exp["on"] else f"▾ Show all {n} lines")

        def toggle(_e=None):
            exp["on"] = not exp["on"]
            fill()
            fit()

        def fit():
            try:
                if T.winfo_width() < 50:
                    return
                avail = T.winfo_width() - 2 * S(14) - S(22) - S(8) - (gut.winfo_reqwidth() if gut is not None else 0)
                chars = max(24, avail // cw - 2)
                ct.configure(width=chars)
                cur["cap"] = chars * cw + 2 * S(8)   # known without layout: cards scrolled out of view are not mapped yet, so winfo_width() would lie
                hcheck()
            except tk.TclError:
                pass

        def hcheck():   # horizontal scrollbar only when a line is wider than the card (measured, not guessed from xview)
            try:
                need = max((F["code"].measure(l.expandtabs(8)) for l in cur["lines"]), default=0) + 2 * S(8) + 4
                if need > cur.get("cap", 10 ** 6):
                    hb.pack(fill="x", side="bottom")
                else:
                    hb.pack_forget()
            except tk.TclError:
                pass

        def do_copy(_e=None):
            if copy_raw(code, "Code copied"):
                cpb.configure(text="✓ Copied", fg=GREEN)
                root.after(1200, lambda: cpb.winfo_exists() and cpb.configure(text="⧉ Copy", fg=DIM))
        cpb.bind("<Button-1>", do_copy)
        cpb.bind("<Enter>", lambda e: cpb.configure(fg=ACC) if cpb.cget("text") == "⧉ Copy" else None)
        cpb.bind("<Leave>", lambda e: cpb.configure(fg=DIM) if cpb.cget("text") == "⧉ Copy" else None)
        if tgl is not None:
            tgl.bind("<Button-1>", toggle)

        def sel_copy(_e=None):
            try:
                s = ct.get("sel.first", "sel.last")
            except tk.TclError:
                s = ""
            copy_raw(s or code, "Selection copied" if s else "Code copied")
            return "break"
        ct.bind("<Control-c>", sel_copy)
        ct.bind("<Button-1>", lambda e: ct.focus_set(), add="+")
        if gut is not None:
            gut.bind("<Button-1>", lambda e: "break")
        for w in [f, inner, head, body, ct] + ([gut] if gut is not None else []):
            w.bind("<MouseWheel>", forward_wheel)
            w.bind("<Button-4>", forward_wheel)
            w.bind("<Button-5>", forward_wheel)
            w.bind("<Button-3>", lambda e, m=mid, ww=w: popup(m, e.x_root, e.y_root, ww), add="+")
        ct.bind("<Shift-MouseWheel>", lambda e: (ct.xview_scroll(-int(e.delta / 120) * 3, "units"), "break")[1])
        fill()
        st["frames"].append((f, fit))
        return f

    # ---- one message
    def insert_message(e):
        mid, out, text = e["id"], e["out"], e["text"]
        start = T.index("end-1c")
        hdr = "hdr_out" if out else "hdr_in"
        T.insert("end", "❯ " if out else "● ", (hdr,))
        T.insert("end", ("To " if out else "From ") + e["peer"], (hdr,))
        T.insert("end", "  ·  " + stamp(e["ts"]) + "      ", ("time",))
        acts = [("⧉ Copy", f"cp{mid}", lambda ev, m=mid: copy_message(m))]
        url = link_of(text)
        if url:
            acts.append(("↗ Open link", f"op{mid}", lambda ev, u=url: open_link(u)))
        acts.append(("⋯", f"mn{mid}", lambda ev, m=mid: popup(m, ev.x_root, ev.y_root, None)))
        for k, (label, tag, fn) in enumerate(acts):
            T.insert("end", label, ("act", tag))
            if k < len(acts) - 1:
                T.insert("end", "    ", ("time",))
            T.tag_bind(tag, "<Button-1>", fn)
            T.tag_bind(tag, "<Enter>", lambda ev, t=tag: (T.tag_configure(t, foreground=ACC), T.configure(cursor="hand2")))
            T.tag_bind(tag, "<Leave>", lambda ev, t=tag: (T.tag_configure(t, foreground=DIM), T.configure(cursor="arrow")))
        T.insert("end", "\n")
        mode = st["modes"].get(mid)
        fenced = "```" in text or "~~~" in text
        guess = None if (mode == "text" or fenced) else (guess_lang(text) or ("text" if mode == "code" else None))
        if mode == "code" and not guess:
            guess = "text"
        if guess:
            blocks = [{"t": "code", "lang": guess, "code": text.strip("\n")}]
        elif mode == "text":
            blocks = [{"t": "p", "runs": [(l, ())]} for l in text.strip("\n").split("\n")]
        else:
            blocks = parse_blocks(text)
        base = ("body",) + (("outb",) if out else ())
        for b in blocks:
            t = b["t"]
            if t == "code":
                T.window_create("end", window=code_block(mid, b["lang"], b["code"]), padx=0, pady=S(4))
                T.tag_add("body", "end-2c")
                T.insert("end", "\n")
                continue
            if t == "gap":
                T.insert("end", "\n", ("gap",))
                continue
            if t == "hr":
                T.insert("end", "─" * 40 + "\n", ("hr",))
                continue
            if t == "h":
                tags = base + (f"h{min(b['lvl'], 3)}",)
            elif t == "li":
                tags = ("body", f"li{b['ind']}") + base[1:]
                T.insert("end", b["mark"] + "\t", tags)
            elif t == "quote":
                tags = base + ("quote",)
                T.insert("end", "▎ ", tags + ("quotebar",))
            else:
                tags = base
            for txt, tg in b["runs"]:
                tt = list(tags)
                for x in tg:
                    if x.startswith("link:"):
                        st["n_link"] += 1
                        ln = f"lk{st['n_link']}"
                        st["links"][ln] = x[5:]
                        T.tag_configure(ln, foreground=BLUE, underline=True)
                        T.tag_bind(ln, "<Button-1>", lambda ev, u=x[5:]: open_link(u))
                        T.tag_bind(ln, "<Enter>", lambda ev: T.configure(cursor="hand2"))
                        T.tag_bind(ln, "<Leave>", lambda ev: T.configure(cursor="arrow"))
                        tt.append(ln)
                    else:
                        tt.append(x)
                T.insert("end", txt, tuple(tt))
            T.insert("end", "\n", tags)
        T.tag_add(f"m{mid}", start, "end-1c")

    def clear_dynamic_tags():
        for t in T.tag_names():
            if re.match(r"^(m|cp|op|mn|lk)\d+$", t):
                T.tag_delete(t)
        st["links"].clear()

    def refresh(force=False):
        items = text_list()
        ids = [e["id"] for e in items]
        rendered = st["rendered"]
        y0, y1 = T.yview()
        at_bottom = y1 >= 0.995 or not rendered
        T.configure(state="normal")
        try:
            if not force and rendered and set(rendered) <= set(ids):
                new = [e for e in items if e["id"] > max(rendered)]
                for e in new:
                    insert_message(e)
                st["rendered"] = rendered + [e["id"] for e in new]
            else:
                for fr, _ in st["frames"]:
                    try: fr.destroy()
                    except tk.TclError: pass
                st["frames"].clear()
                T.delete("1.0", "end")
                clear_dynamic_tags()
                if not items:
                    T.insert("end", "Nothing yet.\nShare text to LANShare Text from your phone, or type below.", ("hint",))
                    st["rendered"] = []
                else:
                    older = max(0, len(items) - st["limit"])
                    if older:
                        T.insert("end", f"▴ Show {min(older, RENDER_LIMIT)} older of {older}\n", ("older", "olderlink"))
                        T.tag_bind("olderlink", "<Button-1>", lambda ev: (st.update(limit=st["limit"] + RENDER_LIMIT), refresh(True)))
                        T.tag_bind("olderlink", "<Enter>", lambda ev: T.configure(cursor="hand2"))
                        T.tag_bind("olderlink", "<Leave>", lambda ev: T.configure(cursor="arrow"))
                    shown = items[-st["limit"]:]
                    for e in shown:
                        insert_message(e)
                    st["rendered"] = [e["id"] for e in shown]
        finally:
            T.configure(state="disabled")
        fit_all()
        if at_bottom:
            T.yview_moveto(1.0)
            root.after(60, lambda: T.yview_moveto(1.0))
        elif force:
            T.yview_moveto(y0)

    def fit_all(_e=None):
        if st["fit_job"]:
            root.after_cancel(st["fit_job"])
        def go():
            st["fit_job"] = None
            y1 = T.yview()[1]
            for fr, fit in list(st["frames"]):
                try:
                    if fr.winfo_exists():
                        fit()
                except tk.TclError:
                    pass
            if y1 >= 0.995:
                T.yview_moveto(1.0)
        st["fit_job"] = root.after(60, go)
    T.bind("<Configure>", fit_all)
    T.bind("<Button-1>", lambda e: T.focus_set(), add="+")

    # ---- context menu (long-press menu of the phone: Copy / Open link / Delete, plus code view switch)
    def mk_menu():
        return tk.Menu(root, tearoff=0, bg=PANEL, fg=FG, activebackground=ACC, activeforeground="#10151f", bd=0, relief="flat", font=F["body"])

    def popup(mid, x, y, widget):
        e = item_by_id(mid)
        if not e:
            return
        m = mk_menu()
        sel = ""
        for w in ([widget] if widget is not None and isinstance(widget, tk.Text) else []) + [T]:
            try:
                sel = w.get("sel.first", "sel.last")
                if sel:
                    break
            except tk.TclError:
                pass
        m.add_command(label="Copy message", command=lambda: copy_message(mid))
        if sel:
            m.add_command(label="Copy selection", command=lambda: copy_raw(sel, "Selection copied"))
        blocks = [b for b in parse_blocks(e["text"]) if b["t"] == "code"]
        if blocks:
            m.add_command(label="Copy all code" if len(blocks) > 1 else "Copy code", command=lambda: copy_raw("\n\n".join(b["code"] for b in blocks), "Code copied"))
        url = link_of(e["text"])
        if url:
            m.add_command(label="Open link", command=lambda: open_link(url))
        m.add_separator()
        cur = st["modes"].get(mid)
        looks_code = bool(guess_lang(e["text"])) or bool(blocks)
        if cur != "code" and not looks_code:
            m.add_command(label="Show as code", command=lambda: (st["modes"].__setitem__(mid, "code"), refresh(True)))
        if cur != "text" and looks_code:
            m.add_command(label="Show as plain text", command=lambda: (st["modes"].__setitem__(mid, "text"), refresh(True)))
        if cur:
            m.add_command(label="Reset view", command=lambda: (st["modes"].pop(mid, None), refresh(True)))
        m.add_command(label="Delete", command=lambda: text_delete(mid))
        try:
            m.tk_popup(x, y)
        finally:
            m.grab_release()

    def on_T_right(ev):
        idx = T.index(f"@{ev.x},{ev.y}")
        for t in T.tag_names(idx):
            mm = re.match(r"^m(\d+)$", t)
            if mm:
                popup(int(mm.group(1)), ev.x_root, ev.y_root, T)
                return
    T.bind("<Button-3>", on_T_right)

    def ctrl_c(_e=None):
        try:
            s = T.get("sel.first", "sel.last")
        except tk.TclError:
            s = ""
        if s:
            copy_raw(s, "Selection copied")
        else:
            items = text_list()
            if items:
                copy_message(items[-1]["id"])
        return "break"
    T.bind("<Control-c>", ctrl_c)

    # ---- target device line + menus
    def target_info():
        ds = devices_now()
        d = settings_get()["def_target"]
        byid = {x["id"]: x for x in ds}
        if d and d in byid:
            return byid[d], f"To: {byid[d]['name']}  ▾   (default device)"
        if d:
            return None, "To: a device that is offline now  ▾"
        if len(ds) == 1:
            return ds[0], f"To: {ds[0]['name']}  ▾   (the only device online)"
        return None, ("To: ask every time  ▾" if ds else "To: no device found yet - open LANShare on the phone (same Wi-Fi or Tailscale)  ▾")

    def refresh_devices():
        ds = devices_now()
        _, label = target_info()
        to_line.configure(text=label)
        if SERVER_ERR[0]:
            info.configure(text=SERVER_ERR[0], fg=RED)
        else:
            info.configure(text=f"{socket.gethostname()}  ·  receiving on :{st.get('port', PORT)}  ·  {len(ds)} device{'s' if len(ds) != 1 else ''} found", fg=DIM)

    def choose_target(then=None):
        ds = devices_now()
        if not ds:
            say("No device found yet - the phone must be on this network with LANShare open", RED)
            return
        m = mk_menu()
        d = settings_get()["def_target"]
        if then is None:
            m.add_command(label=("✓  " if not d else "     ") + "Ask every time", command=lambda: (settings_set(def_target=""), refresh_devices()))
        for x in ds:
            if then is None:
                m.add_command(label=("✓  " if x["id"] == d else "     ") + x["name"], command=lambda i=x["id"]: (settings_set(def_target=i), refresh_devices()))
            else:
                m.add_command(label=x["name"], command=lambda i=x["id"]: then(i))
        x, y = to_line.winfo_rootx() + S(14), to_line.winfo_rooty() + to_line.winfo_height()
        try:
            m.tk_popup(x, y)
        finally:
            m.grab_release()
    to_line.bind("<Button-1>", lambda e: choose_target(None))

    ac_var = tk.BooleanVar(value=cfg["autocopy"])
    pop_var = tk.BooleanVar(value=cfg["popup"])
    top_var = tk.BooleanVar(value=cfg["topmost"])

    def apply_top():
        root.attributes("-topmost", bool(top_var.get()))
        settings_set(topmost=bool(top_var.get()))

    def clear_all():
        from tkinter import messagebox
        if text_list() and messagebox.askyesno("Clear", "Remove all texts from this PC's list?", parent=root):
            text_delete(None)

    def open_data():
        try:
            os.makedirs(APP, exist_ok=True)
            os.startfile(APP) if WIN else subprocess.Popen(["xdg-open", APP])
        except Exception as ex:
            say(str(ex), RED)

    def add_host():
        from tkinter import simpledialog
        v = simpledialog.askstring("Add device by IP", "Address of the phone (Tailscale IP like 100.x.y.z, or its MagicDNS name).\nThis PC then announces itself to it; the phone must have LANShare open.", parent=root)
        if v and v.strip():
            hosts = [h for h in settings_get()["hosts"] if h != v.strip()] + [v.strip()]
            settings_set(hosts=hosts)
            say(f"Added {v.strip()} - the phone should show this PC within a few seconds", GREEN, 5000)

    def forget_hosts():
        if settings_get()["hosts"]:
            settings_set(hosts=[])
            say("Hand-added addresses removed", GREEN)

    def options_menu(_e=None):
        m = mk_menu()
        m.add_checkbutton(label="Copy received text to the clipboard automatically", variable=ac_var, command=lambda: settings_set(autocopy=bool(ac_var.get())), selectcolor=ACC)
        m.add_checkbutton(label="Show this window when text arrives", variable=pop_var, command=lambda: settings_set(popup=bool(pop_var.get())), selectcolor=ACC)
        m.add_checkbutton(label="Always on top", variable=top_var, command=apply_top, selectcolor=ACC)
        m.add_separator()
        m.add_command(label="Choose default device…", command=lambda: choose_target(None))
        m.add_command(label="Add device by IP / Tailscale name…", command=add_host)
        m.add_command(label="Forget hand-added addresses", command=forget_hosts)
        m.add_command(label="Open data folder", command=open_data)
        m.add_command(label="Clear all texts…", command=clear_all)
        try:
            m.tk_popup(menu_btn.winfo_rootx(), menu_btn.winfo_rooty() + menu_btn.winfo_height())
        finally:
            m.grab_release()
    menu_btn.bind("<Button-1>", options_menu)

    # ---- sending
    def paste():
        try:
            t = root.clipboard_get()
        except tk.TclError:
            t = ""
        if not t:
            say("Clipboard is empty", RED)
            return
        inp.insert("insert", t)
        grow()
        inp.focus_set()

    def send_to(t, did):
        name = next((x["name"] for x in devices_now() if x["id"] == did), "device")
        say(f"Sending to {name}…")

        def go():
            try:
                ok, err = text_send(did, t)
            except Exception as ex:
                ok, err = False, str(ex)

            def done():
                if ok:
                    inp.delete("1.0", "end")
                    grow()
                    say(f"Sent to {name}", GREEN)
                else:
                    say(str(err)[:160], RED, 6000)
            post(done)
        threading.Thread(target=go, daemon=True).start()

    def send(_e=None):
        t = inp.get("1.0", "end").strip()
        if not t:
            say("Type something first", RED)
            return "break"
        dev, _ = target_info()
        if dev:
            send_to(t, dev["id"])
        else:
            choose_target(lambda did: send_to(t, did))
        return "break"

    def grow(_e=None):
        try:
            n = inp.count("1.0", "end-1c", "displaylines")
            n = int(n[0]) if n else 1
        except tk.TclError:
            n = 1
        inp.configure(height=max(1, min(8, n + (0 if n else 1))))

    inp.bind("<Return>", send)
    inp.bind("<Control-Return>", send)
    inp.bind("<Shift-Return>", lambda e: (inp.insert("insert", "\n"), grow(), "break")[2])
    inp.bind("<KeyRelease>", grow)
    inp.bind("<<Paste>>", lambda e: root.after(20, grow), add="+")

    # ---- window behaviour
    def bring_front():
        try:
            if root.state() == "iconic":
                root.deiconify()
            root.lift()
            root.attributes("-topmost", True)
            root.after(700, lambda: root.attributes("-topmost", bool(top_var.get())))
        except tk.TclError:
            pass

    def tick():
        try:
            while True:
                try:
                    resq.get_nowait()()
                except queue.Empty:
                    break
            last = None
            while True:
                try:
                    last = CLIPQ.get_nowait()
                except queue.Empty:
                    break
            copied = False
            if last is not None:
                copied = copy_text(root, last)
                say("Received text copied to the clipboard" if copied else "Received text - could not reach the clipboard", GREEN if copied else RED, 4000)
            events = []
            while True:
                try:
                    events.append(EVENTQ.get_nowait())
                except queue.Empty:
                    break
            if STORE_VER[0] != st["ver"]:
                st["ver"] = STORE_VER[0]
                refresh()
            show = False
            for ev in events:
                if ev[0] == "show":
                    show = True
                elif ev[0] == "text":
                    _, entry, auto, quiet = ev
                    if auto and copied:
                        flash_tag(f"cp{entry['id']}", 1500)
                    if pop_var.get() and not (quiet and auto):
                        show = True
            if show:
                bring_front()
            if time.time() >= st["next_dev"]:
                st["next_dev"] = time.time() + 1.5
                refresh_devices()
        except Exception as ex:
            log(f"tick error: {ex!r}")
        root.after(250, tick)

    def on_close():
        try:
            settings_set(geometry=root.geometry())
        except Exception:
            pass
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    root.geometry(cfg.get("geometry") or "760x680")
    root.attributes("-topmost", bool(cfg["topmost"]))
    srv = start_server(port) if serve else None
    st["port"] = port if port is not None else PORT
    if serve:
        threading.Thread(target=beacon_listener, daemon=True).start()
        threading.Thread(target=beacon_sender, daemon=True).start()
        threading.Thread(target=ensure_firewall, daemon=True).start()
    refresh(True)
    refresh_devices()
    inp.focus_set()
    root.after(250, tick)
    root.mainloop()
    if srv:
        srv.shutdown()


def single_instance():
    """True when this is the only copy. A second copy asks the first one to show itself and exits."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", LOCK_PORT))
        s.listen(4)
    except OSError:
        try:
            c = socket.create_connection(("127.0.0.1", LOCK_PORT), timeout=2)
            c.sendall(b"show")
            c.close()
        except OSError:
            pass
        return False

    def loop():
        while True:
            try:
                c, _ = s.accept()
                c.close()
                EVENTQ.put(("show",))
            except OSError:
                return
    threading.Thread(target=loop, daemon=True).start()
    single_instance.sock = s
    return True


# ================================================================== self test (no window): python lanshare_text.py --selftest
def selftest():
    global APP, TEXT_FILE, TEXT_CFG, LOG_FILE
    import tempfile, urllib.request, http.client
    APP = tempfile.mkdtemp(prefix="lst_")
    TEXT_FILE, TEXT_CFG, LOG_FILE = (os.path.join(APP, n) for n in ("inbox.json", "text.json", "log.txt"))
    bad = []

    def ok(cond, what):
        print(("ok    " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # highlighter
    kinds = lambda src, lang: [(src[s:e], KIND_NAMES[k]) for s, e, k in Hl.scan(src, lang)]
    py = kinds('def f(x):\n    # hi\n    return "a" + str(3)  # n\nclass Foo: pass', "py")
    ok(("def", "keyword") in py and ("# hi", "comment") in py and ('"a"', "string") in py and ("3", "number") in py and ("Foo", "type") in py and ("str", "func") in py, "python tokens")
    js = kinds('const a = `x\ny`; /* c */ fn(1.5)', "js")
    ok(("const", "keyword") in js and ("/* c */", "comment") in js and ("1.5", "number") in js and ("fn", "func") in js, "js tokens (template string, block comment)")
    jj = kinds('{"name": "x", "n": 2, "ok": true}', "json")
    ok(('"name"', "attr") in jj and ('"x"', "string") in jj and ("2", "number") in jj and ("true", "keyword") in jj, "json keys vs strings")
    ym = kinds("name: app\nitems:\n  - a: 1\n# c\n", "yaml")
    ok(("name", "attr") in ym and ("a", "attr") in ym and ("# c", "comment") in ym, "yaml keys")
    ht = kinds('<div class="a">hi</div><!-- c --><script>var x = 1;</script>', "html")
    ok(("<div", "keyword") in ht and ("class", "attr") in ht and ('"a"', "string") in ht and ("<!-- c -->", "comment") in ht and ("var", "keyword") in ht, "html tags + embedded js")
    ok(Hl.key("Kotlin") == "java" and Hl.key("PS1") == "sh" and Hl.key("nope") is None and Hl.scan("x", "nope") is None, "language aliases")
    big = Hl.scan("a = 1\n" * 30000, "py")
    ok(len(big) <= MAX_TOKENS, "token cap")

    # markdown blocks
    b = parse_blocks("# Title\nhello **bold** and `x` [site](http://a.b/c) see http://x.y/z.\n\n- one\n  - two\n1. num\n> quote\n```py\nprint(1)\n```\n| a | b |\n|---|---|\n| 1 | 2 |\n```\nunclosed")
    tt = [x["t"] for x in b]
    ok(tt[:2] == ["h", "p"] and "li" in tt and "quote" in tt and tt.count("code") == 3, f"block kinds {tt}")
    runs = [r for r in b[1]["runs"]]
    ok(any("bold" in t for _, t in runs) and any("icode" in t for _, t in runs) and sum(1 for _, t in runs if any(x.startswith("link:") for x in t)) == 2, "inline runs (bold, code, 2 links)")
    ok([x for x in b if x["t"] == "li"][1]["ind"] == 1, "nested list indent")
    ok(b[-1]["code"] == "unclosed", "unclosed fence still code")
    ok(not any(u.endswith(".") for _, t in runs for u in [x[5:] for x in t if x.startswith("link:")]), "link drops trailing dot")

    # code guessing
    ok(guess_lang('def f(x):\n    return x + 1\nprint(f(2))') == "py", "guess python")
    ok(guess_lang('{"a": 1, "b": [1,2]}') == "json", "guess json")
    ok(guess_lang("pip install requests") == "sh", "guess shell one-liner")
    ok(guess_lang("<div class='a'>x</div>") == "html", "guess html")
    ok(guess_lang("const a = 1;\nfunction f() {\n  return a;\n}") == "js", "guess js")
    ok(guess_lang("Hello, can you send me the file when you are home tonight?") is None, "prose is not code")
    ok(guess_lang("Meeting notes:\nWe talked about the budget for next year.\nNobody objected to the plan, so we go ahead.") is None, "multi-line prose is not code")

    # store, receive, send
    t0 = text_add(False, "Pixel", "hello")
    ok(t0[1] and text_add(False, "Pixel", "hello")[1] is False, "store + 2 s duplicate guard")
    text_add(False, "Pixel", "   ")
    ok(len(text_list()) == 1, "blank text ignored")
    srv = start_server(0)
    ok(srv is not None, "receiver starts")
    port = srv.server_address[1]
    r = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/ping", timeout=3).read())
    ok(r.get("clip") is True and r.get("ok"), "/ping answers clip:true (phone discovery)")
    for path in ("/p/clip", "/clip"):
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps({"text": f"via {path}", "from": "Pixel", "id": "dev1", "port": 41999}).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        ok(json.loads(urllib.request.urlopen(req, timeout=3).read()).get("ok"), f"POST {path}")
    got = []
    while not CLIPQ.empty():
        got.append(CLIPQ.get())
    ok(got == ["via /p/clip", "via /clip"], "auto-copy queue has both received texts (auto-copy default ON)")
    ok(any(d["id"] == "dev1" and d["port"] == 41999 for d in devices_now()), "sender registered as a target device")
    ev = []
    while not EVENTQ.empty():
        ev.append(EVENTQ.get())
    ok(len(ev) == 2 and ev[0][0] == "text", "window events queued")
    for body, code in ((b"{", 400), (json.dumps({"text": "  "}).encode(), 400)):
        try:
            urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/clip", data=body, method="POST"), timeout=3)
            c = 200
        except urllib.error.HTTPError as e:
            c = e.code
        ok(c == code, f"bad body -> {code}")
    settings_set(autocopy=False)
    text_received("quiet one", "Pixel", True)
    ok(CLIPQ.empty(), "auto-copy OFF: nothing queued")
    settings_set(autocopy=True)
    ok(lan_ok("192.168.1.5") and lan_ok("127.0.0.1") and lan_ok("100.100.1.1") and not lan_ok("8.8.8.8"), "LAN / tailscale only")

    class Stub(BaseHTTPRequestHandler):
        got = None

        def log_message(self, *a): pass

        def do_POST(self):
            Stub.got = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"{}")
    stub = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    device_seen("phone1", "Stub phone", "127.0.0.1", stub.server_address[1])
    res = text_send("phone1", "to phone")
    ok(res == (True, None) and Stub.got["text"] == "to phone" and TEXTS[-1]["out"] and TEXTS[-1]["peer"] == "Stub phone", "send to a phone (/p/clip) is stored as outgoing")
    ok(text_send("nobody", "x")[0] is False, "unknown device -> error")
    stub.shutdown()

    # beacons
    th = threading.Thread(target=beacon_listener, daemon=True)
    th.start()
    time.sleep(0.4)
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    u.sendto(json.dumps({"app": "lanshare", "id": "beacon1", "name": "Beacon phone", "port": 41234}).encode(), ("127.0.0.1", BEACON_PORT))
    time.sleep(0.5)
    ok(any(d["id"] == "beacon1" for d in devices_now()), "UDP beacon -> device list")

    # handshake: /ping?id=&name=&port= registers the caller (phone over Tailscale) and answers with this PC's id
    r = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/ping?id=hs1&name=Handshake%20phone&port=40123", timeout=3).read())
    ok(r.get("clip") is True and r.get("id") == my_id() and r.get("app") == "lanshare-text", "/ping answers with this PC's id")
    ok(any(d["id"] == "hs1" and d["port"] == 40123 and d["name"] == "Handshake phone" for d in devices_now()), "/ping handshake registers the phone")
    ok(PORT != 8799 and PORT == 48558 and TEXT_BEACON_PORT == 48559, "own ports (not pcprint's 8799)")

    # no limits: 3 MB in one text, and far more than 300 texts, nothing cut
    big = "x" * 3_000_000 + "END"
    req = urllib.request.Request(f"http://127.0.0.1:{port}/p/clip", data=json.dumps({"text": big, "from": "big"}).encode(), method="POST", headers={"Content-Type": "application/json"})
    ok(json.loads(urllib.request.urlopen(req, timeout=20).read()).get("ok"), "3 MB text accepted")
    ok(TEXTS[-1]["text"] == big, "3 MB text stored whole (no 100k cut)")
    for i in range(450):
        text_add(False, "bulk", f"bulk {i}")
    ok(sum(1 for t in TEXTS if t["peer"] == "bulk") == 450 and any(t["text"] == big for t in TEXTS), "more than 300 texts / 2 MB kept (no trimming)")
    stub2 = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=stub2.serve_forever, daemon=True).start()
    device_seen("phone2", "Stub 2", "127.0.0.1", stub2.server_address[1])
    ok(text_send("phone2", big) == (True, None) and Stub.got["text"] == big, "3 MB text sent to a phone whole")
    stub2.shutdown()

    # this PC's own beacon: broadcast + unicast to known devices (listen on the beacon port, let the sender run one beat)
    lu = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    lu.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    lu.bind(("127.0.0.1", TEXT_BEACON_PORT))
    lu.settimeout(4)
    stop = threading.Event()
    threading.Thread(target=beacon_sender, args=(stop,), daemon=True).start()
    try:
        bm = json.loads(lu.recvfrom(4096)[0].decode())
    except (socket.timeout, ValueError):
        bm = {}
    stop.set()
    lu.close()
    ok(bm.get("app") == "lanshare-text" and bm.get("port") == PORT and bm.get("id") == my_id() and bm.get("name"), "beacon: unicast to a known device carries app / id / name / port")

    # persistence + delete
    n = len(text_list())
    _TLOADED[0] = False
    TEXTS.clear()
    _text_load()
    ok(len(TEXTS) == n and TEXT_SET["autocopy"] is True, "inbox.json round trip")
    text_delete(TEXTS[0]["id"])
    ok(len(text_list()) == n - 1, "delete one")
    text_delete(None)
    ok(text_list() == [], "clear all")
    srv.shutdown()
    shutil.rmtree(APP, ignore_errors=True)
    print("\nALL OK" if not bad else f"\n{len(bad)} FAILED: {bad}")
    return 1 if bad else 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    port = None
    if "--port" in argv:
        try:
            port = int(argv[argv.index("--port") + 1])
        except (ValueError, IndexError):
            print("usage: lanshare_text.py [--port N] [--selftest]")
            return 2
    if not single_instance():
        return 0
    try:
        import tkinter  # noqa: F401
    except ImportError:
        print("tkinter is not installed (on Windows: reinstall Python with the 'tcl/tk' option); the text window needs it")
        return 1
    run_window(port)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
