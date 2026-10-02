#!/usr/bin/env python3
"""LANShare - find devices running the same app on your Wi-Fi / hotspot, browse their
storage, copy / cut / paste between devices, and send files.   Python 3.8+, stdlib only.

Run in Pydroid 3 (or any Python). It opens a phone-friendly UI at http://127.0.0.1:8765
Optional:  python lanshare.py [shared_root_folder]

How it works
  * Every device runs this same file: a small HTTP server + the web UI.
  * Discovery: UDP beacons (broadcast + unicast sweep of the subnet). Devices see each
    other within ~1s, and a hotspot host sees its clients too.
  * Security: each device shows a 6-digit PIN. You enter the other device's PIN once to
    pair. Only the shared root folder is reachable; the UI itself is local-only.
"""
import os, sys, re, json, time, uuid, hmac, random, socket, shutil, struct, mimetypes
import posixpath, threading, subprocess, ipaddress, webbrowser
import urllib.parse, http.client
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

BEACON_PORT = 48555
BASE_PORT = int(os.environ.get("LANSHARE_PORT", 8765))
CHUNK = 1 << 20
INBOX = "/LANShare Received"


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


def load_cfg():
    try:
        with open(CFG_FILE) as f:
            CFG.update(json.load(f))
    except Exception:
        pass
    CFG.setdefault("id", uuid.uuid4().hex[:8])
    CFG.setdefault("pin", "%06d" % random.SystemRandom().randrange(1000000))
    CFG.setdefault("paired", {})
    host = os.environ.get("LANSHARE_NAME") or socket.gethostname()
    CFG.setdefault("name", host if host not in ("", "localhost") else "Phone-" + CFG["id"][:4])
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
                    st = e.stat()
                    d = e.is_dir()
                except OSError:
                    continue
                out.append({"name": e.name, "dir": d, "size": 0 if d else st.st_size, "mtime": int(st.st_mtime)})
        return out

    def names(self, v):
        try:
            return {i["name"] for i in self.ls(v)}
        except (OSError, PermissionError):
            return set()

    def walk(self, v):
        base = self.real(v)
        if os.path.isfile(base):
            return [{"rel": "", "dir": False, "size": os.path.getsize(base)}]
        res = [{"rel": "", "dir": True, "size": 0}]
        for dp, dns, fns in os.walk(base):
            for n in dns:
                if not os.path.islink(os.path.join(dp, n)):
                    res.append({"rel": os.path.relpath(os.path.join(dp, n), base).replace(os.sep, "/"), "dir": True, "size": 0})
            for n in fns:
                fp = os.path.join(dp, n)
                if os.path.islink(fp):
                    continue
                try:
                    sz = os.path.getsize(fp)
                except OSError:
                    continue
                res.append({"rel": os.path.relpath(fp, base).replace(os.sep, "/"), "dir": False, "size": sz})
        return res

    def open_read(self, v):
        p = self.real(v)
        return open(p, "rb"), os.path.getsize(p)

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
    """Read-only, seekable view of a file on another device (re-opens with a Range header on seek)."""

    def __init__(self, remote, v):
        self.rm, self.v, self.pos, self.s = remote, v, 0, None
        self.s, self.size = remote._call("GET", "file", {"path": v}, stream=True)

    def seek(self, n):
        if self.s:
            self.s.close()
            self.s = None
        self.pos = n

    def read(self, n=-1):
        if self.s is None:
            hdr = {"Range": "bytes=%d-" % self.pos} if self.pos else None
            self.s, _ = self.rm._call("GET", "file", {"path": self.v}, stream=True, headers=hdr)
        b = self.s.read(n)
        self.pos += len(b)
        return b

    def close(self):
        if self.s:
            self.s.close()
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

    def __init__(self, peer, pin):
        self.id, self.name, self.ip, self.port, self.pin = peer["id"], peer["name"], peer["ip"], peer["port"], pin

    def _call(self, method, route, params=None, body=None, size=None, stream=False, headers=None):
        c = http.client.HTTPConnection(self.ip, self.port, timeout=30, blocksize=1 << 16)
        h = {"X-Pin": self.pin}
        if headers:
            h.update(headers)
        if body is not None:
            h["Content-Length"] = str(size)
        try:
            c.request(method, "/p/%s?%s" % (route, urllib.parse.urlencode(params or {})), body=body, headers=h)
            r = c.getresponse()
        except Exception as e:
            c.close()
            raise IOError("%s unreachable (%s)" % (self.name, e))
        if r.status not in (200, 206):
            txt = r.read(800).decode("utf8", "ignore")
            c.close()
            try:
                txt = json.loads(txt).get("error", txt)
            except Exception:
                pass
            if r.status == 404:
                raise FileNotFoundError(txt)
            if r.status == 401:  # only a wrong PIN un-pairs the device
                CFG["paired"].pop(self.id, None)
                save_cfg()
                raise PermissionError("PIN rejected by %s - pair again" % self.name)
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


