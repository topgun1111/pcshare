#!/usr/bin/env python3
"""LANShare - find devices running the same app on your Wi-Fi / hotspot, browse their
storage, copy / cut / paste between devices, and send files.   Python 3.8+, stdlib only.

Run in Pydroid 3 (or any Python). It opens a phone-friendly UI at http://127.0.0.1:8765
Optional:  python lanshare.py [shared_root_folder]

How it works
  * Every device runs this same file: a small HTTP server + the web UI.
  * Discovery: UDP beacons (broadcast + unicast sweep of the subnet). Devices see each
    other within ~1s, and a hotspot host sees its clients too.
  * Also a TCP scan of the subnet + a live check of known devices, so devices show up
    and stay listed even when the router/hotspot drops UDP broadcasts.
  * No PIN, no pairing: every LANShare device on the network can be opened directly.
    Only use it on networks you trust.
"""
import os, sys, re, json, time, uuid, errno, socket, shutil, struct, mimetypes
import posixpath, threading, subprocess, ipaddress, webbrowser
import urllib.parse, http.client
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BEACON_PORT = 48555
BASE_PORT = int(os.environ.get("LANSHARE_PORT", 8765))
CHUNK = 1 << 20
INBOX = "/"  # Send puts files/folders in the target device's main storage root


# ----------------------------------------------------------------- config
def _cfg_path():
    if os.environ.get("LANSHARE_CFG"):
        return os.environ["LANSHARE_CFG"]
    for d in (os.path.expanduser("~"), os.path.dirname(os.path.abspath(__file__)), "."):
        if d and os.access(d, os.W_OK):
            return os.path.join(d, ".lanshare.json")
    return ".lanshare.json"


CFG_FILE = _cfg_path()
CFG = {}


def phone_model():
    """Phone model (e.g. 'Pixel 7', 'SM-S918B') on Android; '' if it can't be read."""
    for prop in ("ro.product.marketname", "ro.product.model", "ro.product.device"):
        try:
            v = subprocess.check_output(["getprop", prop], stderr=subprocess.DEVNULL,
                                        timeout=3).decode("utf-8", "ignore").strip()
        except Exception:
            v = ""
        if v:
            return v[:40]
    try:  # Pydroid / Chaquopy-style fallback
        from jnius import autoclass
        b = autoclass("android.os.Build")
        return str(b.MODEL).strip()[:40]
    except Exception:
        return ""


def load_cfg():
    try:
        with open(CFG_FILE) as f:
            CFG.update(json.load(f))
    except Exception:
        pass
    CFG.setdefault("id", uuid.uuid4().hex[:8])
    CFG.pop("pin", None)
    CFG.pop("paired", None)
    host = socket.gethostname()
    fallback = host if host not in ("", "localhost") else "Phone-" + CFG["id"][:4]
    cur = CFG.get("name")
    # use the phone model unless the user picked a name themselves (an old auto name is replaced)
    if not CFG.get("name_custom") and (not cur or cur == fallback or cur.startswith("Phone-")):
        CFG["name"] = phone_model() or cur or fallback
    save_cfg()


def save_cfg():
    try:
        with open(CFG_FILE, "w") as f:
            json.dump(CFG, f)
    except Exception:
        pass


# ----------------------------------------------------------------- paths
def vnorm(p):
    return posixpath.normpath("/" + str(p).replace("\\", "/").lstrip("/"))


def vjoin(a, b):
    return vnorm(a + "/" + b)


def unique_name(name, taken, is_dir):
    if name not in taken:
        return name
    base, ext = (name, "") if is_dir else posixpath.splitext(name)
    i = 1
    while "%s (%d)%s" % (base, i, ext) in taken:
        i += 1
    return "%s (%d)%s" % (base, i, ext)


def default_root():
    for p in ("/storage/emulated/0", "/sdcard"):
        if os.path.isdir(p):
            return p
    return os.path.expanduser("~")


# ----------------------------------------------------------------- endpoints
def fsize(p):
    try:
        return os.path.getsize(p)
    except OSError:
        pass
    try:
        with open(p, "rb") as f:
            f.seek(0, 2)
            return f.tell()
    except OSError:
        return 0


def storage_ok():
    """False when Android 11+ 'All files access' is NOT granted (apps then only see folders and their
    own/media files - other files look missing). None = unknown / not Android."""
    try:
        from jnius import autoclass
        sdk = autoclass("android.os.Build$VERSION").SDK_INT
        if sdk < 30:
            return True
        return bool(autoclass("android.os.Environment").isExternalStorageManager())
    except Exception:
        return None


STORAGE_MSG = ("This phone hides files from LANShare: open Android Settings > Apps > LANShare > "
               "Permissions > Files and media (or 'All files access') and allow management of all files, "
               "then restart the app")


class Local:
    """Files on this device, exposed as virtual paths rooted at '/'."""
    id = "local"
    name = "This device"

    def __init__(self, root):
        self.root = os.path.realpath(root)

    def real(self, v):
        p = os.path.realpath(os.path.join(self.root, vnorm(v).lstrip("/")))
        if p != self.root and not p.startswith(self.root.rstrip(os.sep) + os.sep):
            raise PermissionError("outside the shared folder")
        return p

    def ls(self, v):
        out = []
        with os.scandir(self.real(v)) as it:
            for e in it:
                try:
                    d = e.is_dir()
                except OSError:
                    d = False
                try:  # never hide an entry just because stat() is refused (Android storage quirks)
                    st = e.stat()
                    size, mt = (0 if d else st.st_size), int(st.st_mtime)
                except OSError:
                    size, mt = 0, 0
                it = {"name": e.name, "dir": d, "size": size, "mtime": mt}
                if d:  # number of entries, shown as "4 items" under a folder
                    try:
                        with os.scandir(e.path) as sub:
                            it["n"] = sum(1 for _ in sub)
                    except OSError:
                        pass
                out.append(it)
        if not out and STORAGE.get("ok") is False:
            raise PermissionError(STORAGE_MSG)
        return out

    def search(self, v, q, limit=300, secs=15):
        """Recursive name search below folder v (case-insensitive substring). Capped by count and time."""
        q, base, out, end = q.lower(), self.real(v), [], time.time() + secs
        for dp, dns, fns in os.walk(base):
            rel = os.path.relpath(dp, self.root).replace(os.sep, "/")
            vdir = "/" if rel == "." else "/" + rel
            for n, d in [(x, True) for x in dns] + [(x, False) for x in fns]:
                if q not in n.lower():
                    continue
                try:
                    st = os.stat(os.path.join(dp, n))
                    size, mt = (0 if d else st.st_size), int(st.st_mtime)
                except OSError:
                    size, mt = 0, 0
                out.append({"name": n, "dir": d, "size": size, "mtime": mt, "path": vjoin(vdir, n)})
                if len(out) >= limit:
                    return {"items": out, "partial": True}
            if time.time() > end:
                return {"items": out, "partial": True}
        out.sort(key=lambda i: (not i["dir"], i["name"].lower()))
        return {"items": out, "partial": False}

    def names(self, v):
        try:
            return {i["name"] for i in self.ls(v)}
        except (OSError, PermissionError):
            return set()

    def walk(self, v):
        base = self.real(v)
        if os.path.isfile(base):
            return [{"rel": "", "dir": False, "size": fsize(base)}]
        res = [{"rel": "", "dir": True, "size": 0}]
        for dp, dns, fns in os.walk(base):
            for n in dns:
                if not os.path.islink(os.path.join(dp, n)):
                    res.append({"rel": os.path.relpath(os.path.join(dp, n), base).replace(os.sep, "/"), "dir": True, "size": 0})
            for n in fns:
                fp = os.path.join(dp, n)
                if os.path.islink(fp):
                    continue
                res.append({"rel": os.path.relpath(fp, base).replace(os.sep, "/"), "dir": False, "size": fsize(fp)})
        return res

    def open_read(self, v):
        p = self.real(v)
        return open(p, "rb"), fsize(p)

    def write(self, v, fobj, size, cb=None):
        p = self.real(v)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        tmp = p + ".lspart"
        left = size
        try:
            with open(tmp, "wb") as f:
                while left > 0:
                    b = fobj.read(min(CHUNK, left))
                    if not b:
                        raise IOError("connection lost")
                    f.write(b)
                    left -= len(b)
                    if cb:
                        cb(len(b))
            os.replace(tmp, p)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)

    def mkdir(self, v):
        os.makedirs(self.real(v), exist_ok=True)

    def remove(self, v):
        p = self.real(v)
        if p == self.root:
            raise PermissionError("cannot delete the shared root")
        shutil.rmtree(p) if os.path.isdir(p) and not os.path.islink(p) else os.remove(p)

    def rename(self, v, newname):
        if not newname or "/" in newname or "\\" in newname or newname in (".", ".."):
            raise ValueError("invalid name")
        p = self.real(v)
        dst = os.path.join(os.path.dirname(p), newname)
        if os.path.exists(dst):
            raise FileExistsError("name already exists")
        os.rename(p, self.real(vjoin(posixpath.dirname(vnorm(v)), newname)))

    def move(self, v, to_v):
        shutil.move(self.real(v), self.real(to_v))


def src_for(ip):
    """Our own address on the same subnet as `ip` (None if unknown). Binding outgoing sockets to it
    makes Android send the traffic over Wi-Fi/hotspot instead of through a VPN."""
    try:
        a = ipaddress.ip_address(ip)
        for mine, net in (DISC.ifaces if DISC else []):
            if a in net:
                return mine
    except Exception:
        pass
    return None


def conn_to(ip, port, timeout):
    src = src_for(ip)
    try:
        if src:
            return http.client.HTTPConnection(ip, port, timeout=timeout, blocksize=1 << 16, source_address=(src, 0))
    except TypeError:
        pass
    return http.client.HTTPConnection(ip, port, timeout=timeout, blocksize=1 << 16)


class Stream:
    def __init__(self, c, r):
        self.c, self.r = c, r

    def read(self, n=-1):
        return self.r.read(n)

    def close(self):
        try:
            self.r.close()
        finally:
            self.c.close()


class RemoteFile:
    """Read-only, seekable view of a file on another device.
    Re-opens with a Range header on seek and after a dropped connection."""

    def __init__(self, remote, v):
        self.rm, self.v, self.pos, self.s = remote, v, 0, None
        self.s, self.size = remote._call("GET", "file", {"path": v}, stream=True)

    def seek(self, n):
        if self.s:
            self.s.close()
            self.s = None
        self.pos = n

    def _reopen(self):
        hdr = {"Range": "bytes=%d-" % self.pos} if self.pos else None
        self.s, _ = self.rm._call("GET", "file", {"path": self.v}, stream=True, headers=hdr)

    def read(self, n=-1):
        last = None
        for attempt in range(5):
            try:
                if self.s is None:
                    self._reopen()
                b = self.s.read(n)
                self.pos += len(b)
                return b
            except (PermissionError, FileNotFoundError):
                raise
            except Exception as e:
                last = e
                if self.s:
                    try:
                        self.s.close()
                    except Exception:
                        pass
                    self.s = None
                time.sleep(0.5 * (attempt + 1))
        raise IOError("connection lost (%s)" % last)

    def close(self):
        if self.s:
            try:
                self.s.close()
            except Exception:
                pass
            self.s = None


class Counter:
    def __init__(self, f, cb):
        self.f, self.cb = f, cb

    def read(self, n=-1):
        b = self.f.read(n)
        if b and self.cb:
            self.cb(len(b))
        return b


class Remote:
    """Another LANShare device, same interface as Local."""

    def __init__(self, peer):
        self.id, self.name, self.ip, self.port = peer["id"], peer["name"], peer["ip"], peer["port"]
        self.ips = [peer["ip"]] + [i for i in peer.get("ips", []) if i != peer["ip"]]

    def _call(self, method, route, params=None, body=None, size=None, stream=False, headers=None):
        h = {}
        if headers:
            h.update(headers)
        if body is not None:
            h["Content-Length"] = str(size)
        retry = (method == "GET" or route == "mkdir") and body is None
        r = c = None
        err = None
        order = list(self.ips)
        for rnd in range(3 if retry else 1):
            for ip in order:
                c = conn_to(ip, self.port, 30)
                try:
                    c.request(method, "/p/%s?%s" % (route, urllib.parse.urlencode(params or {})), body=body, headers=h)
                    r = c.getresponse()
                    err = None
                    if ip != self.ip:  # remember the address that works
                        self.ip = ip
                        if DISC:
                            with DISC.lock:
                                if self.id in DISC.peers:
                                    DISC.peers[self.id]["ip"] = ip
                    break
                except Exception as e:
                    err = e
                    c.close()
                    if body is not None:
                        break  # an upload body cannot be replayed
            if err is None:
                break
            time.sleep(0.4)
        if err is not None:
            hint = " - LANShare is not running there (Android may have paused it); open it on that phone" if isinstance(err, ConnectionRefusedError) else ""
            raise IOError("%s unreachable at %s (%s)%s" % (self.name, ", ".join(order), err, hint))
        if r.status not in (200, 206):
            txt = r.read(800).decode("utf8", "ignore")
            c.close()
            try:
                txt = json.loads(txt).get("error", txt)
            except Exception:
                pass
            if r.status == 404:
                raise FileNotFoundError(txt)
            if r.status == 403:
                raise PermissionError(txt)
            raise IOError("%s: %s" % (self.name, txt))
        if stream:
            return Stream(c, r), int(r.getheader("Content-Length") or 0)
        data = r.read()
        c.close()
        return data

    def ping(self):
        self._call("GET", "ping")

    def ls(self, v):
        return json.loads(self._call("GET", "ls", {"path": v}))

    def search(self, v, q):
        return json.loads(self._call("GET", "search", {"path": v, "q": q}))

    def names(self, v):
        try:
            return {i["name"] for i in self.ls(v)}
        except FileNotFoundError:
            return set()

    def walk(self, v):
        return json.loads(self._call("GET", "walk", {"path": v}))

    def open_read(self, v):
        f = RemoteFile(self, v)
        return f, f.size

    def write(self, v, fobj, size, cb=None):
        self._call("POST", "put", {"path": v}, body=Counter(fobj, cb), size=size)

    def mkdir(self, v):
        self._call("POST", "mkdir", {"path": v})

    def remove(self, v):
        self._call("POST", "rm", {"path": v})

    def rename(self, v, newname):
        self._call("POST", "rename", {"path": v, "name": newname})

    def move(self, v, to_v):
        self._call("POST", "mv", {"path": v, "to": to_v})