def ep(dev):
    if dev == "local":
        return LOCAL
    p = DISC.get(dev)
    if not p:
        raise IOError("that device is offline")
    pin = CFG["paired"].get(dev)
    if not pin:
        raise PermissionError("not paired - enter its PIN first")
    return Remote(p, pin)


# ----------------------------------------------------------------- discovery
EXTRA_IFACES = {}  # ip -> netmask; injected by the Android wrapper (LinkProperties) when ioctl is restricted


def set_extra_ifaces(spec):
    """spec: 'ip/prefix,ip/prefix' (from Android ConnectivityManager)."""
    out = {}
    for part in (spec or "").split(","):
        try:
            ip, bits = part.strip().split("/")
            out[ip] = str(ipaddress.ip_network("0.0.0.0/" + bits).netmask)
        except ValueError:
            pass
    EXTRA_IFACES.clear()
    EXTRA_IFACES.update(out)


def get_ifaces():
    found = {}
    try:  # Linux / Android: ioctl per interface
        import fcntl
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for _, name in socket.if_nameindex():
            b = struct.pack("256s", name[:15].encode())
            try:
                ip = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, b)[20:24])
                found[ip] = socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x891B, b)[20:24])
            except OSError:
                pass
        s.close()
    except Exception:
        pass
    for ip, mask in list(EXTRA_IFACES.items()):
        found.setdefault(ip, mask)
    if not found:
        try:
            out = subprocess.run(["ip", "-4", "-o", "addr"], capture_output=True, text=True, timeout=3).stdout
            for ip, bits in re.findall(r"inet (\d+\.\d+\.\d+\.\d+)/(\d+)", out):
                found[ip] = str(ipaddress.ip_network("0.0.0.0/" + bits).netmask)
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
        if net.prefixlen < 22:
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