LOCAL = None
DISC = None
STORAGE = {}


def ep(dev):
    if dev == "local":
        return LOCAL
    p = DISC.get(dev)
    if not p:
        raise IOError("that device is offline")
    return Remote(p)


# ----------------------------------------------------------------- discovery
SKIP_IF = ("lo", "rmnet", "ccmni", "tun", "ppp", "dummy", "v4-", "clat", "docker", "veth")


def get_ifaces():
    found = {}
    try:  # Linux / Android: ioctl per interface
        import fcntl
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for _, name in socket.if_nameindex():
            if name.startswith(SKIP_IF):
                continue
            b = struct.pack("256s", name[:15].encode())
            try:
                ip = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, b)[20:24])
                found[ip] = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x891B, b)[20:24])
            except OSError:
                pass
        s.close()
    except Exception:
        pass
    if not found:
        try:
            out = subprocess.run(["ip", "-4", "-o", "addr"], capture_output=True, text=True, timeout=3).stdout
            for line in out.splitlines():
                m = re.search(r"^\d+:\s+(\S+).*?inet (\d+\.\d+\.\d+\.\d+)/(\d+)", line)
                if m and not m.group(1).startswith(SKIP_IF):
                    found[m.group(2)] = str(ipaddress.ip_network("0.0.0.0/" + m.group(3)).netmask)
        except Exception:
            pass
    if not found:
        for probe in ("192.168.43.1", "192.168.1.1", "10.0.0.1", "8.8.8.8"):
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect((probe, 9))
                found.setdefault(s.getsockname()[0], "255.255.255.0")
                s.close()
            except OSError:
                pass
        try:
            for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
                found.setdefault(ip, "255.255.255.0")
        except OSError:
            pass
    res = []
    for ip, mask in found.items():
        if ip.startswith(("127.", "169.254.", "0.")):
            continue
        try:
            net = ipaddress.ip_network("%s/%s" % (ip, mask), strict=False)
        except ValueError:
            continue
        if net.prefixlen < 22 or net.prefixlen > 30:
            net = ipaddress.ip_network(ip + "/24", strict=False)
        res.append((ip, net))
    return res


def arp_neighbors():
    ips = []
    try:
        for line in open("/proc/net/arp").read().splitlines()[1:]:
            p = line.split()
            if len(p) >= 4 and p[3] != "00:00:00:00:00:00":
                ips.append(p[0])
    except OSError:
        pass
    return ips


def hello_url(ip, port, timeout=2.5):
    c = conn_to(ip, port, timeout)
    try:
        c.request("GET", "/p/hello")
        return json.loads(c.getresponse().read())
    finally:
        c.close()


class Discovery:
    PEER_TTL = 60

    def __init__(self, port):
        self.port = port
        self.peers = {}
        self.lock = threading.Lock()
        self.ifaces = get_ifaces()
        self.own_ips = {ip for ip, _ in self.ifaces}
        self.out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.out.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.wake = threading.Event()
        self.sweep_lock = threading.Lock()
        self.replied = {}
        self.pool = ThreadPoolExecutor(max_workers=48)
        self.probe_pool = ThreadPoolExecutor(max_workers=8)  # own pool: live checks must never queue behind a subnet scan
        self.gen = 0            # bump -> listen() re-creates its UDP socket
        self.tick = time.time()  # last beacon-loop heartbeat (detects phone sleep / unlock)

    def msg(self):
        return json.dumps({"app": "lanshare", "id": CFG["id"], "name": CFG["name"], "port": self.port,
                           "ips": sorted(self.own_ips)}).encode()

    def hello(self):
        return {"app": "lanshare", "id": CFG["id"], "name": CFG["name"], "port": self.port,
                "ips": sorted(self.own_ips)}

    def start(self):
        for fn in (self.listen, self.beacon, self.tcp_loop, self.live_loop):
            threading.Thread(target=fn, daemon=True).start()

    # ---- peer table
    def add(self, pid, ip, port, name, ips=None):
        now = time.time()
        with self.lock:
            p = self.peers.get(pid)
            new = p is None
            if new:
                p = self.peers[pid] = {"id": pid, "ip": ip, "port": int(port), "name": str(name)[:40],
                                       "seen": now, "ips": [], "ok": True}
            elif not p.get("ok", True):
                p["ip"] = ip  # last address failed - try the newest one
            p["port"], p["name"], p["seen"] = int(port), str(name)[:40], now
            allips = set(p["ips"]) | {ip}
            for i in (ips or []):
                try:
                    ipaddress.ip_address(i)
                    allips.add(i)
                except ValueError:
                    pass
            p["ips"] = sorted(allips - self.own_ips)[:8]
        return new

    def get(self, pid):
        with self.lock:
            return dict(self.peers[pid]) if pid in self.peers else None

    def list(self):
        now = time.time()
        with self.lock:
            for k in [k for k, v in self.peers.items() if now - v["seen"] > self.PEER_TTL]:
                del self.peers[k]
            return sorted((dict(v) for v in self.peers.values()), key=lambda v: v["name"].lower())

    def unicast(self, ip):
        try:
            self.out.sendto(self.msg(), (ip, BEACON_PORT))
        except OSError:
            pass

    # ---- UDP
    def listen(self):
        while True:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                try:
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                except OSError:
                    pass
            s.settimeout(3)
            gen = self.gen
            try:
                s.bind(("", BEACON_PORT))
            except OSError as e:
                print("! cannot listen for beacons (TCP scan still works):", e)
                s.close()
                time.sleep(10)
                continue
            while gen == self.gen:
                try:
                    data, (ip, _) = s.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    m = json.loads(data)
                    if m.get("app") != "lanshare" or m["id"] == CFG["id"] or ip in self.own_ips:
                        continue
                    new = self.add(m["id"], ip, m["port"], m["name"], m.get("ips"))
                    now = time.time()
                    if new or now - self.replied.get(ip, 0) > 6:
                        self.replied[ip] = now
                        self.unicast(ip)  # instant two-way discovery
                except Exception:
                    pass
            s.close()
            time.sleep(1)

    def beacon(self):
        n = 0
        while True:
            try:
                now = time.time()
                if now - self.tick > 8:  # we were frozen (screen lock / doze) -> network state is stale
                    self.resumed()
                self.tick = now
                if n % 5 == 0:
                    self.ifaces = get_ifaces()
                    self.own_ips = {ip for ip, _ in self.ifaces}
                self.announce()
                if n < 4 or n % 5 == 0:
                    self.sweep()
            except Exception:
                pass
            n += 1
            time.sleep(2)

    def resumed(self):
        """Called after the process was suspended: rebuild sockets/addresses and rescan a few times,
        because Wi-Fi often needs several seconds to come back after unlock."""
        self.gen += 1
        self.ifaces = get_ifaces()
        self.own_ips = {ip for ip, _ in self.ifaces}
        now = time.time()
        with self.lock:
            for p in self.peers.values():  # give live_loop a chance to re-verify instead of expiring them
                p["seen"] = now
                p["ok"] = False

        def rescan():
            for _ in range(6):
                try:
                    self.ifaces = get_ifaces()
                    self.own_ips = {ip for ip, _ in self.ifaces}
                    self.scan_now()
                except Exception:
                    pass
                time.sleep(3)
        threading.Thread(target=rescan, daemon=True).start()

    def announce(self):
        data = self.msg()
        sent = False
        for ip, net in self.ifaces:  # one socket per interface so the packet leaves on that interface
            for t in (str(net.broadcast_address), "255.255.255.255"):
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    try:
                        s.bind((ip, 0))
                    except OSError:
                        pass
                    s.sendto(data, (t, BEACON_PORT))
                    s.close()
                    sent = True
                except OSError:
                    pass
        if not sent:
            for t in {"255.255.255.255"} | {str(net.broadcast_address) for _, net in self.ifaces}:
                try:
                    self.out.sendto(data, (t, BEACON_PORT))
                except OSError:
                    pass

    def candidates(self):
        nets = [net for _, net in self.ifaces]
        hosts, seen = [], set(self.own_ips)

        def put(h):
            if h not in seen:
                seen.add(h)
                hosts.append(h)

        for ip in arp_neighbors():
            put(ip)
            try:
                n = ipaddress.ip_network(ip + "/24", strict=False)
                if n not in nets:
                    nets.append(n)
            except ValueError:
                pass
        for net in nets:
            for h in net.hosts():
                put(str(h))
        return hosts[:1100]

    def sweep(self):
        """UDP unicast to every host - works even when the router/hotspot drops broadcasts."""
        for h in self.candidates():
            self.unicast(h)

    # ---- TCP scan (most reliable path: plain HTTP hello on each host)
    def tcp_probe(self, ip):
        for port in range(BASE_PORT, BASE_PORT + 5):
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.7)
            try:
                src = src_for(ip)
                if src:
                    s.bind((src, 0))
                r = s.connect_ex((ip, port))
            except OSError:
                r = -1
            finally:
                s.close()
            if r == 0:
                try:
                    m = hello_url(ip, port, 2.5)
                    if m.get("app") == "lanshare" and m["id"] != CFG["id"]:
                        self.add(m["id"], ip, m["port"], m["name"], m.get("ips"))
                        return True
                except Exception:
                    pass
            elif r not in (errno.ECONNREFUSED, 10061):
                return False  # timeout / unreachable: nobody home
        return False

    def tcp_sweep(self):
        if not self.sweep_lock.acquire(False):
            return
        try:
            known = {p["ip"] for p in self.list()}
            hosts = [h for h in self.candidates() if h not in known]
            list(self.pool.map(self.tcp_probe, hosts))
        finally:
            self.sweep_lock.release()

    def tcp_loop(self):
        k = 0
        while True:
            try:
                self.tcp_sweep()
            except Exception:
                pass
            k += 1
            wait = 3 if k < 6 else (8 if not self.peers else 30)
            self.wake.wait(wait)
            self.wake.clear()

    def scan_now(self):
        self.announce()
        threading.Thread(target=self.sweep, daemon=True).start()
        self.wake.set()

    # ---- keep known peers alive / fix their address
    def probe(self, peer):
        cands = [peer["ip"]] + [i for i in peer.get("ips", []) if i != peer["ip"]]
        for ip in cands[:4]:
            try:
                m = hello_url(ip, peer["port"], 2.5)
                if m.get("id") == peer["id"]:
                    self.add(peer["id"], ip, m["port"], m["name"], m.get("ips"))
                    with self.lock:
                        p = self.peers.get(peer["id"])
                        if p:
                            p["ip"], p["ok"] = ip, True
                    return
            except Exception:
                continue
        with self.lock:
            p = self.peers.get(peer["id"])
            if p:
                p["ok"] = False

    def live_loop(self):
        while True:
            time.sleep(3)
            try:
                list(self.probe_pool.map(self.probe, self.list()))
            except Exception:
                pass

    def add_ip(self, ip):
        port0 = None
        if ip.count(":") == 1:
            ip, _, ps = ip.partition(":")
            port0 = int(ps)
        ipaddress.ip_address(ip)
        for port in ([port0] if port0 else range(BASE_PORT, BASE_PORT + 20)):
            try:
                m = hello_url(ip, port, 2.0)
                if m.get("app") == "lanshare" and m["id"] != CFG["id"]:
                    self.add(m["id"], ip, m["port"], m["name"], m.get("ips"))
                    self.unicast(ip)
                    return True
            except Exception:
                continue
        return False


# ----------------------------------------------------------------- jobs / clipboard
JOBS = {}
CLIP = {}


def start_job(src_id, paths, dst_id, ddir, cut, label):
    src, dst = ep(src_id), ep(dst_id)
    jid = uuid.uuid4().hex[:8]
    now = time.time()
    for k in [k for k, v in JOBS.items() if v["state"] != "run" and now - v.get("end", now) > 600]:
        del JOBS[k]
    job = JOBS[jid] = {"state": "run", "done": 0, "total": 1, "bytes": True, "error": None, "label": label}

    def bump(n):
        job["done"] += n

    def work():
        try:
            ddir_n = vnorm(ddir)
            same = src.id == dst.id
            plan = []
            for p in paths:
                p = vnorm(p)
                if p == "/":
                    raise PermissionError("cannot copy the root")
                if same and (ddir_n == p or ddir_n.startswith(p + "/")):
                    raise ValueError("cannot put a folder inside itself")
                if same and cut and posixpath.dirname(p) == ddir_n:
                    continue  # moving into the same folder: nothing to do
                plan.append((p, src.walk(p) if not (same and cut) else None))
            if same and cut:
                job["bytes"], job["total"] = False, max(len(plan), 1)
            else:
                job["total"] = max(sum(i["size"] for _, w in plan for i in w), 1)
            taken = dst.names(ddir_n)
            for p, walked in plan:
                name = posixpath.basename(p)
                is_dir = bool(walked and walked[0]["dir"]) if walked else False
                new = unique_name(name, taken, is_dir)
                taken.add(new)
                dest = vjoin(ddir_n, new)
                if same and cut:
                    src.move(p, dest)
                    job["done"] += 1
                    continue
                for it in walked:
                    target = dest if not it["rel"] else dest + "/" + it["rel"]
                    if it["dir"]:
                        dst.mkdir(target)
                        continue
                    sp = p if not it["rel"] else p + "/" + it["rel"]
                    for attempt in range(4):
                        sent = [0]

                        def bump2(n, sent=sent):
                            sent[0] += n
                            job["done"] += n

                        try:
                            f, size = src.open_read(sp)
                            try:
                                dst.write(target, f, size, bump2)
                            finally:
                                f.close()
                            break
                        except (PermissionError, FileNotFoundError, FileExistsError, ValueError):
                            raise
                        except (OSError, http.client.HTTPException) as e:
                            job["done"] -= sent[0]
                            if attempt == 3:
                                raise IOError(str(e))
                            time.sleep(1.5 * (attempt + 1))
                if cut:
                    src.remove(p)
            job["done"] = job["total"]
            job["state"] = "done"
            if cut and CLIP.get("paths") and CLIP.get("dev") == src_id:
                CLIP.clear()
        except Exception as e:
            job["error"] = (e.strerror if isinstance(e, OSError) and e.strerror else str(e)) or e.__class__.__name__
            job["state"] = "error"
        finally:
            job["end"] = time.time()

    threading.Thread(target=work, daemon=True).start()
    return jid


# ----------------------------------------------------------------- http handler
MIME_FIX = {".3gp": "video/3gpp", ".3g2": "video/3gpp2", ".mkv": "video/x-matroska", ".mov": "video/quicktime",
            ".m4v": "video/mp4", ".mp4": "video/mp4", ".webm": "video/webm", ".ts": "video/mp2t",
            ".heic": "image/heic", ".heif": "image/heif", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".png": "image/png", ".webp": "image/webp", ".gif": "image/gif", ".m4a": "audio/mp4"}


def mime_for(name):
    ext = posixpath.splitext(name)[1].lower()
    return MIME_FIX.get(ext) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def safe_inline(mt):
    return (mt.startswith(("image/", "video/", "audio/")) and "svg" not in mt) or mt in ("application/pdf", "text/plain")


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LANShare"
    timeout = 120

    def setup(self):
        BaseHTTPRequestHandler.setup(self)
        try:
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        except OSError:
            pass

    def log_message(self, *a):
        pass

    # helpers
    def ip(self):
        return self.client_address[0]

    def reply(self, code, data, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def json(self, obj, code=200):
        self.reply(code, json.dumps(obj).encode(), "application/json")

    def fail(self, code, e):
        self.close_connection = True
        try:
            msg = e.strerror if isinstance(e, OSError) and e.strerror else str(e)
            self.json({"error": msg or e.__class__.__name__}, code)
        except Exception:
            pass

    def body_json(self):
        raw = self._body if self._body is not None else self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self._body = b""
        return json.loads(raw or b"{}")

    def send_file(self, opened, name, inline):
        f, size = opened
        try:
            start, end, code = 0, size - 1, 200
            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes=") and hasattr(f, "seek") and size:
                a, _, b = rng[6:].split(",")[0].partition("-")
                try:
                    if a == "":
                        start = max(0, size - int(b))
                    else:
                        start, end = int(a), min(int(b) if b else size - 1, size - 1)
                    if 0 <= start <= end:
                        code = 206
                    else:
                        start, end = 0, size - 1
                except ValueError:
                    start, end = 0, size - 1
            length = max(end - start + 1, 0)
            mt = mime_for(name)
            disp = "inline" if inline and safe_inline(mt) else "attachment"
            self.send_response(code)
            self.send_header("Content-Type", mt)
            self.send_header("Content-Length", str(length))
            self.send_header("Content-Disposition", "%s; filename*=UTF-8''%s" % (disp, urllib.parse.quote(name)))
            if not mt.startswith(("video/", "audio/")):
                self.send_header("Content-Security-Policy", "sandbox")
            self.send_header("X-Content-Type-Options", "nosniff")
            if hasattr(f, "seek"):
                self.send_header("Accept-Ranges", "bytes")
            if code == 206:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.end_headers()
            if start:
                f.seek(start)
            left = length
            while left > 0:
                b = f.read(min(CHUNK, left))
                if not b:
                    break
                self.wfile.write(b)
                left -= len(b)
            if left:
                self.close_connection = True
        finally:
            f.close()

    # routing
    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method):
        u = urllib.parse.urlparse(self.path)
        # Always consume the request body for local UI calls, otherwise unread bytes
        # (e.g. the "{}" of POST /api/scan) get glued onto the next keep-alive request.
        self._body = None
        if not u.path.startswith("/p/"):
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                n = 0
            self._body = self.rfile.read(n) if n > 0 else b""
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            if u.path == "/p/hello":
                return self.json(DISC.hello())
            if u.path.startswith("/p/"):
                return self.peer(u.path[3:], q)
            if u.path == "/":
                return self.reply(200, PAGE.encode(), "text/html; charset=utf-8")
            if u.path.startswith("/api/"):
                return self.api(u.path[5:], q)
            self.reply(404, b"not found", "text/plain")
        except FileNotFoundError as e:
            self.fail(404, e)
        except PermissionError as e:
            self.fail(403, e)
        except FileExistsError as e:
            self.fail(409, e)
        except (ValueError, KeyError) as e:
            self.fail(400, e)
        except Exception as e:
            self.fail(500, e)

    # ---- what other devices call
    def peer(self, route, q):
        L = LOCAL
        if route == "ping":
            self.json({"ok": True})
        elif route == "ls":
            self.json(L.ls(q["path"]))
        elif route == "walk":
            self.json(L.walk(q["path"]))
        elif route == "search":
            self.json(L.search(q["path"], q["q"]))
        elif route == "file":
            self.send_file(L.open_read(q["path"]), posixpath.basename(q["path"]), False)
        elif route == "put":
            L.write(q["path"], self.rfile, int(self.headers["Content-Length"]))
            self.json({"ok": True})
        elif route == "mkdir":
            L.mkdir(q["path"])
            self.json({"ok": True})
        elif route == "rm":
            L.remove(q["path"])
            self.json({"ok": True})
        elif route == "rename":
            L.rename(q["path"], q["name"])
            self.json({"ok": True})
        elif route == "mv":
            L.move(q["path"], q["to"])
            self.json({"ok": True})
        else:
            self.fail(404, "unknown route")

    # ---- what the local UI calls
    def api(self, route, q):
        if route == "info":
            return self.json({"id": CFG["id"], "name": CFG["name"], "ips": sorted(DISC.own_ips),
                              "port": DISC.port, "root": LOCAL.root, "storage_ok": STORAGE.get("ok")})
        if route == "peers":
            return self.json([{"id": p["id"], "name": p["name"], "ip": p["ip"], "ok": p.get("ok", True)} for p in DISC.list()])
        if route == "diag":
            return self.json({"me": CFG["name"], "id": CFG["id"], "port": DISC.port, "ifaces": [[i, str(n)] for i, n in DISC.ifaces],
                              "peers": DISC.list()})
        if route == "scan":
            DISC.scan_now()
            return self.json({"ok": True})
        if route == "ls":
            items = ep(q["dev"]).ls(vnorm(q.get("path", "/")))
            items.sort(key=lambda i: (not i["dir"], i["name"].lower()))
            used = None
            if q["dev"] == "local":  # share of main storage in use, for the "70% USED" pill
                try:
                    du = shutil.disk_usage(LOCAL.root)
                    used = round((du.total - du.free) * 100 / du.total) if du.total else None
                except Exception:
                    pass
            return self.json({"path": vnorm(q.get("path", "/")), "items": items, "used": used})
        if route == "search":
            return self.json(ep(q["dev"]).search(vnorm(q.get("path", "/")), q["q"]))
        if route == "dl":
            e = ep(q["dev"])
            return self.send_file(e.open_read(vnorm(q["path"])), posixpath.basename(vnorm(q["path"])), q.get("dl") != "1")
        if route == "job":
            if q["id"] not in JOBS:
                raise FileNotFoundError("unknown job")
            return self.json(JOBS[q["id"]])
        if route == "clip" and self.command == "GET":
            return self.json(CLIP or None)
        b = self.body_json()
        if route == "clip":
            CLIP.clear()
            if b.get("op") in ("copy", "cut") and b.get("paths"):
                CLIP.update({"op": b["op"], "dev": b["dev"], "paths": [vnorm(p) for p in b["paths"]]})
            return self.json(CLIP or None)
        if route == "paste":
            if not CLIP:
                raise ValueError("clipboard is empty")
            cut = CLIP["op"] == "cut"
            return self.json({"job": start_job(CLIP["dev"], CLIP["paths"], b["dev"], b["dir"], cut, "Moving" if cut else "Copying")})
        if route == "send":
            return self.json({"job": start_job(b["dev"], b["paths"], b["to"], INBOX, False, "Sending")})
        if route == "addip":
            if not DISC.add_ip(str(b["ip"]).strip()):
                raise IOError("no LANShare device found at that address")
            return self.json({"ok": True})
        if route == "name":
            CFG["name"] = str(b["name"]).strip()[:40] or CFG["name"]
            CFG["name_custom"] = True
            save_cfg()
            DISC.announce()
            return self.json({"name": CFG["name"]})
        if route == "op":
            e, op = ep(b["dev"]), b["op"]
            if op == "mkdir":
                e.mkdir(vnorm(b["path"]))
            elif op == "rm":
                for p in b["paths"]:
                    e.remove(vnorm(p))
            elif op == "rename":
                e.rename(vnorm(b["path"]), b["name"])
            else:
                raise ValueError("unknown op")
            return self.json({"ok": True})
        self.fail(404, "unknown api")