class Discovery:
    def __init__(self, port):
        self.port = port
        self.peers = {}
        self.lock = threading.Lock()
        self.ifaces = get_ifaces()
        self.own_ips = {ip for ip, _ in self.ifaces}
        self.alive = True
        self.out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.out.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    def msg(self):
        return json.dumps({"app": "lanshare", "id": CFG["id"], "name": CFG["name"], "port": self.port}).encode()

    def start(self):
        threading.Thread(target=self.listen, daemon=True).start()
        threading.Thread(target=self.beacon, daemon=True).start()

    def stop(self):
        self.alive = False
        try:
            self.out.close()
        except OSError:
            pass

    def add(self, pid, ip, port, name):
        with self.lock:
            new = pid not in self.peers or self.peers[pid]["ip"] != ip
            self.peers[pid] = {"id": pid, "ip": ip, "port": int(port), "name": str(name)[:40], "seen": time.time()}
        return new

    def get(self, pid):
        with self.lock:
            return dict(self.peers[pid]) if pid in self.peers else None

    def list(self):
        now = time.time()
        with self.lock:
            for k in [k for k, v in self.peers.items() if now - v["seen"] > 9]:
                del self.peers[k]
            return sorted((dict(v) for v in self.peers.values()), key=lambda v: v["name"].lower())

    def unicast(self, ip):
        try:
            self.out.sendto(self.msg(), (ip, BEACON_PORT))
        except OSError:
            pass

    def listen(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        try:
            s.bind(("", BEACON_PORT))
        except OSError as e:
            print("! cannot listen for beacons:", e)
            return
        s.settimeout(1.0)
        while self.alive:
            try:
                data, (ip, _) = s.recvfrom(2048)
                m = json.loads(data)
                if m.get("app") != "lanshare" or m["id"] == CFG["id"]:
                    continue
                if self.add(m["id"], ip, m["port"], m["name"]):
                    self.unicast(ip)  # instant two-way discovery
            except Exception:
                pass
        s.close()

    def beacon(self):
        n = 0
        while self.alive:
            if n % 8 == 0:
                self.ifaces = get_ifaces()
                self.own_ips = {ip for ip, _ in self.ifaces}
            self.announce()
            if n < 3 or n % 15 == 0:
                self.sweep()
            n += 1
            time.sleep(2)

    def announce(self):
        for t in {"255.255.255.255"} | {str(net.broadcast_address) for _, net in self.ifaces}:
            try:
                self.out.sendto(self.msg(), (t, BEACON_PORT))
            except OSError:
                pass

    def sweep(self):
        """Unicast to every host - works even when the router/hotspot drops broadcasts."""
        nets = [net for _, net in self.ifaces]
        for ip in arp_neighbors():
            try:
                n = ipaddress.ip_network(ip + "/24", strict=False)
                if n not in nets:
                    nets.append(n)
            except ValueError:
                pass
        sent = 0
        for net in nets:
            for h in net.hosts():
                h = str(h)
                if h not in self.own_ips and sent < 1024:
                    self.unicast(h)
                    sent += 1

    def add_ip(self, ip):
        ipaddress.ip_address(ip)
        for port in range(BASE_PORT, BASE_PORT + 20):
            try:
                c = http.client.HTTPConnection(ip, port, timeout=1.0)
                c.request("GET", "/p/hello")
                m = json.loads(c.getresponse().read())
                c.close()
                if m.get("app") == "lanshare" and m["id"] != CFG["id"]:
                    self.add(m["id"], ip, m["port"], m["name"])
                    self.unicast(ip)
                    return True
            except Exception:
                continue
        return False


# ----------------------------------------------------------------- jobs / clipboard
JOBS = {}
CLIP = {}
FAILS = {}


def check_pin(ip, pin):
    now = time.time()
    n, t = FAILS.get(ip, (0, 0))
    if n >= 5 and now - t < 60:
        return False
    if hmac.compare_digest(str(pin), CFG["pin"]):
        FAILS.pop(ip, None)
        return True
    FAILS[ip] = (n + 1 if now - t < 60 else 1, now)
    return False


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
                    f, size = src.open_read(p if not it["rel"] else p + "/" + it["rel"])
                    try:
                        dst.write(target, f, size, bump)
                    finally:
                        f.close()
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
def mime_for(name):
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


def safe_inline(mt):
    return (mt.startswith(("image/", "video/", "audio/")) and "svg" not in mt) or mt in ("application/pdf", "text/plain")


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LANShare"

    def log_message(self, *a):
        pass

    # helpers
    def ip(self):
        return self.client_address[0]

    def trusted(self):
        return self.ip() in ("127.0.0.1", "::1") or self.ip() in DISC.own_ips

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
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

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
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            if u.path == "/p/hello":
                return self.json({"app": "lanshare", "id": CFG["id"], "name": CFG["name"], "port": DISC.port})
            if u.path.startswith("/p/"):
                return self.peer(u.path[3:], q)
            if not self.trusted():
                return self.reply(403, ("LANShare - " + CFG["name"]).encode(), "text/plain")
            host = (self.headers.get("Host") or "").rsplit(":", 1)[0]
            if host not in ("127.0.0.1", "localhost") and host not in DISC.own_ips:
                return self.reply(403, b"bad host", "text/plain")
            if u.path == "/":
                return self.reply(200, PAGE.encode(), "text/html; charset=utf-8")
            if u.path.startswith("/api/"):
                if method == "POST" and self.headers.get("X-LS") != "1":
                    return self.reply(403, b"missing header", "text/plain")
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

    # ---- what other devices call (PIN protected)
    def peer(self, route, q):
        if not self.trusted() and not check_pin(self.ip(), self.headers.get("X-Pin", "")):
            return self.fail(401, "bad pin")
        L = LOCAL
        if route == "ping":
            self.json({"ok": True})
        elif route == "ls":
            self.json(L.ls(q["path"]))
        elif route == "walk":
            self.json(L.walk(q["path"]))
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
            return self.json({"id": CFG["id"], "name": CFG["name"], "pin": CFG["pin"], "ips": sorted(DISC.own_ips),
                              "port": DISC.port, "root": LOCAL.root})
        if route == "peers":
            return self.json([{"id": p["id"], "name": p["name"], "ip": p["ip"], "paired": p["id"] in CFG["paired"]}
                              for p in DISC.list()])
        if route == "scan":
            DISC.announce()
            threading.Thread(target=DISC.sweep, daemon=True).start()
            return self.json({"ok": True})
        if route == "ls":
            items = ep(q["dev"]).ls(vnorm(q.get("path", "/")))
            items.sort(key=lambda i: (not i["dir"], i["name"].lower()))
            return self.json({"path": vnorm(q.get("path", "/")), "items": items})
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
        if route == "pair":
            p = DISC.get(b["id"])
            if not p:
                raise IOError("that device is offline")
            pin = str(b["pin"]).strip()
            Remote(p, pin).ping()
            CFG["paired"][b["id"]] = pin
            save_cfg()
            return self.json({"ok": True})
        if route == "addip":
            if not DISC.add_ip(str(b["ip"]).strip()):
                raise IOError("no LANShare device found at that address")
            return self.json({"ok": True})
        if route == "name":
            CFG["name"] = str(b["name"]).strip()[:40] or CFG["name"]
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
<title>LANShare</title>
<style>
:root{--bg:#f4f5f7;--fg:#1c1e21;--card:#fff;--ac:#1a73e8;--mut:#6b7280;--bd:#e5e7eb;--sel:#e8f0fe}
@media(prefers-color-scheme:dark){:root{--bg:#111215;--fg:#e8eaed;--card:#1b1d21;--mut:#9aa0a6;--bd:#2b2e33;--sel:#1f2a3d}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font:15px system-ui,sans-serif;background:var(--bg);color:var(--fg);padding-bottom:150px}
header{position:sticky;top:0;z-index:5;background:var(--card);border-bottom:1px solid var(--bd);padding:10px 12px;display:flex;align-items:center;gap:8px}
header h1{font-size:18px;margin:0;flex:1}
.tag{background:var(--sel);color:var(--ac);border-radius:12px;padding:4px 10px;font-size:13px;border:0;font-weight:600}
#peers{display:flex;gap:8px;overflow-x:auto;padding:10px 12px}
.chip{flex:none;border:1px solid var(--bd);background:var(--card);color:var(--fg);border-radius:20px;padding:8px 14px;font-size:14px}
.chip.on{background:var(--ac);color:#fff;border-color:var(--ac)}
#hint{padding:0 14px;color:var(--mut);font-size:13px}
#crumbs{padding:4px 12px;display:flex;flex-wrap:wrap;gap:2px;align-items:center;color:var(--mut)}
#crumbs button{background:none;border:0;color:var(--ac);font-size:14px;padding:6px 4px}
.row{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid var(--bd);background:var(--card)}
.row.sel{background:var(--sel)}
.dot{width:24px;height:24px;border-radius:50%;border:2px solid var(--mut);flex:none;display:flex;align-items:center;justify-content:center;font-size:14px;color:#fff}
.sel .dot{background:var(--ac);border-color:var(--ac)}
.ico{font-size:24px;flex:none}
.nm{flex:1;min-width:0}.nm b{display:block;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.nm small{color:var(--mut)}
#empty{padding:30px;text-align:center;color:var(--mut)}
#dock{position:fixed;left:0;right:0;bottom:0;background:var(--card);border-top:1px solid var(--bd);padding:8px 10px calc(8px + env(safe-area-inset-bottom));z-index:6}
#bar{display:flex;gap:6px;overflow-x:auto}
#clip{display:none;align-items:center;gap:8px;padding:0 2px 8px;font-size:13px}#clip span{flex:1}
.act{flex:none;border:1px solid var(--bd);background:var(--bg);color:var(--fg);border-radius:10px;padding:10px 12px;font-size:14px}
.act.pri{background:var(--ac);color:#fff;border-color:var(--ac)}
#toast{display:none;position:fixed;left:12px;right:12px;bottom:calc(120px + env(safe-area-inset-bottom));background:#222;color:#fff;border-radius:10px;padding:12px;z-index:9;font-size:14px}
#sheet{display:none;position:fixed;inset:0;background:#0008;z-index:10;align-items:flex-end}
#sheet .card{background:var(--card);width:100%;padding:16px;border-radius:16px 16px 0 0;display:flex;flex-direction:column;gap:8px}
#sheet h3{margin:0 0 4px}.big{padding:14px;text-align:left}
</style></head><body>
<header><h1>LANShare</h1><button class=tag id=nm></button><button class=tag id=pin></button><button class=tag id=scan>⟳</button></header>
<div id=peers></div><div id=hint></div><div id=crumbs></div><div id=list></div>
<div id=dock><div id=clip></div><div id=bar></div></div>
<div id=toast></div><div id=sheet></div>
<script>
const $=s=>document.querySelector(s);
const E=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e};
const S={dev:'local',path:'/',sel:new Set(),peers:[],items:[],clip:null,sig:''};
const enc=encodeURIComponent;
const jn=(a,b)=>(a==='/'?'':a)+'/'+b;
async function api(m,u,b){
  const r=await fetch(u,{method:m,headers:{'X-LS':'1','Content-Type':'application/json'},body:b?JSON.stringify(b):undefined});
  const t=await r.text();let j;try{j=JSON.parse(t)}catch(e){j={error:t}}
  if(!r.ok)throw new Error((j&&j.error)||('HTTP '+r.status));return j}
let tt;
function toast(msg,ms=2800){const t=$('#toast');t.textContent=msg;t.style.display='block';clearTimeout(tt);if(ms)tt=setTimeout(()=>t.style.display='none',ms)}
function fmt(n){const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<4){n/=1024;i++}return(i?n.toFixed(1):n)+' '+u[i]}
function devName(id){if(id==='local')return 'this device';const p=S.peers.find(x=>x.id===id);return p?p.name:'device'}
const ICONS={jpg:'🖼',jpeg:'🖼',png:'🖼',gif:'🖼',webp:'🖼',mp4:'🎬',mkv:'🎬',mov:'🎬',mp3:'🎵',wav:'🎵',m4a:'🎵',pdf:'📕',zip:'🗜',rar:'🗜',apk:'📦',txt:'📝',doc:'📝',docx:'📝'};
function icon(i){return i.dir?'📁':(ICONS[i.name.split('.').pop().toLowerCase()]||'📄')}

function renderPeers(){
  const sig=JSON.stringify([S.peers,S.dev]);if(sig===S.sig)return;S.sig=sig;
  const box=$('#peers');box.textContent='';
  const mk=(label,id)=>{const c=E('button','chip'+(S.dev===id?' on':''),label);c.onclick=()=>openDev(id);box.append(c)};
  mk('📱 This device','local');
  S.peers.forEach(p=>mk((p.paired?'🟢 ':'🔒 ')+p.name,p.id));
  const add=E('button','chip','＋ IP');add.onclick=addIp;box.append(add);
  $('#hint').textContent=S.peers.length?'':'Searching for devices running LANShare on this network…'}
async function pollPeers(){
  try{S.peers=await api('GET','/api/peers');
    if(S.dev!=='local'&&!S.peers.find(p=>p.id===S.dev)){toast('Device went offline');S.dev='local';S.path='/';S.sel.clear();load()}
    renderPeers()}catch(e){}
  setTimeout(pollPeers,1200)}
async function ensurePaired(p){
  if(p.id==='local'||p.paired)return true;
  const pin=prompt('Enter the PIN shown on '+p.name+' (top bar of its screen):');if(!pin)return false;
  try{await api('POST','/api/pair',{id:p.id,pin});p.paired=true;S.sig='';await pollOnce();return true}catch(e){toast('⚠ '+e.message,4000);return false}}
async function pollOnce(){try{S.peers=await api('GET','/api/peers')}catch(e){}}
async function openDev(id){
  if(id!=='local'){const p=S.peers.find(x=>x.id===id);if(!p||!await ensurePaired(p))return}
  S.dev=id;S.path='/';S.sel.clear();S.sig='';renderPeers();load()}
async function addIp(){
  const ip=prompt('IP address of the other device (e.g. 192.168.43.1):');if(!ip)return;
  try{await api('POST','/api/addip',{ip});toast('Found it!')}catch(e){toast('⚠ '+e.message,4000)}}

async function load(){
  try{const r=await api('GET','/api/ls?dev='+enc(S.dev)+'&path='+enc(S.path));S.path=r.path;S.items=r.items;
    S.sel=new Set([...S.sel].filter(n=>S.items.find(i=>i.name===n)));render()}
  catch(e){toast('⚠ '+e.message,4500);S.items=[];render()}}
function render(){
  const cr=$('#crumbs');cr.textContent='';
  const home=E('button','',devName(S.dev)==='this device'?'📱 Storage':'📂 '+devName(S.dev));home.onclick=()=>go('/');cr.append(home);
  let acc='';S.path.split('/').filter(Boolean).forEach(seg=>{acc+='/'+seg;const p=acc;cr.append(E('span','','›'));const b=E('button','',seg);b.onclick=()=>go(p);cr.append(b)});
  const l=$('#list');l.textContent='';
  if(!S.items.length){const e=E('div','','This folder is empty');e.id='empty';l.append(e)}
  S.items.forEach(i=>{
    const r=E('div','row'+(S.sel.has(i.name)?' sel':''));
    const d=E('div','dot',S.sel.has(i.name)?'✓':'');d.onclick=e=>{e.stopPropagation();S.sel.has(i.name)?S.sel.delete(i.name):S.sel.add(i.name);render()};
    const nm=E('div','nm');nm.append(E('b','',i.name),E('small','',(i.dir?'Folder':fmt(i.size))+' · '+new Date(i.mtime*1000).toLocaleDateString()));
    r.append(d,E('div','ico',icon(i)),nm);
    nm.onclick=()=>{if(S.sel.size){S.sel.has(i.name)?S.sel.delete(i.name):S.sel.add(i.name);render()}else if(i.dir)go(jn(S.path,i.name));else window.open('/api/dl?dev='+enc(S.dev)+'&path='+enc(jn(S.path,i.name)),'_blank')};
    l.append(r)});
  renderBar()}
function go(p){S.path=p;S.sel.clear();load()}
const selPaths=()=>[...S.sel].map(n=>jn(S.path,n));

function renderBar(){
  const b=$('#bar');b.textContent='';
  const btn=(t,f,c)=>{const x=E('button','act'+(c?' '+c:''),t);x.onclick=f;b.append(x)};
  if(S.sel.size){
    btn('📋 Copy',()=>setClip('copy'));btn('✂️ Cut',()=>setClip('cut'));btn('📤 Send',doSend);
    if(S.sel.size===1)btn('✏️ Rename',doRename);btn('🗑 Delete',doDelete);btn('✖',()=>{S.sel.clear();render()})
  }else{btn('📁 New folder',doMkdir);btn('☑ Select all',()=>{S.items.forEach(i=>S.sel.add(i.name));render()})}
  const c=$('#clip');
  if(S.clip&&S.clip.paths.length){c.style.display='flex';c.textContent='';
    c.append(E('span','',(S.clip.op==='cut'?'✂️ ':'📋 ')+S.clip.paths.length+' item(s) from '+devName(S.clip.dev)));
    const pb=E('button','act pri','⬇ Paste here');pb.onclick=doPaste;
    const xb=E('button','act','✕');xb.onclick=async()=>{await api('POST','/api/clip',{op:'clear'});S.clip=null;renderBar()};c.append(pb,xb)}
  else c.style.display='none'}
async function refreshClip(){try{S.clip=await api('GET','/api/clip')}catch(e){}renderBar()}
async function setClip(op){try{S.clip=await api('POST','/api/clip',{op,dev:S.dev,paths:selPaths()});toast((op==='cut'?'Cut ':'Copied ')+S.sel.size+' item(s) - open a folder and tap Paste');S.sel.clear();render()}catch(e){toast('⚠ '+e.message,4000)}}
async function doPaste(){try{const r=await api('POST','/api/paste',{dev:S.dev,dir:S.path});track(r.job)}catch(e){toast('⚠ '+e.message,5000)}}
function pickDevice(){return new Promise(res=>{
  const o=$('#sheet');o.textContent='';o.style.display='flex';const card=E('div','card');card.append(E('h3','','Send to…'));
  const opts=[];if(S.dev!=='local')opts.push({id:'local',name:'This device',paired:true});S.peers.filter(p=>p.id!==S.dev).forEach(p=>opts.push(p));
  if(!opts.length)card.append(E('p','','No other devices found yet.'));
  opts.forEach(p=>{const b=E('button','act big',(p.paired?'🟢 ':'🔒 ')+p.name);b.onclick=()=>{o.style.display='none';res(p)};card.append(b)});
  const c=E('button','act','Cancel');c.onclick=()=>{o.style.display='none';res(null)};card.append(c);o.append(card)})}
async function doSend(){
  const p=await pickDevice();if(!p||!await ensurePaired(p))return;
  try{const r=await api('POST','/api/send',{dev:S.dev,paths:selPaths(),to:p.id});S.sel.clear();render();track(r.job)}catch(e){toast('⚠ '+e.message,5000)}}
async function doDelete(){
  if(!confirm('Delete '+S.sel.size+' item(s) from '+devName(S.dev)+'?'))return;
  try{await api('POST','/api/op',{dev:S.dev,op:'rm',paths:selPaths()});S.sel.clear();load()}catch(e){toast('⚠ '+e.message,4000)}}
async function doRename(){
  const old=[...S.sel][0];const n=prompt('New name',old);if(!n||n===old)return;
  try{await api('POST','/api/op',{dev:S.dev,op:'rename',path:jn(S.path,old),name:n});S.sel.clear();load()}catch(e){toast('⚠ '+e.message,4000)}}
async function doMkdir(){
  const n=prompt('Folder name');if(!n)return;
  try{await api('POST','/api/op',{dev:S.dev,op:'mkdir',path:jn(S.path,n)});load()}catch(e){toast('⚠ '+e.message,4000)}}
async function track(id){
  for(;;){
    let j;try{j=await api('GET','/api/job?id='+id)}catch(e){toast('⚠ '+e.message,5000);break}
    if(j.state==='error'){toast('⚠ '+j.error,7000);break}
    if(j.state==='done'){toast('✅ Done',2500);break}
    const pct=Math.floor(j.done*100/j.total);
    toast((j.label||'Working')+'… '+pct+'%  '+(j.bytes?fmt(j.done)+' / '+fmt(j.total):j.done+' / '+j.total),0);
    await new Promise(r=>setTimeout(r,400))}
  await refreshClip();load()}

$('#scan').onclick=async()=>{await api('POST','/api/scan',{});toast('Scanning…',1500)};
$('#nm').onclick=async()=>{const n=prompt('Name of this device',$('#nm').dataset.n);if(!n)return;const r=await api('POST','/api/name',{name:n});$('#nm').textContent='📱 '+r.name;$('#nm').dataset.n=r.name};
(async()=>{
  const i=await api('GET','/api/info');
  $('#nm').textContent='📱 '+i.name;$('#nm').dataset.n=i.name;$('#pin').textContent='PIN '+i.pin;
  document.title='LANShare - '+i.name;
  await pollOnce();renderPeers();load();refreshClip();pollPeers()})();
</script></body></html>
"""


# ----------------------------------------------------------------- main
def serve(root, block=True, open_browser=True):
    """Start server + discovery. block=False returns (port, server) immediately (Android embed)."""
    global LOCAL, DISC
    load_cfg()
    LOCAL = Local(root)
    srv = None
    for port in range(BASE_PORT, BASE_PORT + 20):
        try:
            srv = ThreadingHTTPServer(("", port), H)
            break
        except OSError:
            continue
    if not srv:
        raise RuntimeError("No free port found")
    srv.daemon_threads = True
    DISC = Discovery(port)
    DISC.start()
    url = "http://127.0.0.1:%d" % port
    print("LANShare running")
    print("  Open:   ", url)
    print("  Device: ", CFG["name"], "   PIN:", CFG["pin"])
    print("  Sharing:", LOCAL.root)
    print("  Network:", ", ".join(sorted(DISC.own_ips)) or "(no network found)")
    if open_browser:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    if not block:
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return port, srv
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


def main():
    serve(sys.argv[1] if len(sys.argv) > 1 else default_root())


if __name__ == "__main__":
    main()