# ----------------------------------------------------------------- UI
PAGE = r"""<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name=theme-color content="#1c1c1e">
<title>LANShare</title>
<style>
:root{--bg:#f4f4f4;--fg:#1b1b1b;--card:#f9f9f9;--cont:#ececec;--ac:#1a6fd1;--onac:#fff;--mut:#6b6b6b;--bd:#d3d3d3;--sel:#cfe0f7;--onsel:#0a2a55;--hov:#0000000f;--errc:#f9dedc;--onerr:#410e0b;--err:#b3261e;--ok:#1e8e3e;--warn:#f9ab00;--sh:0 1px 3px #0000004d,0 4px 8px 3px #00000026;--bar:#1c1c1e;--onbar:#fff;--barmut:#b4b4b8;
--k-folder-c:#8c5d00;--k-folder-b:#ffdf9e;--k-img-c:#146c2e;--k-img-b:#c4eed0;--k-vid-c:#7627a8;--k-vid-b:#f0dbff;--k-aud-c:#b3126b;--k-aud-b:#ffd8ea;--k-pdf-c:#b3261e;--k-pdf-b:#f9dedc;--k-zip-c:#5d4037;--k-zip-b:#ebdbd0;--k-apk-c:#00695c;--k-apk-b:#c2f0e8;--k-doc-c:#0b57d0;--k-doc-b:#d3e3fd;--k-file-c:#444746;--k-file-b:#e1e3e1;--tl:#00897b}
:root[data-theme=dark]{--bg:#121212;--fg:#e6e6e6;--card:#1a1a1a;--cont:#242424;--ac:#8ab4f8;--onac:#0b2a5b;--mut:#9a9a9a;--bd:#333;--sel:#233b5e;--onsel:#d6e4fb;--hov:#ffffff14;--errc:#8c1d18;--onerr:#f9dedc;--err:#f2b8b5;--ok:#81c995;--warn:#fdd663;--sh:0 1px 3px #000a,0 4px 8px 3px #0006;
--k-folder-c:#ffdf9e;--k-folder-b:#5c4300;--k-img-c:#c4eed0;--k-img-b:#0f5223;--k-vid-c:#f0dbff;--k-vid-b:#5b1e82;--k-aud-c:#ffd8ea;--k-aud-b:#7a0f49;--k-pdf-c:#f9dedc;--k-pdf-b:#8c1d18;--k-zip-c:#ebdbd0;--k-zip-b:#4e342e;--k-apk-c:#c2f0e8;--k-apk-b:#00504a;--k-doc-c:#d3e3fd;--k-doc-b:#0842a0;--k-file-c:#e1e3e1;--k-file-b:#444746;--tl:#4db6ac;color-scheme:dark}
@media(prefers-color-scheme:dark){:root[data-theme=auto]{--bg:#121212;--fg:#e6e6e6;--card:#1a1a1a;--cont:#242424;--ac:#8ab4f8;--onac:#0b2a5b;--mut:#9a9a9a;--bd:#333;--sel:#233b5e;--onsel:#d6e4fb;--hov:#ffffff14;--errc:#8c1d18;--onerr:#f9dedc;--err:#f2b8b5;--ok:#81c995;--warn:#fdd663;--sh:0 1px 3px #000a,0 4px 8px 3px #0006;
--k-folder-c:#ffdf9e;--k-folder-b:#5c4300;--k-img-c:#c4eed0;--k-img-b:#0f5223;--k-vid-c:#f0dbff;--k-vid-b:#5b1e82;--k-aud-c:#ffd8ea;--k-aud-b:#7a0f49;--k-pdf-c:#f9dedc;--k-pdf-b:#8c1d18;--k-zip-c:#ebdbd0;--k-zip-b:#4e342e;--k-apk-c:#c2f0e8;--k-apk-b:#00504a;--k-doc-c:#d3e3fd;--k-doc-b:#0842a0;--k-file-c:#e1e3e1;--k-file-b:#444746;--tl:#4db6ac;color-scheme:dark}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
svg{width:24px;height:24px;fill:currentColor;flex:none;display:block}
body{margin:0;font:15px/1.4 Roboto,system-ui,sans-serif;background:var(--bg);color:var(--fg);padding-bottom:calc(var(--dockh,0px) + 110px);overscroll-behavior-y:contain}
/* pull to refresh */
#ptr{position:fixed;left:0;right:0;top:0;height:0;display:flex;align-items:flex-end;justify-content:center;overflow:hidden;z-index:4;font-size:13px;pointer-events:none}
#ptr div{margin:0 0 8px;padding:6px 14px;border-radius:16px;background:var(--cont);color:var(--mut);box-shadow:var(--sh)}#ptr.go div{color:var(--ac);font-weight:600}
/* top app bar */
#top{position:sticky;top:0;z-index:5;background:var(--bg);transition:box-shadow .2s}
#top:before{content:"";display:block;height:env(safe-area-inset-top);background:var(--bar)}
#top.el{box-shadow:0 2px 6px #0004}
#pathrow{display:flex;align-items:center;gap:8px;padding:2px 8px 2px 4px;background:var(--card);border-bottom:1px solid var(--bd)}
.pill{display:none;align-items:center;gap:6px;flex:none;height:28px;padding:0 9px;border:1px solid var(--mut);border-radius:6px;font-weight:600;font-size:12px;letter-spacing:.2px;text-transform:uppercase}
.pill i{width:14px;height:14px;border-radius:50%;background:conic-gradient(var(--fg) var(--p,0%),var(--bd) 0)}
.bar{display:flex;align-items:center;gap:0;height:44px;padding:0 2px 0 14px;background:var(--bar);color:var(--onbar)}
.selb{display:none;padding-left:4px;background:#243a5e}
body.selm .mainb{display:none}body.selm .selb{display:flex}
.ttl{flex:1;min-width:0;display:flex;align-items:center;gap:10px}.bar .ibtn{width:40px;height:40px}
.ttl h1{flex:0 1 auto;min-width:0;font-size:18px;line-height:24px;font-weight:500;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#nm{display:flex;align-items:center;gap:4px;color:var(--barmut);font-size:12px;line-height:16px;flex:0 1 auto;min-width:0;max-width:50%}
#nm svg{width:12px;height:12px}#nmt{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ibtn{width:48px;height:48px;border-radius:50%;display:grid;place-items:center;flex:none;transition:background .15s}
.ibtn:active,.ibtn.spin:active{background:var(--hov)}
.ibtn.spin svg{animation:rot .8s linear}
@keyframes rot{to{transform:rotate(360deg)}}
.selb h2{flex:1;font-size:20px;font-weight:500;margin:0 0 0 4px}
/* device chips */
#peers{display:flex;gap:8px;overflow-x:auto;padding:5px 10px;scrollbar-width:none;background:var(--bg);border-bottom:1px solid var(--bd)}#peers::-webkit-scrollbar{display:none}
.chip{flex:none;height:28px;display:flex;align-items:center;gap:8px;border:1px solid var(--bd);background:var(--card);border-radius:4px;padding:0 10px 0 8px;font-size:13px;font-weight:500;transition:background .15s}
.chip:active{background:var(--hov)}
.chip svg{width:18px;height:18px}
.chip.on{background:var(--sel);color:var(--onsel);border-color:transparent}
.chip.add{color:var(--ac);border-style:dashed}
.dot{width:10px;height:10px;border-radius:50%;background:var(--ok);margin:0 3px}.dot.wn{background:var(--warn)}
#hint{padding:3px 14px;color:var(--mut);font-size:12px;background:var(--bg);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}#hint:empty{display:none}
/* breadcrumbs */
#crumbs{flex:1;min-width:0;display:flex;align-items:center;gap:0;overflow-x:auto;white-space:nowrap;scrollbar-width:none;color:var(--mut)}#crumbs::-webkit-scrollbar{display:none}
.crumb{flex:none;display:flex;align-items:center;gap:6px;height:34px;padding:0 5px;border-radius:6px;font-size:14px;color:var(--mut);transition:background .15s}
.crumb:active{background:var(--hov)}.crumb svg{width:18px;height:18px}.crumb svg.cico{width:24px;height:24px}
.crumb.cur{color:var(--fg);font-weight:600}
#crumbs>svg{width:18px;height:18px;opacity:.6}
/* sort / view toolbar */
#toolrow{display:flex;align-items:center;gap:4px;min-height:36px;padding:0 2px 0 14px;background:var(--card);border-bottom:1px solid var(--bd)}
#sum{flex:1;min-width:0;line-height:1.25;color:var(--mut);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#sum b{font-size:13px;font-weight:500;color:var(--fg)}
#sum small{font-size:12px}#sum small:before{content:' \00b7  '}
#sortb{display:flex;align-items:center;gap:6px;flex:none;height:32px;padding:0 8px;border-radius:16px;font-size:13px;font-weight:500;color:var(--ac)}#sortb:active{background:var(--hov)}
#sortb svg{width:18px;height:18px}#sortb svg.ar{width:14px;height:14px}
#srow{display:none;align-items:center;height:48px;padding:0 4px;background:var(--card);border-bottom:1px solid var(--bd)}body.srch #srow{display:flex}
#srow .ibtn{width:40px;height:40px}#sq{flex:1;min-width:0;height:40px;border:0;outline:0;background:transparent;color:var(--fg);font:inherit;font-size:16px}
.sh{color:var(--tl);font-weight:500;font-size:16px;padding:14px 4px 6px}
#sheet .card.vs{--ac:var(--tl);border-radius:0;padding:0 12px calc(8px + env(safe-area-inset-bottom));gap:0;background:var(--card);max-height:90vh}
.vrow{display:flex;align-items:center;gap:16px;min-height:56px;padding:6px 4px;width:100%;text-align:left}.vrow .vt{flex:1;font-size:18px}
.cbx{width:24px;height:24px;margin:0;accent-color:var(--tl);flex:none}
.vrad{display:flex;padding:6px 0}.rdo{flex:1;display:flex;align-items:center;gap:14px;height:56px;padding:0 4px}
.rdo .rd{width:22px;height:22px;border-radius:50%;border:2px solid var(--mut);display:grid;place-items:center;flex:none}
.rdo.on .rd{border-color:var(--tl)}.rdo.on .rd:after{content:'';width:12px;height:12px;border-radius:50%;background:var(--tl)}
#dlg .dcard.sd{--ac:var(--tl);max-width:360px;padding:24px 0 8px}.sd h3{padding:0 24px;font-weight:500}
.sd .opt{border-radius:0;padding:6px 24px;min-height:54px}.sd .opt .t b{font-size:20px}.sd .opt.on .t b{color:inherit;font-weight:400}
.sd .tbtn{color:var(--tl);text-transform:uppercase;letter-spacing:1px;margin:8px 16px 0 0}
#list.t-lg:not(.v-grid):not(.v-compact) .row{min-height:84px}
#list.t-lg:not(.v-grid):not(.v-compact) .lead{width:76px;height:68px}
#list.t-lg:not(.v-grid):not(.v-compact) .row .lead .kd{width:64px;height:64px;padding:14px}
#list.t-lg:not(.v-grid):not(.v-compact) .fold svg{width:68px;height:58px}
.opt{display:flex;align-items:center;gap:16px;min-height:52px;padding:8px;width:100%;border-radius:12px;text-align:left}.opt:active{background:var(--hov)}
.opt .rd{width:20px;height:20px;border-radius:50%;border:2px solid var(--mut);flex:none;display:grid;place-items:center}
.opt.on .rd{border-color:var(--ac)}.opt.on .rd:after{content:'';width:10px;height:10px;border-radius:50%;background:var(--ac)}
.opt .t{flex:1}.opt .t b{display:block;font-weight:400;font-size:16px}.opt .t small{color:var(--mut);font-size:13px}.opt.on .t b{color:var(--ac);font-weight:500}
#sheet hr{border:0;border-top:1px solid var(--bd);margin:8px 0;width:100%}
/* view: compact */
#list.v-compact .row{min-height:44px;padding:2px 14px 2px 8px;gap:10px}
#list.v-compact .lead{width:36px;height:36px}
#list.v-compact .fold svg{width:34px;height:29px}
#list.v-compact .row .lead .kd,#list.v-compact .lead .ck{width:30px;height:30px;padding:5px}
#list.v-compact .bdg{width:15px;height:15px;border-radius:4px;bottom:-2px}#list.v-compact .bdg svg{width:11px;height:11px}
#list.v-compact .nm{display:flex;align-items:center;gap:10px}
#list.v-compact .nm b{flex:1;min-width:0;font-size:15px}
#list.v-compact .nm small{flex:none;font-size:12px}
/* view: grid */
#list.v-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:8px;align-items:start}
@media(min-width:600px){#list.v-grid{grid-template-columns:repeat(5,1fr)}}
#list.v-grid #empty,#list.v-grid .gal{grid-column:1/-1}
#list.v-grid .row{flex-direction:column;justify-content:flex-start;gap:6px;min-height:0;padding:12px 6px 8px;border:1px solid var(--bd);border-radius:12px;background:var(--bg);text-align:center}
#list.v-grid .row.sel{background:var(--sel);color:var(--onsel)}
#list.v-grid .lead{width:64px;height:56px}
#list.v-grid .fold svg{width:64px;height:55px}
#list.v-grid .row .lead .kd{width:48px;height:48px;padding:10px}
#list.v-grid .nm{width:100%;flex:none}
#list.v-grid .nm b{font-size:13px;line-height:1.25;white-space:normal;word-break:break-word;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}
#list.v-grid .nm small{flex-direction:column;align-items:center;gap:0;margin-top:2px;font-size:12px}
/* banner */
#banner{display:none;margin:6px 16px;padding:12px 16px;border-radius:16px;background:var(--errc);color:var(--onerr);font-size:13px}
/* list */
#list{padding:0;background:var(--card)}
.row{display:flex;align-items:center;gap:14px;min-height:66px;padding:6px 14px 6px 10px;border-bottom:1px solid var(--bd);cursor:pointer;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;transition:background .15s}
.row:active{background:var(--hov)}
.row.sel{background:var(--sel);color:var(--onsel)}
.lead{position:relative;width:56px;height:50px;display:grid;place-items:center;flex:none}
.row .lead .kd{width:40px;height:40px;padding:8px;border-radius:8px;background:var(--b);color:var(--c)}
.fold svg{width:56px;height:48px;fill:none}
.bdg{position:absolute;left:50%;bottom:-1px;transform:translateX(-50%);width:24px;height:24px;border-radius:5px;background:#fff;display:grid;place-items:center;box-shadow:0 0 0 1px #0003}.bdg svg{width:16px;height:16px}
.lead .ck{display:none;width:40px;height:40px;padding:8px;border-radius:50%;background:var(--ac);color:var(--onac)}
.row.sel .lead .ck{display:block}.row.sel .lead .kd,.row.sel .lead .fold,.row.sel .lead .bdg{display:none}
.k-folder{--c:var(--k-folder-c);--b:var(--k-folder-b)}.k-img{--c:var(--k-img-c);--b:var(--k-img-b)}.k-vid{--c:var(--k-vid-c);--b:var(--k-vid-b)}.k-aud{--c:var(--k-aud-c);--b:var(--k-aud-b)}.k-pdf{--c:var(--k-pdf-c);--b:var(--k-pdf-b)}.k-zip{--c:var(--k-zip-c);--b:var(--k-zip-b)}.k-apk{--c:var(--k-apk-c);--b:var(--k-apk-b)}.k-doc{--c:var(--k-doc-c);--b:var(--k-doc-b)}.k-file{--c:var(--k-file-c);--b:var(--k-file-b)}
.nm{flex:1;min-width:0}.nm b{display:block;font-size:18px;font-weight:400;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.nm small{display:flex;justify-content:space-between;gap:12px;color:var(--mut);font-size:14px}.row.sel .nm small{color:inherit;opacity:.8}
#empty{padding:56px 24px;text-align:center;color:var(--mut)}
#empty svg{width:72px;height:72px;margin:0 auto 12px;padding:18px;border-radius:50%;background:var(--cont)}#empty p{margin:0}
/* video gallery */
.gal{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;padding:4px;border-bottom:1px solid var(--bd)}
@media(min-width:600px){.gal{grid-template-columns:repeat(5,1fr)}}
.vc{position:relative;aspect-ratio:1/1;border-radius:8px;overflow:hidden;background:var(--k-vid-b);cursor:pointer;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
.vc img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.vc .ph{position:absolute;inset:0;display:grid;place-items:center;color:var(--k-vid-c)}.vc .ph svg{width:36px;height:36px;opacity:.7}
.vc .pl{position:absolute;left:6px;bottom:6px;width:22px;height:22px;border-radius:50%;background:#0009;color:#fff;display:grid;place-items:center}.vc .pl svg{width:16px;height:16px}
.vc .du{position:absolute;right:6px;bottom:6px;padding:1px 6px;border-radius:10px;background:#0009;color:#fff;font-size:11px;font-weight:500}
.vc .vn{position:absolute;left:0;right:0;top:0;padding:14px 6px 4px;background:linear-gradient(#0009,#0000);color:#fff;font-size:11px;line-height:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.vc .ck{display:none;position:absolute;top:6px;right:6px;width:26px;height:26px;padding:4px;border-radius:50%;background:var(--ac);color:var(--onac)}
.vc.sel{outline:3px solid var(--ac);outline-offset:-3px}.vc.sel .ck{display:block}.vc.sel img{opacity:.75}
.vc:active{filter:brightness(.85)}
/* player */
#pv{display:none;position:fixed;inset:0;z-index:20;background:#000;flex-direction:column}
#pv .ph2{display:flex;align-items:center;gap:4px;padding:calc(6px + env(safe-area-inset-top)) 4px 6px;color:#fff;background:#000c}
#pv .ph2 b{flex:1;min-width:0;font-weight:400;font-size:16px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#pv video{flex:1;min-height:0;width:100%;background:#000}
#pv .pimg{flex:1;min-height:0;width:100%;object-fit:contain;background:#000}
.lead .thi{position:absolute;inset:0;margin:auto;width:calc(100% - 8px);height:calc(100% - 6px);object-fit:cover;border-radius:8px}
.row.sel .lead .thi{display:none}
#pv .pe{display:none;position:absolute;left:16px;right:16px;bottom:calc(24px + env(safe-area-inset-bottom));padding:14px 16px;border-radius:16px;background:var(--errc);color:var(--onerr);font-size:14px}
#pv .pe .tbtn{margin-top:8px;color:inherit;border:1px solid currentColor}
/* FAB */
#fab{position:fixed;right:16px;bottom:calc(var(--dockh,0px) + 16px + env(safe-area-inset-bottom));z-index:7;height:56px;padding:0 20px 0 16px;border-radius:12px;display:flex;align-items:center;gap:12px;background:#e2ac5f;color:#3a2600;font-weight:500;font-size:15px;box-shadow:var(--sh);transition:transform .15s,opacity .15s}
#fab:active{transform:scale(.96)}body.selm #fab{display:none}
/* bottom bar */
#dock{touch-action:manipulation;position:fixed;left:0;right:0;bottom:0;z-index:6;background:var(--cont);border-top:1px solid var(--bd);box-shadow:0 -2px 8px #0002;padding:8px 4px calc(8px + env(safe-area-inset-bottom))}
#bar{display:flex;gap:0;overflow-x:auto;scrollbar-width:none}#bar::-webkit-scrollbar{display:none}#bar:empty{display:none}
.ib{touch-action:manipulation;flex:1 0 50px;min-width:0;display:flex;flex-direction:column;align-items:center;gap:4px;padding:4px 0;font-size:11px;font-weight:500}
.ib .ii{width:48px;height:30px;border-radius:15px;display:grid;place-items:center;transition:background .15s}
.ib:active .ii{background:var(--hov)}
.ib.pri .ii{background:var(--ac);color:var(--onac)}.ib.dng{color:var(--err)}
#clip{display:none;align-items:center;gap:8px;margin:0 4px 8px;padding:6px 6px 6px 16px;border-radius:16px;background:var(--sel);color:var(--onsel);font-size:14px}#clip span{flex:1;min-width:0}
.tbtn{height:36px;padding:0 16px;border-radius:18px;font-weight:500;font-size:14px;color:var(--ac)}.tbtn:active{background:var(--hov)}
.tbtn.fill{background:var(--ac);color:var(--onac)}.tbtn.dng{color:var(--err)}
#clip .ibtn{width:36px;height:36px}
/* snackbar */
#toast{display:none;position:fixed;left:16px;right:16px;bottom:calc(var(--dockh,0px) + 16px + env(safe-area-inset-bottom));background:#2e3133;color:#f0f0f0;border-radius:12px;padding:14px 16px;z-index:12;font-size:14px;box-shadow:var(--sh)}
#dlw{position:fixed;left:16px;right:16px;bottom:calc(var(--dockh,0px) + 84px + env(safe-area-inset-bottom));z-index:12;display:flex;flex-direction:column;gap:8px}
.dlc{background:#2e3133;color:#f0f0f0;border-radius:12px;padding:12px 14px;font-size:13px;box-shadow:var(--sh)}
.dlc .dh{display:flex;align-items:center;gap:10px}.dlc b{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500;font-size:14px}
.dlc button{background:none;border:0;color:#a8c7fa;font-size:13px;padding:4px 8px}
.dlc .bar{height:4px;border-radius:2px;background:#ffffff33;margin:8px 0 6px;overflow:hidden}.dlc .bar i{display:block;height:100%;width:0;background:#a8c7fa;transition:width .3s}
.dlc.ind .bar i{width:35%;animation:dli 1.1s linear infinite}@keyframes dli{from{margin-left:-35%}to{margin-left:100%}}
.dlc small{opacity:.8;font-size:12px}
#tp{display:none;height:4px;border-radius:2px;background:#ffffff33;margin-top:10px;overflow:hidden}#tp i{display:block;height:100%;width:0;background:#a8c7fa;transition:width .3s}
/* bottom sheet + dialog */
#sheet,#dlg{display:none;position:fixed;inset:0;background:#0000006b;z-index:10;animation:fade .2s}
#sheet{align-items:flex-end}#dlg{align-items:center;justify-content:center;padding:24px;z-index:11}
@keyframes fade{from{opacity:0}}@keyframes up{from{transform:translateY(40px);opacity:.4}}
#sheet .card{background:var(--cont);width:100%;max-height:85vh;overflow:auto;padding:8px 16px calc(16px + env(safe-area-inset-bottom));border-radius:16px 16px 0 0;display:flex;flex-direction:column;gap:4px;animation:up .22s ease-out}
.handle{width:32px;height:4px;border-radius:2px;background:var(--mut);opacity:.5;margin:4px auto 12px}
#sheet h3,.dcard h3{margin:0 0 8px;font-size:22px;font-weight:400}
.li{display:flex;align-items:center;gap:16px;min-height:56px;padding:8px;border-radius:16px;text-align:left;width:100%}.li:active{background:var(--hov)}
.li .lead{width:40px;height:40px;border-radius:50%;background:var(--sel);color:var(--onsel)}
.set{display:flex;align-items:center;gap:16px;padding:12px 8px;min-height:64px}
.set .t{flex:1}.set .t b{display:block;font-weight:400;font-size:16px}.set .t small{color:var(--mut);font-size:13px}
.sw{position:relative;width:52px;height:32px;flex:none}
.sw input{position:absolute;inset:0;opacity:0;margin:0;width:100%;height:100%;z-index:1}
.sw i{position:absolute;inset:0;border-radius:16px;border:2px solid var(--mut);background:var(--cont);transition:.2s}
.sw i:after{content:'';position:absolute;left:4px;top:6px;width:16px;height:16px;border-radius:50%;background:var(--mut);transition:.2s}
.sw input:checked+i{background:var(--ac);border-color:var(--ac)}
.sw input:checked+i:after{left:20px;top:2px;width:24px;height:24px;background:var(--onac)}
.dcard{background:var(--cont);width:100%;max-width:420px;border-radius:12px;padding:24px 24px 16px;animation:up .2s ease-out}
.dcard p{margin:0 0 16px;color:var(--mut)}
.tf{width:100%;height:56px;border:1px solid var(--mut);border-radius:6px;background:transparent;color:var(--fg);font:inherit;font-size:16px;padding:0 16px;margin-bottom:16px;outline:0}.tf:focus{border:2px solid var(--ac);padding:0 15px}
.dact{display:flex;justify-content:flex-end;gap:8px}
</style></head><body>
<header id=top>
  <div class="bar mainb"><div class=ttl><h1 id=ht>Main storage</h1><button id=nm><span id=nmt></span><span data-i=edit></span></button></div>
    <button class=ibtn id=srch aria-label=Search data-i=search></button><button class=ibtn id=tune aria-label="View options" data-i=tune></button><button class=ibtn id=scan aria-label=Refresh data-i=refresh></button><button class=ibtn id=cog aria-label=Settings data-i=settings></button></div>
  <div class="bar selb"><button class=ibtn id=xsel aria-label=Cancel data-i=close></button><h2 id=selcount></h2><button class=ibtn id=allsel aria-label="Select all" data-i=selall></button></div>
  <div id=srow><button class=ibtn id=sback aria-label=Back data-i=back></button><input id=sq type=search placeholder="Search in this folder" autocomplete=off><button class=ibtn id=sclr aria-label=Clear data-i=close></button></div>
  <div id=peers></div><div id=hint></div>
  <div id=pathrow><div id=crumbs></div><div class=pill id=used><i></i><span></span></div></div>
  <div id=toolrow><div id=sum></div><button id=sortb aria-label="Sort"></button></div>
</header>
<div id=banner></div><div id=list></div>
<button id=fab><span data-i=newfolder></span>New folder</button>
<div id=dock><div id=clip></div><div id=bar></div></div>
<div id=ptr><div></div></div><div id=toast><span id=tx></span><div id=tp><i></i></div></div><div id=dlw></div><div id=pv></div><div id=sheet></div><div id=dlg></div>
<script>
const $=s=>document.querySelector(s);
const E=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e};
const IC={
folder:'M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
file:'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z',
doc:'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
img:'M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z',
vid:'M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z',
aud:'M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z',
pdf:'M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z',
zip:'M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z',
apk:'M17.6 9.48l1.84-3.18c.16-.31.04-.69-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z',
refresh:'M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
settings:'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 00.12-.61l-1.92-3.32a.488.488 0 00-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 00-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 00-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
copy:'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
cut:'M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm6-7.5c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5zM19 3l-6 6 2 2 7-7V3z',
paste:'M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z',
download:'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
edit:'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 000-1.41l-2.34-2.34a.996.996 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
send:'M2.01 21L23 12 2.01 3 2 10l15 2-15 2z',openw:'M19 19H5V5h7V3H5a2 2 0 00-2 2v14a2 2 0 002 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
del:'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
close:'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
play:'M8 5v14l11-7z',
check:'M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z',
phone:'M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zM17 19H7V5h10v14z',
newfolder:'M20 6h-8l-2-2H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-1 8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z',
add:'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
chev:'M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
wifi:'M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z',
camera:'M12 15.2a3.2 3.2 0 100-6.4 3.2 3.2 0 000 6.4zM9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9z',
selall:'M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z',
search:'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
tune:'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
back:'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
sort:'M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z',
up:'M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z',
down:'M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z',
vlist:'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
vcomp:'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z',
vgrid:'M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z'};
const FOLD='<svg viewBox="0 0 56 48"><path d="M2 8a4 4 0 014-4h14l4 4h26a4 4 0 014 4v28a4 4 0 01-4 4H6a4 4 0 01-4-4z" fill="#d49b45"/><rect x="6" y="9" width="44" height="5" rx="1" fill="#f7f2ea"/><rect x="6" y="13" width="44" height="3" fill="#dcd3c3"/><path d="M2 21a4 4 0 014-4h44a4 4 0 014 4v19a4 4 0 01-4 4H6a4 4 0 01-4-4z" fill="#e2ac5f"/></svg>';
const HOME='<svg viewBox="0 0 32 32" class="cico"><path d="M6 17l10-8.5L26 17v11H6z" fill="#f5f5f5" stroke="#b5b5b5"/><path d="M2 16L16 4l14 12-2 2.2L16 8 4 18.2z" fill="#e53935"/><path d="M13 20h6v8h-6z" fill="#3d8fd6"/></svg>';
const DRIVE='<svg viewBox="0 0 32 32" class="cico"><path d="M9 4h14l4 15v7a2 2 0 01-2 2H7a2 2 0 01-2-2v-7z" fill="#c9c9c9"/><path d="M5 19h22v7a2 2 0 01-2 2H7a2 2 0 01-2-2z" fill="#b2b2b2"/><circle cx="9.5" cy="24" r="1.4" fill="#4caf50"/></svg>';
const raw=(h,c)=>{const e=document.createElement('span');e.className=c||'';e.innerHTML=h;return e.firstChild.nodeType===1&&!c?e.firstChild:e};
const BADGE={dcim:['camera','#333'],download:['download','#2f9bd8'],downloads:['download','#2f9bd8'],movies:['vid','#b3261e'],music:['aud','#0f8a6d'],pictures:['img','#2e7d32'],documents:['doc','#1a6fd1']};
const ic=(k,cls)=>{const NS='http://www.w3.org/2000/svg',s=document.createElementNS(NS,'svg'),p=document.createElementNS(NS,'path');
  s.setAttribute('viewBox','0 0 24 24');if(cls)s.setAttribute('class',cls);p.setAttribute('d',IC[k]);s.append(p);return s};
document.querySelectorAll('[data-i]').forEach(e=>e.append(ic(e.dataset.i)));
const LD=(k,d)=>{try{return localStorage.getItem(k)||d}catch(e){return d}};
const SV=(k,v)=>{try{localStorage.setItem(k,v)}catch(e){}};
const S={dev:'local',path:'/',sel:new Set(),peers:[],items:[],clip:null,sig:'',miss:0,ips:'',known:{},err:'',down:false,lost:false,back:false,fails:0,hid:(()=>{try{return localStorage.getItem('ls_hidden')==='1'}catch(e){return false}})(),gal:(()=>{try{return localStorage.getItem('ls_gal')!=='0'}catch(e){return true}})(),rg:0,sort:LD('ls_sort','name'),asc:LD('ls_asc','1')!=='0',view:LD('ls_view','list')};
const SORTS={name:{t:'Name',l:'Name',a:'A \u2192 Z',d:'Z \u2192 A'},date:{t:'Date',l:'Date modified',a:'Oldest first',d:'Newest first'},size:{t:'Size',l:'Size',a:'Smallest first',d:'Largest first'},type:{t:'Type',l:'Type',a:'A \u2192 Z',d:'Z \u2192 A'}};
const VIEWS={list:{t:'List',i:'vlist',n:'compact'},compact:{t:'Compact',i:'vcomp',n:'grid'},grid:{t:'Grid',i:'vgrid',n:'list'}};
SORTS.none={t:'No Sort',l:'No Sort',a:'',d:''};
if(!SORTS[S.sort])S.sort='name';if(!VIEWS[S.view])S.view='list';
S.q='';S.aa=false;S.thumb='s';S.theme=LD('ls_theme','light');if(['light','dark','auto'].indexOf(S.theme)<0)S.theme='light';document.documentElement.dataset.theme=S.theme;
// view/sort preferences: global default + per-folder overrides ("Apply to all folders" promotes to global)
let G={view:S.view,sort:S.sort,asc:S.asc,thumb:'s'};try{Object.assign(G,JSON.parse(localStorage.getItem('ls_g')||'{}'))}catch(e){}
let PF={};try{PF=JSON.parse(localStorage.getItem('ls_pf')||'{}')}catch(e){}
const pfKey=()=>S.dev+'|'+S.path;
function applyPrefs(){const o=Object.assign({},G,PF[pfKey()]||{});
  S.view=VIEWS[o.view]?o.view:'list';S.sort=SORTS[o.sort]?o.sort:'name';S.asc=!!o.asc;S.thumb=o.thumb==='l'?'l':'s'}
function setPref(p){const cur=Object.assign({view:S.view,sort:S.sort,asc:S.asc,thumb:S.thumb},p);
  if(S.aa){G=cur;PF={}}else{const k=pfKey();delete PF[k];PF[k]=cur;const ks=Object.keys(PF);if(ks.length>200)delete PF[ks[0]]}
  try{localStorage.setItem('ls_g',JSON.stringify(G));localStorage.setItem('ls_pf',JSON.stringify(PF))}catch(e){}
  render()}
function clearQ(){S.q='';$('#sq').value='';document.body.classList.remove('srch')}
const enc=encodeURIComponent;
const jn=(a,b)=>(a==='/'?'':a)+'/'+b;
async function api(m,u,b,ms){
  const ac=ms?new AbortController():null,tm=ms?setTimeout(()=>ac.abort(),ms):0;let r,t;
  try{for(let k=0;;k++){
    try{r=await fetch(u,{method:m,headers:{'X-LS':'1','Content-Type':'application/json'},body:b?JSON.stringify(b):undefined,signal:ac?ac.signal:undefined});
      t=await r.text();S.down=false;if(S.lost){S.lost=false;S.back=true;S.fails=0;banner('')}break}  // any answer proves the local server is alive
    catch(e){if(e&&e.name==='AbortError')throw new Error('timed out');
      if(e instanceof TypeError){if(m==='GET'&&k<2){await new Promise(x=>setTimeout(x,300));continue}  // one-off network blip: retry quietly
        S.down=true;throw new Error('LANShare on this phone is paused - open Pydroid, then come back')}throw e}}}
  finally{clearTimeout(tm)}
  let j;try{j=JSON.parse(t)}catch(e){j={error:t}}
  if(!r.ok)throw new Error((j&&j.error)||('HTTP '+r.status));return j}
let tt;
function toast(msg,ms=2800,pct){const t=$('#toast'),b=$('#tp');$('#tx').textContent=msg;
  b.style.display=pct==null?'none':'block';if(pct!=null)b.firstChild.style.width=pct+'%';
  t.style.display='block';clearTimeout(tt);if(ms)tt=setTimeout(()=>t.style.display='none',ms)}
function banner(m){const b=$('#banner');b.textContent=m||'';b.style.display=m?'block':'none'}
function fmt(n){const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<4){n/=1024;i++}return(i?n.toFixed(1):n)+' '+u[i]}
function devName(id){if(id==='local')return 'this device';const p=S.peers.find(x=>x.id===id);return p?p.name:'device'}
const EXT={};[['img','jpg jpeg png gif webp bmp heic svg'],['vid','mp4 mkv mov avi webm 3gp'],['aud','mp3 wav m4a ogg flac aac opus'],['pdf','pdf'],['zip','zip rar 7z tar gz'],['apk','apk'],['doc','txt md rtf doc docx odt xls xlsx csv ppt pptx']].forEach(([k,s])=>s.split(' ').forEach(x=>EXT[x]=k));
const vis=()=>S.hid?S.items:S.items.filter(i=>i.name[0]!=='.');
const COL=new Intl.Collator(undefined,{numeric:true,sensitivity:'base'});
const extOf=n=>{const p=n.lastIndexOf('.');return p>0?n.slice(p+1).toLowerCase():''};
function shown(){  // visible items in the chosen order; folders always come first
  const d=S.asc?1:-1,k=S.sort;
  const num=i=>k==='date'?(i.mtime||0):(i.dir?(i.n||0):(i.size||0));
  const base=vis().filter(i=>!S.q||i.name.toLowerCase().indexOf(S.q)>=0);
  if(k==='none')return base;
  return base.slice().sort((a,b)=>{
    if(a.dir!==b.dir)return a.dir?-1:1;
    let c=0;
    if(k==='name')c=COL.compare(a.name,b.name);
    else if(k==='type')c=a.dir?0:COL.compare(extOf(a.name),extOf(b.name));
    else c=num(a)-num(b);
    if(c)return c*d;
    return COL.compare(a.name,b.name)*(k==='name'?d:1)})}
function renderTools(V){
  let fo=0,fi=0,sz=0;V.forEach(i=>{if(i.dir)fo++;else{fi++;sz+=i.size||0}});
  const n=V.length,sm=$('#sum');sm.textContent='';
  sm.append(E('b','',n+(n===1?' item':' items')+(fi?' \u00b7 '+fmt(sz):'')));
  if(fo&&fi)sm.append(E('small','',fo+(fo===1?' folder':' folders')+' \u00b7 '+fi+(fi===1?' file':' files')));
  const sb=$('#sortb');sb.textContent='';sb.append(ic('sort'),E('span','',SORTS[S.sort].t));if(S.sort!=='none')sb.append(ic(S.asc?'up':'down','ar'))}
function openSort(){
  const d=$('#dlg');d.textContent='';d.style.display='flex';
  const done=()=>{d.style.display='none';d.onclick=null};d.onclick=e=>{if(e.target===d)done()};
  const card=E('div','dcard sd');card.append(E('h3','','Sort By'));
  const opt=(k,asc)=>{const on=S.sort===k&&(k==='none'||S.asc===asc);
    const b=E('button','opt'+(on?' on':'')),t=E('span','t');t.append(E('b','',SORTS[k].t+(k==='none'?'':(asc?' \u25B2':' \u25BC'))));
    b.append(E('span','rd'),t);b.onclick=()=>{done();setPref({sort:k,asc:asc})};card.append(b)};
  opt('none',true);['name','size','date','type'].forEach(k=>{opt(k,true);opt(k,false)});
  const row=E('div','dact'),c=E('button','tbtn','Cancel');c.onclick=done;row.append(c);card.append(row);d.append(card)}
function openView(){
  const o=$('#sheet');o.textContent='';o.style.display='flex';S.aa=false;
  const close=()=>{o.style.display='none';o.onclick=null};o.onclick=e=>{if(e.target===o)close()};
  const card=E('div','card vs');o.append(card);
  const draw=()=>{card.textContent='';
    const cbrow=(label,on,f)=>{const r=E('label','vrow'),c=E('input','cbx');c.type='checkbox';c.checked=on;c.onchange=()=>f(c.checked);r.append(E('span','vt',label),c);return r};
    card.append(cbrow('Apply to all folders',S.aa,v=>{S.aa=v;if(v)setPref({});draw()}));
    card.append(E('hr'),E('div','sh','View'));
    const r1=E('div','vrad');
    ['list','grid','compact'].forEach(k=>{const b=E('button','rdo'+(S.view===k?' on':''));b.append(E('span','rd'),ic(VIEWS[k].i));b.setAttribute('aria-label',VIEWS[k].t);b.onclick=()=>{setPref({view:k});draw()};r1.append(b)});card.append(r1);
    const r2=E('div','vrad');
    [['s','Small thumbnails',22],['l','Large thumbnails',32]].forEach(a=>{const b=E('button','rdo'+(S.thumb===a[0]?' on':'')),g=ic('img');g.style.width=g.style.height=a[2]+'px';b.append(E('span','rd'),g);b.setAttribute('aria-label',a[1]);b.onclick=()=>{setPref({thumb:a[0]});draw()};r2.append(b)});card.append(r2);
    card.append(E('hr'),E('div','sh','Sort'));
    const sb=E('button','vrow');sb.append(E('span','vt',SORTS[S.sort].t+(S.sort==='none'?'':(S.asc?' \u25B2':' \u25BC'))));sb.onclick=()=>{close();openSort()};card.append(sb);
    card.append(E('hr'),E('div','sh','Others'),cbrow('Show hidden files',S.hid,()=>{toggleHidden(true);draw()}))};
  draw()}
function kind(i){return i.dir?'folder':(EXT[i.name.split('.').pop().toLowerCase()]||'file')}
function setName(n){$('#nmt').textContent=n;$('#nm').dataset.n=n}
// themed dialog: returns true / the typed text, or null when cancelled
function dlg(o){return new Promise(res=>{
  const d=$('#dlg');d.textContent='';d.style.display='flex';
  const card=E('div','dcard');card.append(E('h3','',o.title));if(o.msg)card.append(E('p','',o.msg));
  let inp=null;if(o.input){inp=E('input','tf');inp.value=o.input.value||'';inp.placeholder=o.input.label||'';inp.autocomplete='off';card.append(inp)}
  const row=E('div','dact'),c=E('button','tbtn','Cancel'),k=E('button','tbtn'+(o.danger?' dng':''),o.ok||'OK');
  const done=v=>{d.style.display='none';d.onclick=null;res(v)};
  c.onclick=()=>done(null);k.onclick=()=>done(inp?inp.value:true);
  d.onclick=e=>{if(e.target===d)done(null)};
  if(inp)inp.onkeydown=e=>{if(e.key==='Enter')k.click()};
  row.append(c,k);card.append(row);d.append(card);
  if(inp)setTimeout(()=>{inp.focus();inp.select()},60)})}

function renderPeers(){
  const sig=JSON.stringify([S.peers,S.dev]);if(sig===S.sig)return;S.sig=sig;
  const box=$('#peers');box.textContent='';
  const mk=(label,id,warn)=>{const c=E('button','chip'+(S.dev===id?' on':''));
    c.append(id==='local'?ic('phone'):E('i','dot'+(warn?' wn':'')),E('span','',label));c.onclick=()=>openDev(id);box.append(c)};
  mk('This device','local');
  S.peers.forEach(p=>mk(p.name,p.id,p.ok===false));
  if(S.dev!=='local'&&!S.peers.find(p=>p.id===S.dev))mk(S.known[S.dev]||'device',S.dev,true);
  const add=E('button','chip add');add.append(ic('add'),E('span','','IP'));add.onclick=addIp;box.append(add);
  $('#hint').textContent=S.peers.length?'':'Searching for devices… tap refresh or + IP'+(S.ips?' · This device: '+S.ips:'')}
let pollGen=0,pollTimer;
async function pollPeers(){
  const g=++pollGen;clearTimeout(pollTimer);
  try{S.peers=await api('GET','/api/peers',null,5000);
    S.peers.forEach(p=>S.known[p.id]=p.name);
    S.fails=0;if(S.back){S.back=false;load()}
    renderPeers()}
  catch(e){if(S.down&&++S.fails>=3&&!S.lost){S.lost=true;banner('⏸ LANShare on this phone stopped responding (Android paused it). Reconnecting…')}}
  if(g===pollGen)pollTimer=setTimeout(pollPeers,S.lost?2500:1200)}
let lastResume=0;
function resume(){  // screen unlocked / tab restored / network back
  const n=Date.now();if(n-lastResume<3000)return;lastResume=n;
  api('POST','/api/scan',{},4000).catch(()=>{});
  pollPeers();S.sig='';if(S.dev!=='local')load()}
document.addEventListener('visibilitychange',()=>{if(!document.hidden)resume()});
window.addEventListener('pageshow',resume);window.addEventListener('online',resume);window.addEventListener('focus',resume);
async function pollOnce(){try{S.peers=await api('GET','/api/peers')}catch(e){}}
async function openDev(id){
  if(id!=='local'&&!S.peers.find(x=>x.id===id))return;
  S.dev=id;S.path='/';S.sel.clear();S.sig='';clearQ();renderPeers();load()}
async function addIp(){
  const ip=await dlg({title:'Add device by IP',msg:'IP address of the other device',input:{label:'e.g. 192.168.43.1'},ok:'Connect'});if(!ip)return;
  try{await api('POST','/api/addip',{ip});toast('Found it!')}catch(e){toast('⚠ '+e.message,4000)}}

let loadT;
async function load(){
  clearTimeout(loadT);const dev=S.dev,path=S.path;
  try{const r=await api('GET','/api/ls?dev='+enc(S.dev)+'&path='+enc(S.path));
    if(dev!==S.dev||path!==S.path)return;
    S.path=r.path;S.items=r.items;S.used=r.used;S.err='';if(!S.lost)banner('');
    S.sel=new Set([...S.sel].filter(n=>vis().find(i=>i.name===n)));render()}
  catch(e){
    if(dev!==S.dev||path!==S.path)return;
    if(/unreachable|offline|paused|timed out|connection|lost/i.test(e.message)){  // transient: keep what is on screen, retry
      S.err=e.message;banner('⚠ '+e.message+' — retrying…');render();loadT=setTimeout(load,3000)}
    else{S.err='';toast('⚠ '+e.message,4500);S.items=[];render()}}}
function render(){
  applyPrefs();
  const cr=$('#crumbs');cr.textContent='';
  const segs=S.path.split('/').filter(Boolean);
  const home=E('button','crumb');home.setAttribute('aria-label','Root');home.append(raw(HOME));home.onclick=()=>go('/');cr.append(home,ic('chev'));
  const dr=E('button','crumb'+(segs.length?'':' cur'));dr.append(raw(DRIVE));if(S.dev!=='local')dr.append(E('span','',devName(S.dev)));dr.onclick=()=>go('/');cr.append(dr);
  let acc='';segs.forEach((seg,n)=>{acc+='/'+seg;const p=acc;cr.append(ic('chev'));const b=E('button','crumb'+(n===segs.length-1?' cur':''),seg);b.onclick=()=>go(p);cr.append(b)});
  $('#ht').textContent=S.dev==='local'?'Main storage':devName(S.dev);
  const pl=$('#used');if(S.dev==='local'&&S.used!=null){pl.style.display='flex';pl.style.setProperty('--p',S.used+'%');pl.lastChild.textContent=S.used+'%';pl.title='Storage used'}else pl.style.display='none';
  cr.scrollLeft=cr.scrollWidth;
  const l=$('#list');l.textContent='';l.className='v-'+S.view+(S.thumb==='l'?' t-lg':'');
  const V=shown();renderTools(V);
  if(!V.length){const e=E('div');e.id='empty';e.append(ic(S.err?'wifi':'folder'),E('p','',S.err?'Can\'t reach this device right now…':(S.q?'No matches':S.items.length?'No visible files (hidden files are off)':'This folder is empty')));l.append(e)}
  const P0=S.path;  // folder this list was drawn for: a double-tap must not append the name twice (/a/a)
  const GV=S.gal?V.filter(i=>!i.dir&&kind(i)==='vid'):[];
  V.forEach(i=>{
    if(GV.indexOf(i)>=0)return;
    const k=kind(i),r=E('div','row'+(S.sel.has(i.name)?' sel':''));
    const lead=E('div','lead k-'+k);
    if(i.dir){lead.append(raw(FOLD,'fold'));const bd=BADGE[i.name.toLowerCase()];if(bd){const g=E('span','bdg');g.style.color=bd[1];g.append(ic(bd[0]));lead.append(g)}}
    else{lead.append(ic(k,'kd'));
      if((k==='img'||k==='vid')&&!/\.(svg|heic|heif)$/i.test(i.name)&&(k==='vid'||i.size<30e6)){
        const key=thKey(i),pth0=jn(S.path,i.name);
        const showR=t=>{if(!t||!t.u)return;const im=E('img','thi');im.alt='';im.src=t.u;lead.insertBefore(im,lead.firstChild);const kd=lead.querySelector('.kd');if(kd)kd.style.display='none'};
        const hit=thGet(key);if(hit)showR(hit);else thWatch(r,()=>({gen:S.rg,key:key,k:k,url:'/api/dl?dev='+enc(S.dev)+'&path='+enc(pth0),done:showR}))}}
    lead.append(ic('check','ck'));
    const nm=E('div','nm'),sub=E('small'),dt=i.mtime?new Date(i.mtime*1000).toLocaleDateString(undefined,{year:'numeric',month:'short',day:'numeric'}):'';
    const sz=i.dir?(i.n==null?'Folder':i.n+(i.n===1?' item':' items')):fmt(i.size);
    if(S.view==='compact')sub.append(E('span','',S.sort==='date'&&dt?dt:sz));else sub.append(E('span','',sz),E('span','',dt));
    nm.append(E('b','',i.name),sub);r.append(lead,nm);
    r.onclick=()=>{if(r._lp){r._lp=false;return}
      if(S.sel.size){S.sel.has(i.name)?S.sel.delete(i.name):S.sel.add(i.name);render()}else if(i.dir)go(jn(P0,i.name));else if(k==='vid')playVid(i);else if(k==='img'&&!/\.(svg|heic|heif)$/i.test(i.name))viewImg(i);else openFile(i)};
    holdMenu(r,i);
    l.append(r)});
  S.rg++;THQ.length=0;
  renderSR(l);
  if(GV.length){const g=E('div','gal');
    GV.forEach(i=>{
      const c=E('div','vc'+(S.sel.has(i.name)?' sel':'')),pth=jn(S.path,i.name),key=thKey(i);
      const ph=E('div','ph');ph.append(ic('vid'));c.append(ph);
      c.append(E('div','vn',i.name));
      const pl=E('span','pl');pl.append(ic('play'));c.append(pl);
      const du=E('span','du');du.style.display='none';c.append(du);
      const ck=ic('check','ck');c.append(ck);
      const show=t=>{if(!t)return;if(t.u){const im=E('img');im.src=t.u;im.alt='';c.insertBefore(im,ph);ph.style.display='none'}
        if(t.d){du.textContent=fmtDur(t.d);du.style.display=''}};
      const hit=thGet(key);if(hit)show(hit);else thWatch(c,()=>({gen:S.rg,key:key,k:'vid',url:'/api/dl?dev='+enc(S.dev)+'&path='+enc(pth),done:show}));
      c.onclick=()=>{if(c._lp){c._lp=false;return}
        if(S.sel.size){S.sel.has(i.name)?S.sel.delete(i.name):S.sel.add(i.name);render()}else playVid(i)};
      holdMenu(c,i);
      g.append(c)});
    l.append(g)}
  renderBar()}

/* Tap on a file: in the Android app fetch it and open it with an installed app (text editor for source files); in a browser let it handle the URL */
function openFile(i){const u='/api/dl?dev='+enc(S.dev)+'&path='+enc(jn(S.path,i.name));
  if(window.LSAndroid&&LSAndroid.open)LSAndroid.open(location.origin+u);else window.open(u,'_blank')}
function viewImg(i){
  const pth=jn(S.path,i.name),u='/api/dl?dev='+enc(S.dev)+'&path='+enc(pth);
  const o=$('#pv');o.textContent='';o.style.display='flex';
  const close=()=>{o.style.display='none';o.textContent=''};
  const hd=E('div','ph2'),x=E('button','ibtn');x.append(ic('close'));x.onclick=close;x.style.color='#fff';hd.append(x,E('b','',i.name));
  const im=E('img','pimg');im.src=u;
  const er=E('div','pe');im.onerror=()=>{er.textContent='Cannot show this picture here.';const a=E('button','tbtn','Download');a.onclick=()=>window.open(u+'&dl=1','_blank');er.append(document.createElement('br'),a);er.style.display='block'};
  o.append(hd,im,er)}
function playVid(i){
  const pth=jn(S.path,i.name),u='/api/dl?dev='+enc(S.dev)+'&path='+enc(pth);
  const o=$('#pv');o.textContent='';o.style.display='flex';
  const close=()=>{try{v.pause();v.removeAttribute('src');v.load()}catch(e){}o.style.display='none';o.textContent=''};
  const hd=E('div','ph2'),x=E('button','ibtn');x.append(ic('close'));x.onclick=close;x.style.color='#fff';hd.append(x,E('b','',i.name));
  const v=E('video');v.controls=true;v.autoplay=true;v.playsInline=true;v.preload='auto';
  const er=E('div','pe');
  v.onerror=()=>{const c=v.error?v.error.code:0,m={1:'aborted',2:'network error',3:'cannot decode (codec not supported by this browser)',4:'format not supported'}[c]||'unknown';
    er.textContent='Cannot play here: '+m+'.';const a=E('button','tbtn','Download');a.onclick=()=>window.open(u+'&dl=1','_blank');er.append(document.createElement('br'),a);er.style.display='block'};
  v.src=u;o.append(hd,v,er)}
/* ---- video thumbnails: made in the browser from the video itself, cached ---- */
const THQ=[];let thBusy=0;const thMem={};
const thKey=i=>S.dev+'|'+S.path+'/'+i.name+'|'+i.size+'|'+(i.mtime||0);
const fmtDur=d=>{d=Math.round(d);const h=Math.floor(d/3600),m=Math.floor(d%3600/60),x=d%60,z=n=>(n<10?'0':'')+n;return h?h+':'+z(m)+':'+z(x):m+':'+z(x)};
function thGet(k){if(thMem[k])return thMem[k];try{const v=localStorage.getItem('ls_th:'+k);if(v){thMem[k]=JSON.parse(v);return thMem[k]}}catch(e){}return null}
function thPut(k,t){thMem[k]=t;if(!t.u)return;
  try{localStorage.setItem('ls_th:'+k,JSON.stringify(t))}
  catch(e){try{Object.keys(localStorage).filter(x=>x.indexOf('ls_th:')===0).forEach(x=>localStorage.removeItem(x));localStorage.setItem('ls_th:'+k,JSON.stringify(t))}catch(e2){}}}
const thIO=('IntersectionObserver' in window)?new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){thIO.unobserve(e.target);const f=e.target._th;if(f){e.target._th=null;THQ.push(f());thPump()}}}),{rootMargin:'300px'}):null;
function thWatch(el,f){if(thIO){el._th=f;thIO.observe(el)}else{THQ.push(f());thPump()}}
function thPump(){
  while(thBusy<2&&THQ.length){const j=THQ.shift();if(j.gen!==S.rg)continue;thBusy++;
    thMake(j.url,j.k).then(t=>{thPut(j.key,t);if(j.gen===S.rg)j.done(t)}).catch(()=>{thMem[j.key]={u:null,d:0}}).then(()=>{thBusy--;thPump()})}}
function thMake(url,kd){return new Promise((res,rej)=>{
  if(kd==='img'){const im=new Image();let done=false;const tm=setTimeout(()=>{if(!done){done=true;im.src='';rej(new Error('thumb'))}},20000);
    im.onload=()=>{if(done)return;done=true;clearTimeout(tm);try{const w=im.naturalWidth,h=im.naturalHeight;if(!w||!h)return rej(new Error('thumb'));
      const sc=Math.min(1,240/Math.max(w,h)),c=document.createElement('canvas');c.width=Math.round(w*sc);c.height=Math.round(h*sc);
      c.getContext('2d').drawImage(im,0,0,c.width,c.height);res({u:c.toDataURL('image/jpeg',0.6),d:0})}catch(e){rej(e)}};
    im.onerror=()=>{if(!done){done=true;clearTimeout(tm);rej(new Error('thumb'))}};im.decoding='async';im.src=url;return}
  /* Video element must live in the DOM and use preload=auto: detached/metadata-only <video> in Android WebView never decodes a frame. */
  const v=document.createElement('video');v.muted=true;v.defaultMuted=true;v.playsInline=true;v.preload='auto';
  v.setAttribute('playsinline','');v.setAttribute('muted','');
  v.style.cssText='position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:0;pointer-events:none';document.body.append(v);
  let fin=false,tm,seeked=false;
  const end=(ok,t)=>{if(fin)return;fin=true;clearTimeout(tm);try{v.pause();v.removeAttribute('src');v.load()}catch(e){}v.remove();ok?res(t):rej(new Error('thumb'))};
  tm=setTimeout(()=>end(false),40000);
  const grab=()=>{try{const w=v.videoWidth,h=v.videoHeight;if(!w||!h)return end(false);
    const sc=Math.min(1,240/Math.max(w,h)),c=document.createElement('canvas');c.width=Math.round(w*sc);c.height=Math.round(h*sc);
    c.getContext('2d').drawImage(v,0,0,c.width,c.height);end(true,{u:c.toDataURL('image/jpeg',0.6),d:v.duration||0})}catch(e){end(false)}};
  v.onerror=()=>end(false);
  v.onloadedmetadata=()=>{const d=v.duration||0;try{v.currentTime=(isFinite(d)&&d>2)?Math.min(d*0.1,10):0.1}catch(e){end(false)}};
  v.onseeked=()=>{if(seeked)return;seeked=true;
    // wait until a frame is actually presented, then draw
    if(v.requestVideoFrameCallback){v.requestVideoFrameCallback(()=>grab());v.play().then(()=>{}).catch(()=>setTimeout(grab,150))}
    else setTimeout(grab,150)};
  v.src=url;v.load()})}
/* ---- native download progress (called from MainActivity) ---- */
const DLS={};
/* WebView timers are throttled while another app is in front (text editor etc.), so also sweep when we come back */
function lsDlSweep(){Object.keys(DLS).forEach(k=>{const c=DLS[k];if(c.exp&&Date.now()>=c.exp){c.el.remove();delete DLS[k]}})}
document.addEventListener('visibilitychange',lsDlSweep);window.addEventListener('focus',lsDlSweep);
function lsDl(id,name,done,total,speed,st,msg){
  let c=DLS[id];
  if(!c){const el=E('div','dlc'),hd=E('div','dh'),nm=E('b'),cx=E('button','','Cancel'),bar=E('div','bar'),fill=E('i'),info=E('small');
    cx.onclick=()=>{try{LSAndroid.cancel(id)}catch(e){}cx.disabled=true};
    bar.append(fill);hd.append(nm,cx);el.append(hd,bar,info);$('#dlw').append(el);c=DLS[id]={el,nm,cx,fill,info}}
  c.nm.textContent=name;
  const fin=t=>{c.exp=Date.now()+t;setTimeout(lsDlSweep,t+30)};
  if(st==='done'){c.el.classList.remove('ind');c.fill.style.width='100%';c.info.textContent=msg||('✅ Saved to Downloads · '+fmt(done));c.cx.style.display='none';fin(msg?700:5000);return}
  if(st==='err'){c.el.classList.remove('ind');c.info.textContent='⚠ Download failed: '+msg;c.cx.style.display='none';fin(7000);return}
  if(st==='cancel'){c.el.classList.remove('ind');c.info.textContent='Cancelled';c.cx.style.display='none';fin(2500);return}
  if(total>0){c.el.classList.remove('ind');const pct=Math.min(100,done*100/total);c.fill.style.width=pct.toFixed(1)+'%';
    const eta=speed>0?Math.ceil((total-done)/speed):0,em=eta?(eta>=3600?Math.floor(eta/3600)+'h ':'')+(eta>=60?Math.floor(eta%3600/60)+'m ':'')+(eta<3600?eta%60+'s':''):'';
    c.info.textContent=Math.floor(pct)+'% · '+fmt(done)+' / '+fmt(total)+' · '+(speed>0?fmt(speed)+'/s':'…')+(em?' · '+em+' left':'')}
  else{c.el.classList.add('ind');c.info.textContent=done?fmt(done)+' · '+(speed>0?fmt(speed)+'/s':'…'):'Connecting…'}}
function toggleHidden(quiet){S.hid=!S.hid;try{localStorage.setItem('ls_hidden',S.hid?'1':'0')}catch(e){}
  S.sel=new Set([...S.sel].filter(n=>vis().find(i=>i.name===n)));render();if(!quiet)toast(S.hid?'Showing hidden files':'Hiding hidden files',1500)}
function openSettings(){
  const o=$('#sheet');o.textContent='';o.style.display='flex';
  const close=()=>{o.style.display='none';o.onclick=null};o.onclick=e=>{if(e.target===o)close()};
  const card=E('div','card');card.append(E('div','handle'),E('h3','','Settings'));
  const row=E('label','set'),t=E('div','t'),sw=E('span','sw'),cb=E('input');cb.type='checkbox';cb.checked=S.hid;
  t.append(E('b','','Show hidden files'),E('small','','Files and folders starting with a dot (.)'));
  cb.onchange=()=>{if(cb.checked!==S.hid)toggleHidden(true)};
  sw.append(cb,E('i'));row.append(t,sw);card.append(row);
  const rowg=E('label','set'),tg=E('div','t'),swg=E('span','sw'),cbg=E('input');cbg.type='checkbox';cbg.checked=S.gal;
  tg.append(E('b','','Video gallery'),E('small','','Show videos as a grid with thumbnails'));
  cbg.onchange=()=>{S.gal=cbg.checked;try{localStorage.setItem('ls_gal',S.gal?'1':'0')}catch(e){}render()};
  swg.append(cbg,E('i'));rowg.append(tg,swg);card.append(rowg);
  const TH=['light','dark','auto'],THN={light:'Light',dark:'Dark',auto:'Follow system'};
  const rt=E('button','set'),tt=E('div','t'),sm=E('small','',THN[S.theme]);rt.style.width='100%';rt.style.textAlign='left';tt.append(E('b','','Theme'),sm);rt.append(tt);
  rt.onclick=()=>{S.theme=TH[(TH.indexOf(S.theme)+1)%3];SV('ls_theme',S.theme);document.documentElement.dataset.theme=S.theme;sm.textContent=THN[S.theme]};card.append(rt);
  if(S.ips){const r2=E('div','set'),t2=E('div','t');t2.append(E('b','','This device'),E('small','',S.ips));r2.append(t2);card.append(r2)}
  const d=E('button','tbtn fill','Done');d.style.alignSelf='flex-end';d.onclick=close;card.append(d);o.append(card)}
function holdMenu(r,i){  // press and hold (or right-click) selects the item; actions appear in the bottom bar
  let t=0,x0=0,y0=0;const stop=()=>{clearTimeout(t);t=0};
  const pick=()=>{r._lp=true;if(navigator.vibrate)try{navigator.vibrate(15)}catch(_){}
    S.sel.add(i.name);r.classList.add('sel');renderBar()};  // no re-render, so the release tap is still swallowed by _lp
  r.addEventListener('touchstart',e=>{if(e.touches.length!==1)return;r._lp=false;x0=e.touches[0].clientX;y0=e.touches[0].clientY;
    stop();t=setTimeout(()=>{t=0;pick()},450)},{passive:true});
  r.addEventListener('touchmove',e=>{if(t&&(Math.abs(e.touches[0].clientX-x0)>10||Math.abs(e.touches[0].clientY-y0)>10))stop()},{passive:true});
  r.addEventListener('touchend',stop);r.addEventListener('touchcancel',stop);
  r.addEventListener('contextmenu',e=>{e.preventDefault();stop();pick()})}
function go(p){S.path=p;S.sel.clear();clearQ();load()}
/* ---- search in subfolders (server walks the tree below the current folder) ---- */
let sT=0;
function srOK(){return S.sr&&S.sr.q===S.q&&S.sr.dev===S.dev&&S.sr.path===S.path}
function renderSR(l){
  if(!S.q||S.q.length<2||!srOK())return;
  const here=S.path==='/'?'/':S.path,par=p=>p.slice(0,p.lastIndexOf('/'))||'/';
  const items=(S.sr.items||[]).filter(i=>par(i.path)!==here&&(S.hid||!i.path.split('/').some(x=>x[0]==='.')));
  if(!items.length&&S.sr.items&&!S.sr.err)return;
  const h=E('div');h.style.cssText='padding:14px 16px 6px;font-size:13px;color:var(--mut);grid-column:1/-1';
  h.textContent=S.sr.err?'⚠ Search failed: '+S.sr.err:S.sr.items?'In subfolders ('+items.length+(S.sr.partial?'+':'')+')':'Searching subfolders…';
  l.append(h);
  items.forEach(i=>{
    const k=kind(i),r=E('div','row'),lead=E('div','lead k-'+k);
    if(i.dir)lead.append(raw(FOLD,'fold'));else lead.append(ic(k,'kd'));
    const nm=E('div','nm'),sub=E('small');sub.append(E('span','',par(i.path)));
    nm.append(E('b','',i.name),sub);r.append(lead,nm);
    r.onclick=()=>i.dir?go(i.path):goFind(par(i.path),i.name);
    l.append(r)})}
async function goFind(dir,name){S.path=dir;S.sel.clear();clearQ();await load();
  document.body.classList.add('srch');$('#sq').value=name;S.q=name.toLowerCase();render()}
function doSearch(){
  const q=S.q;if(!q||q.length<2)return;const dev=S.dev,path=S.path;
  S.sr={q,dev,path,items:null};render();
  api('GET','/api/search?dev='+enc(dev)+'&path='+enc(path)+'&q='+enc(q),null,45000)
    .then(r=>{if(S.sr&&S.sr.q===q){S.sr.items=r.items;S.sr.partial=r.partial;render()}})
    .catch(e=>{if(S.sr&&S.sr.q===q){S.sr.items=[];S.sr.err=e.message;render()}})}
const selPaths=()=>[...S.sel].map(n=>jn(S.path,n));

const selItems=()=>S.items.filter(i=>S.sel.has(i.name));
function renderBar(){
  const b=$('#bar');b.textContent='';
  const btn=(k,lb,f,c)=>{const x=E('button','ib'+(c?' '+c:''));const ii=E('span','ii');ii.append(ic(k));x.append(ii,E('small','',lb));x.onclick=f;b.append(x)};
  const has=S.clip&&S.clip.paths.length;
  document.body.classList.toggle('selm',S.sel.size>0);
  const ssz=selItems().reduce((a,i)=>a+(i.dir?0:i.size||0),0);
  $('#selcount').textContent=S.sel.size+' selected'+(ssz?' \u00b7 '+fmt(ssz):'');
  if(S.sel.size){
    const it=selItems(),files=it.length>0&&it.every(i=>!i.dir);
    btn('copy','Copy',()=>setClip('copy'));btn('cut','Cut',()=>setClip('cut'));
    if(has)btn('paste','Paste',doPaste,'pri');
    if(files)btn('download','Download',doDownload);
    if(S.sel.size===1)btn('edit','Rename',doRename);
    if(S.sel.size===1&&files)btn('openw','Open with',doOpenWith);
    btn('del','Delete',doDelete,'dng')
  }
  const c=$('#clip');
  if(has){c.style.display='flex';c.textContent='';
    c.append(E('span','',(S.clip.op==='cut'?'Cut ':'Copied ')+S.clip.paths.length+' item(s) from '+devName(S.clip.dev)));
    if(!S.sel.size){const pb=E('button','tbtn fill','Paste here');pb.onclick=doPaste;c.append(pb)}
    const xb=E('button','ibtn');xb.append(ic('close'));xb.onclick=async()=>{await api('POST','/api/clip',{op:'clear'});S.clip=null;renderBar()};c.append(xb)}
  else c.style.display='none';
  const dk=$('#dock');dk.style.display=(b.children.length||has)?'':'none';
  document.documentElement.style.setProperty('--dockh',(dk.style.display==='none'?0:dk.offsetHeight)+'px')}
function doDownload(){
  const f=selItems().filter(i=>!i.dir);S.sel.clear();render();
  f.forEach((i,k)=>setTimeout(()=>{const a=document.createElement('a');a.href='/api/dl?dev='+enc(S.dev)+'&path='+enc(jn(S.path,i.name))+'&dl=1';a.download=i.name;document.body.append(a);a.click();a.remove()},k*600))}
async function refreshClip(){try{S.clip=await api('GET','/api/clip')}catch(e){}renderBar()}
async function setClip(op){try{S.clip=await api('POST','/api/clip',{op,dev:S.dev,paths:selPaths()});toast((op==='cut'?'Cut ':'Copied ')+S.sel.size+' item(s) - open a folder and tap Paste');S.sel.clear();render()}catch(e){toast('⚠ '+e.message,4000)}}
async function doPaste(){try{const r=await api('POST','/api/paste',{dev:S.dev,dir:S.path});
  watchJob(r.job);if(await track(r.job)){await api('POST','/api/clip',{op:'clear'});S.clip=null;renderBar()}}catch(e){toast('⚠ '+e.message,5000)}}  // paste finished: drop the clipboard bar
function pickDevice(){return new Promise(res=>{
  const o=$('#sheet');o.textContent='';o.style.display='flex';const card=E('div','card');card.append(E('div','handle'),E('h3','','Send to…'));
  const fin=v=>{o.style.display='none';o.onclick=null;res(v)};o.onclick=e=>{if(e.target===o)fin(null)};
  const opts=[];if(S.dev!=='local')opts.push({id:'local',name:'This device'});S.peers.filter(p=>p.id!==S.dev).forEach(p=>opts.push(p));
  if(!opts.length)card.append(E('p','','No other devices found yet.'));
  opts.forEach(p=>{const b=E('button','li'),ld=E('div','lead');ld.append(ic('phone'));b.append(ld,E('span','',p.name));b.onclick=()=>fin(p);card.append(b)});
  const c=E('button','tbtn','Cancel');c.style.alignSelf='flex-end';c.onclick=()=>fin(null);card.append(c);o.append(card)})}
/* Open with: fetch the selected file and let Android show the app chooser (Android app only) */
function doOpenWith(){const i=selItems()[0];if(!i||i.dir)return;
  const u='/api/dl?dev='+enc(S.dev)+'&path='+enc(jn(S.path,i.name));
  if(window.LSAndroid&&LSAndroid.openWith){LSAndroid.openWith(location.origin+u);S.sel.clear();render()}
  else window.open(u,'_blank')}
async function doSend(){
  const p=await pickDevice();if(!p)return;
  try{const r=await api('POST','/api/send',{dev:S.dev,paths:selPaths(),to:p.id});S.sel.clear();render();watchJob(r.job);track(r.job)}catch(e){toast('⚠ '+e.message,5000)}}
async function doDelete(){
  const n=S.sel.size;
  if(!await dlg({title:'Delete '+n+' item'+(n>1?'s':'')+'?',msg:'From '+devName(S.dev)+'. This can\'t be undone.',ok:'Delete',danger:true}))return;
  try{await api('POST','/api/op',{dev:S.dev,op:'rm',paths:selPaths()});S.sel.clear();load()}catch(e){toast('⚠ '+e.message,4000)}}
async function doRename(){
  const old=[...S.sel][0];const n=await dlg({title:'Rename',input:{value:old,label:'New name'},ok:'Rename'});if(!n||n===old)return;
  try{await api('POST','/api/op',{dev:S.dev,op:'rename',path:jn(S.path,old),name:n});S.sel.clear();load()}catch(e){toast('⚠ '+e.message,4000)}}
async function doMkdir(){
  const n=await dlg({title:'New folder',input:{label:'Folder name'},ok:'Create'});if(!n)return;
  try{await api('POST','/api/op',{dev:S.dev,op:'mkdir',path:jn(S.path,n)});load()}catch(e){toast('⚠ '+e.message,4000)}}
/* mirror a copy/move/send job in the Android notification shade (no-op in a browser) */
function watchJob(id){try{if(window.LSAndroid&&LSAndroid.watch)LSAndroid.watch(location.origin,id)}catch(e){}}
async function track(id){
  let ok=false;
  for(;;){
    let j;try{j=await api('GET','/api/job?id='+id)}catch(e){toast('⚠ '+e.message,5000);break}
    if(j.state==='error'){toast('⚠ '+j.error,7000);break}
    if(j.state==='done'){toast('✅ Done',2500);ok=true;break}
    const pct=Math.floor(j.done*100/j.total);
    toast((j.label||'Working')+'… '+pct+'%  '+(j.bytes?fmt(j.done)+' / '+fmt(j.total):j.done+' / '+j.total),0,pct);
    await new Promise(r=>setTimeout(r,400))}
  await refreshClip();load();return ok}

// ---- pull down to refresh
(()=>{const box=$('#ptr'),lab=box.firstChild,TH=70;let y0=0,dy=0,on=false,busy=false;
  const atTop=()=>(window.scrollY||document.documentElement.scrollTop||0)<=0;
  const setH=h=>{box.style.height=h+'px'};
  const reset=()=>{box.style.transition='height .2s';setH(0);box.classList.remove('go');setTimeout(()=>box.style.transition='',220)};
  const modal=()=>$('#sheet').style.display==='flex'||$('#dlg').style.display==='flex';
  window.addEventListener('touchstart',e=>{if(busy||e.touches.length!==1||!atTop()||modal())return;
    if(e.target.closest&&e.target.closest('#dock,#fab,#toast,#pv,#sheet,#dlg,#top,button'))return;
    y0=e.touches[0].clientY;dy=0;on=true},{passive:true});
  window.addEventListener('touchmove',e=>{if(!on)return;dy=e.touches[0].clientY-y0;
    if(dy<=0||!atTop()){if(dy<=0){on=false;setH(0)}return}
    if(dy<16)return;
    if(e.cancelable)e.preventDefault();
    const h=Math.min(dy*0.5,TH+20);setH(h);const ready=h>=TH;box.classList.toggle('go',ready);
    lab.textContent=ready?'↻ Release to refresh':'↓ Pull to refresh'},{passive:false});
  const end=async()=>{if(!on)return;on=false;
    if(dy>=16&&dy*0.5>=TH&&!busy){busy=true;box.classList.add('go');lab.textContent='Refreshing…';setH(46);
      try{api('POST','/api/scan',{},4000).catch(()=>{});pollPeers();await load();refreshClip()}catch(e){}
      await new Promise(r=>setTimeout(r,400));busy=false}
    reset()};
  window.addEventListener('touchend',end);window.addEventListener('touchcancel',end)})();

window.addEventListener('scroll',()=>$('#top').classList.toggle('el',(window.scrollY||0)>4),{passive:true});
(()=>{const dk=$('#dock');const upd=()=>document.documentElement.style.setProperty('--dockh',(dk.style.display==='none'?0:dk.offsetHeight)+'px');
  if(window.ResizeObserver)new ResizeObserver(upd).observe(dk);window.addEventListener('resize',upd);window.addEventListener('orientationchange',()=>setTimeout(upd,300))})();
// Android back: close the top-most overlay / search / selection; returns true when it handled the press
function lsBack(){
  const pv=$('#pv');if(pv.style.display==='flex'){const x=pv.querySelector('.ibtn');if(x)x.click();else{pv.style.display='none';pv.textContent=''}return true}
  for(const id of ['#dlg','#sheet']){const o=$(id);if(o.style.display==='flex'){if(o.onclick)o.onclick({target:o});else o.style.display='none';return true}}
  if(document.body.classList.contains('srch')){clearQ();render();return true}
  if(S.sel.size){S.sel.clear();render();return true}
  return false}
$('#cog').onclick=openSettings;
$('#sortb').onclick=openSort;$('#tune').onclick=openView;
const sOpen=v=>{if(v){document.body.classList.add('srch');setTimeout(()=>$('#sq').focus(),50)}else{clearQ();render()}};
$('#srch').onclick=()=>sOpen(!document.body.classList.contains('srch'));
$('#sback').onclick=()=>sOpen(false);
$('#sclr').onclick=()=>{$('#sq').value='';S.q='';render();$('#sq').focus()};
$('#sq').oninput=e=>{S.q=e.target.value.trim().toLowerCase();render();clearTimeout(sT);sT=setTimeout(doSearch,500)};
$('#fab').onclick=doMkdir;
$('#xsel').onclick=()=>{S.sel.clear();render()};
$('#allsel').onclick=()=>{S.sel=new Set(shown().map(i=>i.name));render()};
$('#scan').onclick=async()=>{const b=$('#scan');b.classList.remove('spin');void b.offsetWidth;b.classList.add('spin');
  try{await api('POST','/api/scan',{});toast('Scanning…',1500)}catch(e){toast('⚠ '+e.message,3000)}};
$('#nm').onclick=async()=>{const n=await dlg({title:'Device name',msg:'How this device appears to others',input:{value:$('#nm').dataset.n||'',label:'Name'},ok:'Save'});if(!n)return;
  try{const r=await api('POST','/api/name',{name:n});setName(r.name)}catch(e){toast('⚠ '+e.message,3000)}};
(async()=>{
  const i=await api('GET','/api/info');
  setName(i.name);S.ips=(i.ips||[]).join(', ');
  document.title='LANShare - '+i.name;if(i.storage_ok===false)toast('⚠ Files are hidden by Android - allow "All files access" for Pydroid 3',9000);
  await pollOnce();renderPeers();load();refreshClip();pollPeers()})();
</script></body></html>
"""


# ----------------------------------------------------------------- keep-awake
_HELD = []


def hold_awake():
    """Best effort on Android/Pydroid: keep CPU + Wi-Fi awake and let UDP broadcasts through while the
    screen is off or the browser is in front. Silently does nothing where pyjnius is unavailable."""
    got = []
    try:
        from jnius import autoclass
        ctx = autoclass("android.app.ActivityThread").currentApplication().getApplicationContext()
    except Exception:
        print("  Keep-awake: not available (pyjnius missing) - disable battery optimisation for Pydroid instead")
        return
    for label, make in (
        ("cpu", lambda: ctx.getSystemService("power").newWakeLock(1, "LANShare:cpu")),
        ("wifi", lambda: ctx.getSystemService("wifi").createWifiLock(3, "LANShare:wifi")),
        ("multicast", lambda: ctx.getSystemService("wifi").createMulticastLock("LANShare:mc")),
    ):
        try:
            lk = make()
            lk.setReferenceCounted(False)
            lk.acquire()
            _HELD.append(lk)
            got.append(label)
        except Exception:
            pass
    print("  Keep-awake:", ", ".join(got) if got else "not granted")


# ----------------------------------------------------------------- main
class Server(ThreadingHTTPServer):
    request_queue_size = 128
    daemon_threads = True


def main():
    global LOCAL, DISC
    load_cfg()
    hold_awake()
    root = sys.argv[1] if len(sys.argv) > 1 else default_root()
    LOCAL = Local(root)
    STORAGE["ok"] = storage_ok()
    if STORAGE["ok"] is False:
        print("  WARNING:", STORAGE_MSG)
    srv = None
    for port in range(BASE_PORT, BASE_PORT + 20):
        try:
            srv = Server(("", port), H)
            break
        except OSError:
            continue
    if not srv:
        sys.exit("No free port found")
    srv.daemon_threads = True
    DISC = Discovery(port)
    DISC.start()
    url = "http://127.0.0.1:%d" % port
    print("LANShare running")
    print("  Open:   ", url)
    print("  Device: ", CFG["name"])
    print("  Sharing:", LOCAL.root)
    print("  Network:", ", ".join(sorted(DISC.own_ips)) or "(no network found)")
    try:
        webbrowser.open(url)
    except Exception:
        pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
