"""
Phone Explorer - PC side (part 2)

Finds phones on the Wi-Fi (UDP broadcast), keeps a list of saved devices (with PIN), opens the phone's
storage in Windows Explorer (ftp://phone:PIN@IP:PORT/) and can optionally map it as a drive letter
(needs rclone + WinFsp). Optional system tray icon if `pystray` and `Pillow` are installed
(the packaged .exe includes them).

ONE TAP: keep this program running (tray). In the LANShare app press "Phone Explorer" in the side menu: the phone starts
sharing, finds this PC and Explorer opens the phone's storage - no IP, no PIN. (UDP 41235 answers the phone's search,
TCP 8798 receives "open"; allow both once in the Windows firewall prompt.)

REMOTE COMMANDS (optional, off by default): the "Remote commands: ON / OFF" button turns on a
/exec endpoint on the same TCP 8798 port, guarded by a password you set in this window (the phone app can remember it). The "Remote command"
screen in the LANShare app sends a command line and shows stdout/err/exit code. Commands run as jobs (up to 30 min) that the phone
polls, so a cut Wi-Fi / Tailscale connection does not lose the answer (see "remote commands (jobs)" below). Commands run with whatever rights
this program itself has - see ADMIN below.

ADMIN: on Windows this program ALWAYS runs as Administrator: it relaunches itself elevated (one UAC prompt) as soon
as it starts, and quits if the prompt is declined. (--no-admin is still accepted but ignored.) "Start with Windows" then installs a Task Scheduler entry that starts already
elevated with no further prompt, when this program is currently running elevated; otherwise it falls back to the
ordinary (non-elevated) Run-key autostart, which will prompt again at every login.

Run:   python phone_explorer.py [--pair "phoneexplorer://..."] [--auto] [--tray] [--no-admin]
       --pair       import a pairing link from the phone's QR screen and save the device
       --auto       open the default device right after start (also: tick "Auto-open on start")
       --tray       start hidden in the system tray
       --no-admin   ignored (always runs as Administrator)
       --install-startup / --uninstall-startup   add / remove the Windows autostart entry
"""
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import shutil
import signal
import socket
import string
import subprocess
import sys
import threading
import time
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from tkinter import messagebox, ttk
import re
from urllib.parse import parse_qs, unquote, urlparse

if sys.platform == "win32":
    import ctypes

try:  # optional
    import pystray
    from PIL import Image, ImageDraw
except Exception:  # pragma: no cover
    pystray = None

DISCOVERY_PORT = 41234
MAGIC = b"PHONEEXPLORER_DISCOVER"
PC_MAGIC = b"PHONEEXPLORER_PC_DISCOVER"   # the phone's search for PCs running this program
PC_UDP_PORT = 41235
PC_HTTP_PORT = 8798
EXEC_MAX = 65536    # cap on stdout/stderr bytes sent back per remote command
EXEC_MAX_LIVE = 524288   # same cap for a live (streamed) job: the phone gets it in pieces, so a bigger total is fine
BG, CARD, FG, MUTED, ACCENT = "#0F1720", "#1B2735", "white", "#9FB0C3", "#3B82F6"


# ------------------------------------------------------------------------------------- admin

def is_admin():
    """True on Windows if this process already has administrator rights (always True elsewhere)."""
    if sys.platform != "win32":
        return True
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_elevated():
    """Re-starts this program elevated (one UAC prompt) and returns True if that new process was launched
    (the caller should then exit this one). The relaunched copy gets the same arguments, minus --no-admin."""
    if sys.platform != "win32":
        return False
    try:
        args = [a for a in sys.argv[1:] if a != "--no-admin"]
        if getattr(sys, "frozen", False):          # packaged .exe
            exe = sys.executable
            params = " ".join('"%s"' % a for a in args)
        else:
            exe = sys.executable
            params = '"%s" %s' % (os.path.abspath(__file__), " ".join('"%s"' % a for a in args))
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, None, 1)
        return rc > 32   # ShellExecute: a value greater than 32 means success
    except Exception:
        return False


def hash_pass(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), 200000).hex()


def check_pass(cfg, password):
    """True when [password] matches the one saved in this window. No password set = always False."""
    if not cfg.get("exec_hash") or not cfg.get("exec_salt"):
        return False
    return hmac.compare_digest(hash_pass(password, cfg["exec_salt"]), cfg["exec_hash"])


_fails = {}   # ip -> [count, locked_until]


def gen_pin(n=6):
    """A short random numeric PIN, for the remote-command screen in the phone app."""
    return "".join(secrets.choice(string.digits) for _ in range(n))


# ---------------------------------------------------------------------------------------- config

def config_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "PhoneExplorer")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "config.json")


def load_config():
    cfg = {"devices": [], "default": "", "auto_open": False, "drive": "P", "startup_init": False,
           "exec_enabled": False, "exec_salt": "", "exec_hash": ""}
    try:
        with open(config_path(), "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
        return cfg
    except Exception:
        pass
    # migrate part-1 file (phone_explorer.json next to the script)
    try:
        old = os.path.join(os.path.dirname(os.path.abspath(__file__)), "phone_explorer.json")
        with open(old, "r", encoding="utf-8") as f:
            o = json.load(f)
        if o.get("last_ip") and o.get("pin"):
            cfg["devices"].append({"name": "Phone", "ip": o["last_ip"], "port": 2121, "user": "phone", "pin": o["pin"]})
            cfg["default"] = "Phone"
    except Exception:
        pass
    return cfg


def save_config(cfg):
    try:
        tmp = config_path() + ".tmp"      # write + rename: a reader (the other copy of this program, the /exec handler) never sees half a file
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
        os.replace(tmp, config_path())
    except Exception:
        pass


def parse_pairing(link):
    """phoneexplorer://phone:PIN@IP:PORT/?name=Pixel  ->  device dict (or None)."""
    try:
        u = urlparse(link.strip())
        if u.scheme != "phoneexplorer" or not u.hostname or not u.password:
            return None
        name = unquote(parse_qs(u.query).get("name", ["Phone"])[0]) or "Phone"
        return {"name": name, "ip": u.hostname, "port": u.port or 2121, "user": u.username or "phone", "pin": u.password}
    except Exception:
        return None


def upsert_device(cfg, dev):
    for d in cfg["devices"]:
        if d["name"] == dev["name"]:
            d.update({k: v for k, v in dev.items() if v not in (None, "")})
            return d
    cfg["devices"].append(dev)
    return dev


# ------------------------------------------------------------------------------------ autostart

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "PhoneExplorer"
TASK_NAME = "PhoneExplorer"
_NO_WIN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def startup_command(elevated_task=False):
    """Command line used to start this program at logon: no console window, hidden in the tray.
    elevated_task=True adds --no-admin, since a Task Scheduler entry already running elevated must not
    try to relaunch itself again."""
    if getattr(sys, "frozen", False):                       # packaged .exe
        exe, extra = sys.executable, ""
    else:
        exe = sys.executable
        pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")  # pythonw = no console window
        if os.path.exists(pyw):
            exe = pyw
        extra = ' "%s"' % os.path.abspath(__file__)
    tail = " --tray --no-admin" if elevated_task else " --tray"
    return '"%s"%s%s' % (exe, extra, tail)


def _task_exists():
    try:
        r = subprocess.run(["schtasks", "/Query", "/TN", TASK_NAME], capture_output=True, creationflags=_NO_WIN)
        return r.returncode == 0
    except Exception:
        return False


def startup_enabled():
    if sys.platform != "win32":
        return False
    if _task_exists():
        return True
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, RUN_NAME)[0] == startup_command()
    except Exception:
        return False


def set_startup(enable):
    """Add / remove autostart. Prefers a Task Scheduler entry (/RL HIGHEST) so the program starts already
    elevated at logon with no UAC prompt each time; creating that entry needs this process to already be
    elevated. Otherwise falls back to the ordinary (non-elevated) 'Run' key, which re-elevates itself with
    one UAC prompt at every login (see relaunch_elevated)."""
    if sys.platform != "win32":
        return False
    try:
        subprocess.run(["schtasks", "/Delete", "/TN", TASK_NAME, "/F"], capture_output=True, creationflags=_NO_WIN)
    except Exception:
        pass
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            try:
                winreg.DeleteValue(k, RUN_NAME)
            except FileNotFoundError:
                pass
    except Exception:
        pass
    if not enable:
        return True
    if is_admin():
        try:
            cmd = ["schtasks", "/Create", "/TN", TASK_NAME, "/SC", "ONLOGON", "/RL", "HIGHEST",
                   "/TR", startup_command(elevated_task=True), "/F"]
            if subprocess.run(cmd, capture_output=True, creationflags=_NO_WIN).returncode == 0:
                return True
        except Exception:
            pass
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, startup_command())
        return True
    except Exception:
        return False


# ------------------------------------------------------------------------------------- discovery

def broadcast_addresses():
    """Limited broadcast plus the /24 broadcast of every local IPv4 (some Wi-Fi setups need it)."""
    addrs = {"255.255.255.255"}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                addrs.add(ip.rsplit(".", 1)[0] + ".255")
    except Exception:
        pass
    return addrs


def discover(timeout=2.5, port=DISCOVERY_PORT, targets=None):
    """Return a list of dicts {name, ip, port, user} for phones that answered."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.4)
    found = {}
    t0 = time.time()
    last_send = 0
    while time.time() - t0 < timeout:
        if time.time() - last_send > 0.8:
            for a in (targets or broadcast_addresses()):
                try:
                    s.sendto(MAGIC, (a, port))
                except OSError:
                    pass
            last_send = time.time()
        try:
            data, addr = s.recvfrom(1024)
            info = json.loads(data.decode("utf-8"))
            if info.get("app") == "PhoneExplorer":
                found[addr[0]] = {"name": info.get("name", "Phone"), "ip": addr[0],
                                  "port": int(info.get("port", 2121)), "user": info.get("user", "phone")}
        except Exception:
            pass
    s.close()
    return list(found.values())


def ftp_url(d):
    return "ftp://%s:%s@%s:%d/" % (d["user"], d["pin"], d["ip"], d["port"])


def open_in_explorer(d):
    subprocess.Popen(["explorer.exe", ftp_url(d)])


# --------------------------------------------------------------------------------- drive mapping

def free_drive_letters():
    return [c for c in "PQRSTUVWXYZGHIJKLMNO" if not os.path.exists(c + ":\\")]


def rclone_path():
    return shutil.which("rclone")


def winfsp_installed():
    for base in (os.environ.get("ProgramFiles(x86)", ""), os.environ.get("ProgramFiles", "")):
        if base and os.path.isdir(os.path.join(base, "WinFsp")):
            return True
    return False


def start_drive_mount(d, letter):
    """Mount the phone as <letter>: through rclone's FTP backend + WinFsp. Returns the Popen."""
    rc = rclone_path()
    obscured = subprocess.run([rc, "obscure", d["pin"]], capture_output=True, text=True, check=True).stdout.strip()
    cmd = [rc, "mount", ":ftp:/", letter + ":", "--ftp-host", d["ip"], "--ftp-port", str(d["port"]),
           "--ftp-user", d["user"], "--ftp-pass", obscured, "--vfs-cache-mode", "writes",
           "--volname", d["name"], "--no-console"]
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.Popen(cmd, creationflags=flags)


# ------------------------------------------------------------------------- one-tap listener (phone -> PC)

def lan_ip(s):
    try:
        ip = ipaddress.ip_address(s.split("%")[0])
        return ip.version == 4 and (ip.is_private or ip.is_link_local or ip.is_loopback
                                    or ip in ipaddress.ip_network("100.64.0.0/10"))
    except ValueError:
        return False


def udp_responder():
    """Answers the phone's broadcast so it can find this PC."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", PC_UDP_PORT))
    except OSError:
        return
    reply = json.dumps({"app": "PhoneExplorerPC", "name": socket.gethostname(), "port": PC_HTTP_PORT}).encode()
    while True:
        try:
            data, addr = s.recvfrom(512)
            if data.strip() == PC_MAGIC and lan_ip(addr[0]):
                s.sendto(reply, addr)
        except OSError:
            time.sleep(0.5)


# ---------------------------------------------------------------------- remote commands (jobs)
# A command used to be held on ONE idle HTTP connection until it finished (up to 5 min). Android destroys such sockets
# whenever Tailscale / Wi-Fi re-routes ("Software caused connection abort"), NATs and access points drop idle ones, and a
# command that was still running had its answer thrown away. Now the command runs as a JOB: the phone starts it with an id,
# then asks again every few seconds (long poll, <= EXEC_POLL_WAIT s). Every ask is safe to repeat (same id = same job), the
# output is kept here, so a cut connection only costs one more ask. A phone without id still gets the old single answer.
EXEC_SYNC_LIMIT = 300     # s: old-style request that waits for the end
EXEC_JOB_LIMIT = 1800     # s: job started with an id (the phone polls it)
EXEC_POLL_WAIT = 20       # s: one poll waits at most this long (phone may ask for less, never more than 25)
EXEC_JOBS_MAX = 8         # commands running at the same time
EXEC_IDLE_EOF = 600       # s: a command that waits for typed input and gets none (and prints nothing) gets end-of-input after this long
_ID_RE = re.compile(r"[A-Za-z0-9_-]{8,64}")


def _oem_encoding():
    if sys.platform == "win32":
        try:
            return "cp%d" % ctypes.windll.kernel32.GetOEMCP()
        except Exception:
            pass
    return "utf-8"


def decode_output(b):
    """Console bytes -> text. The old text=True used the ANSI code page, but cmd.exe writes the OEM one (cp857 on a Turkish
    PC): one 'ü' (0x81) raised UnicodeDecodeError and the whole answer became an error. UTF-8 first (PowerShell / python /
    git), then UTF-16 (wsl, some tools), then the OEM page; never raises."""
    if not b:
        return ""
    try:
        if b[:2] in (b"\xff\xfe", b"\xfe\xff"):
            t = b.decode("utf-16", "replace")
        elif b.count(b"\x00") * 3 > len(b):
            t = b.decode("utf-16-le", "replace")
        else:
            try:
                t = b.decode("utf-8")
            except UnicodeDecodeError as e:
                if e.reason == "unexpected end of data" and e.end >= len(b):
                    t = b[:e.start].decode("utf-8")      # the 64 KB cap cut a character in half
                else:
                    t = b.decode(_oem_encoding(), "replace")
    except Exception:
        t = b.decode("utf-8", "replace")
    return t.replace("\r\n", "\n")


def kill_tree(proc):
    """Stops the command AND everything it started. proc.kill() only ends cmd.exe; a grandchild that kept the output pipe
    open made subprocess.run() wait for ever after its timeout, so the phone never got an answer."""
    try:
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True, stdin=subprocess.DEVNULL,
                           creationflags=_NO_WIN, timeout=20)
        else:
            os.killpg(proc.pid, signal.SIGKILL)
    except Exception:
        pass
    try:
        proc.kill()
    except Exception:
        pass


class ExecJob:
    def __init__(self, jid, cmd, limit, cap=EXEC_MAX):
        self.id, self.cmd, self.limit, self.cap = jid, cmd, limit, cap
        self.buf = [bytearray(), bytearray()]     # stdout, stderr (first [cap] bytes each; the rest is read and dropped)
        self.n = [0, 0]
        self.done = threading.Event()
        self.cv = threading.Condition()           # notified when output arrives or the job ends: a poll answers at once
        self.t_end = 0.0
        self.res = None
        self.note = ""
        self.p = None
        self.last = [0.0, 0.0]                    # time of the last output chunk per stream (a prompt without newline is shown once it is quiet)
        self.t_in = time.time()                   # last time the phone sent an answer (or the start)
        self.eof = False

    def _pump(self, stream, i):
        try:
            while True:
                chunk = stream.read1(65536)
                if not chunk:
                    break
                self.n[i] += len(chunk)
                self.last[i] = time.time()
                room = self.cap - len(self.buf[i])
                if room > 0:
                    self.buf[i] += chunk[:room]
                    with self.cv:
                        self.cv.notify_all()
        except Exception:
            pass

    def _cut(self, i, off):
        """End offset of the part of stream i (from [off]) that may be shown now: up to the last newline. Whole lines only, so no
        character is cut in half; UTF-16 output (contains NUL bytes) is held back until the job ends."""
        region = bytes(self.buf[i][off:])
        if b"\x00" in region:
            return off
        k = region.rfind(b"\n")
        if k + 1 < len(region) and time.time() - self.last[i] > 0.8:
            try:
                region.decode("utf-8")
                return off + len(region)      # a prompt such as "(Y/N)?" has no newline: show it once the output went quiet
            except UnicodeDecodeError:
                pass
        return off + k + 1 if k >= 0 else off

    def wait_new(self, o, e, wait):
        """Blocks until the job ended, new whole lines exist past the offsets, or [wait] s passed."""
        end = time.time() + wait
        with self.cv:
            while not self.done.is_set() and self._cut(0, o) <= o and self._cut(1, e) <= e:
                left = end - time.time()
                if left <= 0:
                    break
                self.cv.wait(min(left, 0.3))

    def view(self, o=None, e=None):
        """Answer for a poll. o/e = None: old style (whole result at the end). Else only what is NEW since offsets o / e (bytes
        already received by the phone), plus the new offsets. Asking again with the same offsets gives the same answer."""
        if o is None:
            return self.res if self.done.is_set() else {"done": False, "id": self.id}
        if self.done.is_set():
            r = self.res
            err = decode_output(bytes(self.buf[1][e:]))
            if self.note:
                err = (err + ("" if not err or err.endswith("\n") else "\n")) + self.note
            return {"done": True, "id": self.id, "out": decode_output(bytes(self.buf[0][o:])), "err": err, "code": r["code"],
                    "truncated": r["truncated"], "o": len(self.buf[0]), "e": len(self.buf[1])}
        co, ce = self._cut(0, o), self._cut(1, e)
        return {"done": False, "id": self.id, "out": decode_output(bytes(self.buf[0][o:co])),
                "err": decode_output(bytes(self.buf[1][e:ce])), "o": co, "e": ce}

    def send(self, text):
        """Types [text] + Enter into the running command (answer to a prompt). Returns False when it cannot take input."""
        p = self.p
        if p is None or self.done.is_set() or self.eof or p.stdin is None:
            return False
        try:
            p.stdin.write((text + "\r\n").encode(_oem_encoding(), "replace") if sys.platform == "win32" else (text + "\n").encode("utf-8", "replace"))
            p.stdin.flush()
            self.t_in = time.time()
            return True
        except Exception:
            return False

    def _eof_watch(self):
        """No answer for IDLE_EOF s: close stdin, so a command that reads it ("pause", "more") ends as before instead of waiting."""
        while not self.done.wait(2.0):
            if time.time() - max(self.t_in, self.last[0], self.last[1]) > EXEC_IDLE_EOF:
                break
        self.eof = True
        try:
            if self.p and self.p.stdin:
                self.p.stdin.close()
        except Exception:
            pass

    def run(self):
        code, note, p = -1, "", None
        try:
            cwd = os.path.expanduser("~")
            kw = {"shell": True, "stdin": subprocess.PIPE,   # stdin = pipe: the phone can answer "chkdsk /f" (Y/N) ...; it is closed after IDLE_EOF s without an answer so "pause" etc. never wait for ever
                  "stdout": subprocess.PIPE, "stderr": subprocess.PIPE, "cwd": cwd if os.path.isdir(cwd) else None}
            if sys.platform == "win32":
                kw["creationflags"] = _NO_WIN
            else:
                kw["start_new_session"] = True
            p = subprocess.Popen(self.cmd, **kw)
            self.p = p
            threading.Thread(target=self._eof_watch, daemon=True).start()
            ts = [threading.Thread(target=self._pump, args=(p.stdout, 0), daemon=True),
                  threading.Thread(target=self._pump, args=(p.stderr, 1), daemon=True)]
            for t in ts:
                t.start()
            killed = False
            try:
                p.wait(timeout=self.limit)
            except subprocess.TimeoutExpired:
                killed = True
                note = "[timed out after %d s - the command was stopped]" % self.limit
                kill_tree(p)
                try:
                    p.wait(timeout=10)
                except Exception:
                    pass
            end = time.time() + (3 if killed else 5)    # a background child that inherited the pipes must not hold the answer back
            for t in ts:
                t.join(max(0.1, end - time.time()))
            code = p.returncode if p.returncode is not None else -1
        except Exception as e:
            note = "[could not run the command: %s]" % e
        finally:
            self.note = note
            err = decode_output(bytes(self.buf[1]))
            if note:
                err = (err + ("" if not err or err.endswith("\n") else "\n")) + note
            self.res = {"done": True, "id": self.id, "out": decode_output(bytes(self.buf[0])), "err": err,
                        "code": code, "truncated": self.n[0] > self.cap or self.n[1] > self.cap}
            self.t_end = time.time()
            self.done.set()
            with self.cv:
                self.cv.notify_all()


_jobs = {}
_jobs_lock = threading.Lock()
_awake_on = [False]


def _keep_awake():
    """The PC must not go to sleep while a command runs (a sleeping PC drops the Tailscale link: the phone sees an abort)."""
    if sys.platform != "win32":
        return
    with _jobs_lock:
        if _awake_on[0]:
            return
        _awake_on[0] = True

    def loop():
        try:
            while True:
                ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
                time.sleep(5)
                with _jobs_lock:
                    if not any(not j.done.is_set() for j in _jobs.values()):
                        _awake_on[0] = False
                        break
        except Exception:
            _awake_on[0] = False
        try:
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)       # ES_CONTINUOUS: back to normal
        except Exception:
            pass
    threading.Thread(target=loop, daemon=True).start()


def exec_job(jid, cmd, limit, cap=EXEC_MAX):
    """Starts [cmd] as a job. The same [jid] again returns the job that already exists (never runs the command twice)."""
    with _jobs_lock:
        now = time.time()
        for k, j in list(_jobs.items()):                      # forget answers nobody collected for 15 min
            if j.done.is_set() and now - j.t_end > 900:
                del _jobs[k]
        if jid and jid in _jobs:
            return _jobs[jid]
        if sum(1 for j in _jobs.values() if not j.done.is_set()) >= EXEC_JOBS_MAX:
            raise RuntimeError("too many commands are running on the PC already")
        j = ExecJob(jid or secrets.token_hex(8), cmd, limit, cap)
        _jobs[j.id] = j
    threading.Thread(target=j.run, daemon=True).start()
    _keep_awake()
    return j


# ------------------------------------------------------------------------------------ firewall

def ensure_firewall():
    """The Windows prompt opens the port only for the networks that were ticked in it, and the Tailscale adapter is a PUBLIC
    network: when it was not ticked the phone's connect hung until its timeout. Allow the two ports on every profile, but
    only from private / Tailscale addresses (the same range lan_ip() accepts). Needs admin; once, in the background."""
    if sys.platform != "win32" or not is_admin():
        return
    remote = "LocalSubnet,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,169.254.0.0/16"
    for name, proto, port in (("Phone Explorer TCP %d" % PC_HTTP_PORT, "TCP", PC_HTTP_PORT),
                              ("Phone Explorer UDP %d" % PC_UDP_PORT, "UDP", PC_UDP_PORT)):
        try:
            r = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", "name=" + name], capture_output=True,
                               stdin=subprocess.DEVNULL, creationflags=_NO_WIN, timeout=30)
            if r.returncode == 0:
                continue
            subprocess.run(["netsh", "advfirewall", "firewall", "add", "rule", "name=" + name, "dir=in", "action=allow",
                            "protocol=" + proto, "localport=%d" % port, "profile=any", "remoteip=" + remote],
                           capture_output=True, stdin=subprocess.DEVNULL, creationflags=_NO_WIN, timeout=30)
        except Exception:
            pass


def live_cfg(app):
    """Settings as saved on disk right now: a second copy of this program (autostart + a manual start) holds an older
    in-memory copy, so the ON button / password changed in one window looked 'turned off' / 'wrong password' in the other."""
    c = dict(app.cfg)
    try:
        with open(config_path(), "r", encoding="utf-8") as f:
            c.update(json.load(f))
    except Exception:
        pass
    return c


def start_listeners(app):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        timeout = 60        # was 15: a slow first packet over Tailscale (relay) must not cut the request in half

        def log_message(self, *a):
            pass

        def _reply(self, code, obj):
            b = json.dumps(obj).encode()
            try:
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b)
                self.wfile.flush()
            except (ConnectionError, OSError):
                pass        # the phone gave up waiting (its own timeout): nothing left to answer
            self.close_connection = True

        def _body(self):
            """Request body (form-encoded, <= 256 KB used). The rest is read and dropped: closing a socket that still holds
            unread data makes Windows send a RST and the phone reports 'connection aborted' instead of the answer."""
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                n = 0
            if n <= 0:
                return b""
            keep = self.rfile.read(min(n, 262144))
            left = n - len(keep)
            while left > 0:
                chunk = self.rfile.read(min(left, 65536))
                if not chunk:
                    break
                left -= len(chunk)
            return keep

        def do_POST(self):
            try:
                u = urlparse(self.path)
                ip = self.client_address[0].split("%")[0]
                if not lan_ip(ip) or ":" in ip:
                    self._body()
                    return self._reply(403, {"error": "LAN only"})
                q = parse_qs(u.query)
                body = self._body()
                if body:                    # long commands travel in the body (the URL line is limited to 16 KB on the phone)
                    q.update(parse_qs(body.decode("utf-8", "replace")))
                if u.path == "/open":
                    return self._do_open(q)
                if u.path == "/exec":
                    return self._do_exec(q)
                return self._reply(404, {"error": "not found"})
            except Exception as e:      # never leave the phone with a silently closed socket
                self._reply(500, {"error": "PC error: %s" % e})

        def _do_open(self, q):
            ip = self.client_address[0].split("%")[0]
            user = q.get("user", ["phone"])[0]
            pin = q.get("pin", [""])[0]
            if not (re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", user) and re.fullmatch(r"[A-Za-z0-9]{1,32}", pin)):
                return self._reply(400, {"error": "bad user / pin"})
            try:
                port = max(1, min(65535, int(q.get("port", ["2121"])[0])))
            except ValueError:
                port = 2121
            name = (q.get("name", ["Phone"])[0] or "Phone")[:40]
            dev = {"name": name, "ip": ip, "port": port, "user": user, "pin": pin}
            try:
                app.after(0, app.open_remote, dev)
            except Exception:
                return self._reply(503, {"error": "the Phone Explorer window on the PC is closing"})
            self._reply(200, {"ok": True})

        def _do_exec(self, q):
            """Runs a command line on this PC and returns its output. Off by default - the person at the PC
            must press the "Remote commands" button to turn it ON, and the phone must send the password.
            No id = old behaviour (one request waits for the end). With id = job: the first request starts it, later
            ones (poll=1, or the same request again) wait up to `wait` s for the end and answer {"done": false} if it runs on."""
            if q.get("probe"):      # the phone asks which version this is (no command, no password)
                return self._reply(200, {"v": 3, "exec": bool(live_cfg(app).get("exec_enabled"))})
            cfg = live_cfg(app)
            if not cfg.get("exec_enabled"):
                return self._reply(403, {"error": "Remote commands are turned off on this PC"})
            ip = self.client_address[0].split("%")[0]
            f = _fails.setdefault(ip, [0, 0.0])
            if time.time() < f[1]:
                return self._reply(429, {"error": "too many wrong passwords - try again in a minute"})
            if not cfg.get("exec_hash"):
                return self._reply(403, {"error": "No password is set on the PC (Phone Explorer window -> Remote commands)"})
            if not check_pass(cfg, q.get("pass", [""])[0][:200]):
                f[0] += 1
                if f[0] >= 5:
                    f[0] = 0
                    f[1] = time.time() + 60
                return self._reply(403, {"error": "wrong password"})
            f[0] = 0
            jid = q.get("id", [""])[0]
            if jid and not _ID_RE.fullmatch(jid):
                return self._reply(400, {"error": "bad job id"})
            if jid and (q.get("poll") or q.get("send")):
                with _jobs_lock:
                    job = _jobs.get(jid)
                if job is None:     # never start a command from a poll: after a restart of this program it would run again
                    return self._reply(404, {"error": "job not found - the Phone Explorer program on the PC was restarted"})
                if q.get("send"):   # the phone types an answer into the running command (Y/N ...)
                    if not job.send(q.get("in", [""])[0][:2000]):
                        return self._reply(409, {"error": "the command has already ended"})
                    return self._reply(200, {"ok": True})
            else:
                cmd = (q.get("cmd", [""])[0] or "").strip()
                if not cmd:
                    return self._reply(400, {"error": "empty command"})
                if len(cmd) > 100000:
                    return self._reply(400, {"error": "command too long"})
                try:
                    job = exec_job(jid, cmd, EXEC_JOB_LIMIT if jid else EXEC_SYNC_LIMIT,
                                   EXEC_MAX_LIVE if (jid and q.get("o")) else EXEC_MAX)
                except RuntimeError as e:
                    return self._reply(429, {"error": str(e)})
                try:
                    app.after(0, app.set_status, "Ran remote command: %s" % cmd[:60])
                except Exception:
                    pass
            try:
                wait = max(0.0, min(25.0, float(q.get("wait", [EXEC_POLL_WAIT])[0]))) if jid else None
            except ValueError:
                wait = float(EXEC_POLL_WAIT)
            try:      # o / e = bytes of stdout / stderr the phone already has -> live mode: answer with the new output only
                o = max(0, int(q["o"][0])) if jid and q.get("o") else None
                e = max(0, int(q.get("e", ["0"])[0])) if o is not None else None
            except ValueError:
                o = e = None
            if o is None:
                job.done.wait(wait)         # wait=None (old style): until it is over - the job has its own time limit
            else:
                job.wait_new(o, e, wait)    # returns as soon as a new whole line exists (or the job ended)
            return self._reply(200, job.view(o, e))

    threading.Thread(target=udp_responder, daemon=True).start()
    try:
        srv = ThreadingHTTPServer(("", PC_HTTP_PORT), H)
        srv.request_queue_size = 64
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        threading.Thread(target=ensure_firewall, daemon=True).start()
        return True
    except OSError:
        return False        # another copy of this program is already running

# -------------------------------------------------------------------------------------------- UI

class App(tk.Tk):
    def __init__(self, args):
        super().__init__()
        self.title("Phone Explorer" + (" (Administrator)" if is_admin() else ""))
        self.geometry("520x760")
        self.minsize(480, 720)
        self.configure(bg=BG)
        self.cfg = load_config()
        self.found = []          # last discovery results
        self._rows = []
        self.mount = None        # (Popen, letter, name)
        self.tray = None
        self._build()
        self.protocol("WM_DELETE_WINDOW", self.on_close)

        if "--pair" in args:
            i = args.index("--pair")
            if i + 1 < len(args):
                self.import_link(args[i + 1], silent=True)
        self.refresh_list()
        listening = start_listeners(self)
        if not listening and "--tray" in args:
            self.destroy()          # another copy is already running in the background
            sys.exit(0)
        if sys.platform == "win32" and not self.cfg.get("startup_init"):
            set_startup(True)       # first run: start with Windows (can be switched off in the window)
            self.cfg["startup_init"] = True
            save_config(self.cfg)
        self.startup.set(startup_enabled())
        if listening:
            self.set_status("Ready: press \"Phone Explorer\" in the LANShare app menu on the phone.")
        self.scan(auto_open=("--auto" in args) or bool(self.cfg.get("auto_open")))
        if "--tray" in args and pystray:
            self.after(200, self.to_tray)

    # --- layout
    def _build(self):
        tk.Label(self, text="Phone Explorer", font=("Segoe UI", 20, "bold"), fg=FG, bg=BG).pack(pady=(14, 0))
        tk.Label(self, text="Phone and PC on the same Wi-Fi (or Tailscale)", fg=MUTED, bg=BG).pack()

        st = ttk.Style(self)
        try:
            st.theme_use("clam")
        except Exception:
            pass
        st.configure("Treeview", background=CARD, fieldbackground=CARD, foreground=FG, rowheight=26, borderwidth=0)
        st.configure("Treeview.Heading", background=BG, foreground=MUTED)
        st.map("Treeview", background=[("selected", ACCENT)])

        self.tree = ttk.Treeview(self, columns=("name", "ip", "state"), show="headings", height=7, selectmode="browse")
        for c, t, w in (("name", "Phone", 190), ("ip", "Address", 150), ("state", "Status", 110)):
            self.tree.heading(c, text=t)
            self.tree.column(c, width=w, anchor="w")
        self.tree.pack(fill="x", padx=18, pady=(14, 6))
        self.tree.bind("<Double-1>", lambda e: self.on_open())

        row = tk.Frame(self, bg=BG)
        row.pack(fill="x", padx=18)
        self._btn(row, "Open in Explorer", ACCENT, self.on_open).pack(side="left", expand=True, fill="x", padx=(0, 4))
        self._btn(row, "Scan", CARD, self.scan).pack(side="left", padx=4)
        self._btn(row, "Forget", CARD, self.on_forget).pack(side="left", padx=(4, 0))

        pin_row = tk.Frame(self, bg=BG)
        pin_row.pack(pady=(12, 2))
        tk.Label(pin_row, text="PIN of selected phone:", fg=MUTED, bg=BG).pack(side="left")
        self.pin = tk.StringVar()
        tk.Entry(pin_row, textvariable=self.pin, width=10, font=("Consolas", 14), justify="center").pack(side="left", padx=8)
        self._btn(pin_row, "Save", CARD, self.on_save_pin).pack(side="left")
        self.tree.bind("<<TreeviewSelect>>", self.on_select)


        drv = tk.Frame(self, bg=BG)
        drv.pack(fill="x", padx=18, pady=(14, 0))
        tk.Label(drv, text="Drive letter:", fg=MUTED, bg=BG).pack(side="left")
        letters = free_drive_letters() or ["P"]
        self.letter = tk.StringVar(value=self.cfg.get("drive") if self.cfg.get("drive") in letters else letters[0])
        ttk.Combobox(drv, textvariable=self.letter, values=letters, width=3, state="readonly").pack(side="left", padx=6)
        self.btn_map = self._btn(drv, "Map as drive", CARD, self.on_map)
        self.btn_map.pack(side="left")

        self.auto = tk.BooleanVar(value=bool(self.cfg.get("auto_open")))
        tk.Checkbutton(self, text="Auto-open default phone when this app starts", variable=self.auto,
                       command=self.on_auto, fg=FG, bg=BG, selectcolor=BG, activebackground=BG,
                       activeforeground=FG).pack(anchor="w", padx=18, pady=(12, 0))
        self.startup = tk.BooleanVar(value=False)
        tk.Checkbutton(self, text="Start with Windows (runs in the background / tray)", variable=self.startup,
                       command=self.on_startup, fg=FG, bg=BG, selectcolor=BG, activebackground=BG,
                       activeforeground=FG).pack(anchor="w", padx=18, pady=(4, 0))
        self._btn(self, "Set selected phone as default", CARD, self.on_default).pack(anchor="w", padx=18, pady=(6, 0))

        tk.Label(self, text="Remote commands (for the \"Remote command\" screen in the app)",
                fg=FG, bg=BG, font=("Segoe UI", 10, "bold")).pack(anchor="w", padx=18, pady=(16, 0))
        self.exec_enabled = tk.BooleanVar(value=bool(self.cfg.get("exec_enabled")))
        self.exec_btn = tk.Button(self, command=self.on_exec_toggle, fg=FG, activeforeground=FG,
                                  font=("Segoe UI", 11, "bold"), relief="flat", bd=0, padx=14, pady=8, cursor="hand2")
        self.exec_btn.pack(anchor="w", padx=18, pady=(6, 0))
        self._exec_paint()
        pw_row = tk.Frame(self, bg=BG)
        pw_row.pack(fill="x", padx=18, pady=(6, 0))
        tk.Label(pw_row, text="Password:", fg=MUTED, bg=BG).pack(side="left")
        self.exec_pass_var = tk.StringVar()
        tk.Entry(pw_row, textvariable=self.exec_pass_var, show="*", width=18).pack(side="left", padx=8)
        self._btn(pw_row, "Save password", CARD, self.on_save_exec_pass).pack(side="left")
        self.exec_pass_label = tk.Label(self, fg=MUTED, bg=BG)
        self.exec_pass_label.pack(anchor="w", padx=18, pady=(2, 0))
        self._exec_pass_paint()
        self.admin_label = tk.Label(self, fg=MUTED, bg=BG,
                                    text="Running as: " + ("Administrator - commands have admin rights" if is_admin()
                                                            else "standard user - commands do NOT have admin rights"))
        self.admin_label.pack(anchor="w", padx=18, pady=(2, 0))

        self.status = tk.Label(self, text="Start sharing in the phone app first.", fg=MUTED, bg=BG,
                               wraplength=470, justify="left")
        self.status.pack(fill="x", padx=18, pady=12)

    def _btn(self, parent, text, color, cmd):
        return tk.Button(parent, text=text, command=cmd, bg=color, fg=FG, activebackground=ACCENT,
                         activeforeground=FG, relief="flat", padx=12, pady=7, font=("Segoe UI", 10, "bold"))

    def set_status(self, s):
        self.status.config(text=s)

    # --- list handling
    def rows(self):
        """Merge saved devices with live discovery results -> list of (device dict, online bool)."""
        out = []
        live = {p["name"]: p for p in self.found}
        for d in self.cfg["devices"]:
            p = live.pop(d["name"], None)
            if p:
                d.update({"ip": p["ip"], "port": p["port"], "user": p["user"]})
            out.append((d, p is not None))
        for p in live.values():           # found but not saved yet (no PIN)
            out.append(({"name": p["name"], "ip": p["ip"], "port": p["port"], "user": p["user"], "pin": ""}, True))
        return out

    def refresh_list(self):
        keep = self.selected_name()
        self.tree.delete(*self.tree.get_children())
        self._rows = self.rows()
        for d, online in self._rows:
            label = d["name"] + (" *" if d["name"] == self.cfg.get("default") else "")
            state = ("online" if online else "offline") + ("" if d["pin"] else ", no PIN")
            self.tree.insert("", "end", iid=d["name"], values=(label, "%s:%d" % (d["ip"], d["port"]), state))
        if keep and self.tree.exists(keep):
            self.tree.selection_set(keep)
        elif self._rows:
            self.tree.selection_set(self._rows[0][0]["name"])

    def selected_name(self):
        s = self.tree.selection()
        return s[0] if s else None

    def selected_device(self):
        n = self.selected_name()
        for d, _ in self._rows:
            if d["name"] == n:
                return d
        return None

    def on_select(self, _e=None):
        d = self.selected_device()
        self.pin.set(d["pin"] if d else "")

    # --- one tap from the phone
    def open_remote(self, dev):
        """The phone (LANShare app) asked this PC to open its storage."""
        upsert_device(self.cfg, dev)
        if not self.cfg.get("default"):
            self.cfg["default"] = dev["name"]
        save_config(self.cfg)
        self.refresh_list()
        self.set_status("Opened %s (%s) in Explorer." % (dev["name"], dev["ip"]))
        try:
            open_in_explorer(dev)
        except Exception as e:
            self.set_status("Cannot start Explorer: %s" % e)

    # --- actions
    def scan(self, auto_open=False):
        self.set_status("Looking for phones...")
        threading.Thread(target=self._scan_worker, args=(bool(auto_open),), daemon=True).start()

    def _scan_worker(self, auto_open):
        found = discover()
        self.after(0, self._scan_done, found, auto_open)

    def _scan_done(self, found, auto_open):
        self.found = found
        self.refresh_list()
        online = sum(1 for _, o in self._rows if o)
        self.set_status("%d phone(s) online." % online if online else
                        "No phone found. Is sharing ON in the phone app and are both on the same Wi-Fi?")
        if auto_open:
            self.open_default()

    def open_default(self):
        d = next((x for x, o in self._rows if o and x["name"] == self.cfg.get("default") and x["pin"]), None)
        if not d:
            d = next((x for x, o in self._rows if o and x["pin"]), None)
        if d:
            self.open_device(d)

    def open_device(self, d):
        if not d["pin"]:
            messagebox.showinfo("Phone Explorer", "Enter the PIN shown in the phone app and press Save.")
            return
        upsert_device(self.cfg, d)
        if not self.cfg.get("default"):
            self.cfg["default"] = d["name"]
        save_config(self.cfg)
        self.set_status("Opening %s (%s) in Explorer..." % (d["name"], d["ip"]))
        try:
            open_in_explorer(d)
        except Exception as e:
            self.set_status("Cannot start Explorer: %s" % e)

    def on_open(self):
        d = self.selected_device()
        if not d:
            self.set_status("Select a phone first (press Scan).")
            return
        pin = self.pin.get().strip()
        if pin:
            d["pin"] = pin
        self.open_device(d)

    def on_save_pin(self):
        d = self.selected_device()
        if d and self.pin.get().strip():
            d["pin"] = self.pin.get().strip()
            upsert_device(self.cfg, d)
            save_config(self.cfg)
            self.refresh_list()
            self.set_status("Saved PIN for %s." % d["name"])

    def on_forget(self):
        n = self.selected_name()
        if n and messagebox.askyesno("Phone Explorer", "Forget saved phone '%s'?" % n):
            self.cfg["devices"] = [d for d in self.cfg["devices"] if d["name"] != n]
            if self.cfg.get("default") == n:
                self.cfg["default"] = ""
            save_config(self.cfg)
            self.refresh_list()

    def on_default(self):
        d = self.selected_device()
        if d:
            upsert_device(self.cfg, d)
            self.cfg["default"] = d["name"]
            save_config(self.cfg)
            self.refresh_list()
            self.set_status("%s is now the default phone." % d["name"])

    def on_auto(self):
        self.cfg["auto_open"] = bool(self.auto.get())
        save_config(self.cfg)

    def _exec_pass_paint(self):
        self.exec_pass_label.config(text="Password is set." if self.cfg.get("exec_hash")
                                    else "No password set - the phone cannot run commands until you save one.")

    def on_save_exec_pass(self):
        pw = self.exec_pass_var.get()
        if len(pw) < 4:
            messagebox.showinfo("Phone Explorer", "Use at least 4 characters.")
            return
        salt = secrets.token_hex(16)
        self.cfg["exec_salt"], self.cfg["exec_hash"] = salt, hash_pass(pw, salt)
        save_config(self.cfg)
        self.exec_pass_var.set("")
        _fails.clear()
        self._exec_pass_paint()
        self.set_status("Remote-command password saved. Phones must use the new one now.")

    def _exec_paint(self):
        on = bool(self.cfg.get("exec_enabled"))
        self.exec_btn.config(text="Remote commands: ON  (click to turn off)" if on else "Remote commands: OFF  (click to turn on)",
                             bg="#2e7d32" if on else "#555555", activebackground="#2e7d32" if on else "#555555")

    def on_exec_toggle(self):
        self.cfg["exec_enabled"] = not self.cfg.get("exec_enabled")
        save_config(self.cfg)
        self._exec_paint()
        self.set_status("Remote commands from the phone are now " +
                        ("ON." if self.cfg["exec_enabled"] else "OFF."))

    def on_startup(self):
        ok = set_startup(bool(self.startup.get()))
        if not ok:
            self.startup.set(startup_enabled())
            self.set_status("Could not change the Windows startup entry (Windows only).")
        else:
            self.set_status("Will start with Windows." if self.startup.get() else "Will not start with Windows.")

    def import_link(self, link, silent=False):
        d = parse_pairing(link)
        if not d:
            if not silent:
                messagebox.showerror("Phone Explorer", "That is not a valid pairing link.")
            return
        upsert_device(self.cfg, d)
        if not self.cfg.get("default"):
            self.cfg["default"] = d["name"]
        save_config(self.cfg)
        self.link.set("")
        self.refresh_list()
        if self.tree.exists(d["name"]):
            self.tree.selection_set(d["name"])
        self.set_status("Paired with %s (%s)." % (d["name"], d["ip"]))

    # --- drive mapping
    def on_map(self):
        if self.mount and self.mount[0].poll() is None:
            self.mount[0].terminate()
            self.set_status("Drive %s: unmounted." % self.mount[1])
            self.mount = None
            self.btn_map.config(text="Map as drive")
            return
        d = self.selected_device()
        if not d or not d["pin"]:
            self.set_status("Select a phone with a saved PIN first.")
            return
        if sys.platform != "win32":
            self.set_status("Drive mapping works on Windows only.")
            return
        if not rclone_path() or not winfsp_installed():
            messagebox.showinfo(
                "Map as drive - one-time setup",
                "Windows cannot mount FTP as a drive by itself. Install the two free tools, then try again:\n\n"
                "  winget install WinFsp.WinFsp\n  winget install Rclone.Rclone\n\n"
                "(Opening the phone in Explorer works without them.)")
            return
        try:
            letter = self.letter.get()
            p = start_drive_mount(d, letter)
        except Exception as e:
            self.set_status("Mount failed: %s" % e)
            return
        self.cfg["drive"] = letter
        save_config(self.cfg)
        self.mount = (p, letter, d["name"])
        self.btn_map.config(text="Unmount %s:" % letter)
        self.after(2500, lambda: subprocess.Popen(["explorer.exe", letter + ":\\"]))
        self.set_status("Mapping %s as %s: ..." % (d["name"], letter))

    # --- tray / close
    def tray_image(self):
        im = Image.new("RGB", (64, 64), "#0F1720")
        dr = ImageDraw.Draw(im)
        dr.rounded_rectangle((18, 6, 46, 58), 6, outline="#3B82F6", width=4)
        dr.ellipse((29, 48, 35, 54), fill="#3B82F6")
        return im

    def to_tray(self):
        if not pystray:
            return
        self.withdraw()
        if self.tray:
            return
        menu = pystray.Menu(
            pystray.MenuItem("Open default phone", lambda: self.after(0, self.tray_open), default=True),
            pystray.MenuItem("Show window", lambda: self.after(0, self.from_tray)),
            pystray.MenuItem("Quit", lambda: self.after(0, self.quit_app)))
        self.tray = pystray.Icon("PhoneExplorer", self.tray_image(), "Phone Explorer", menu)
        self.tray.run_detached()

    def tray_open(self):
        self.scan(auto_open=True)

    def from_tray(self):
        self.deiconify()
        self.lift()

    def on_close(self):
        if pystray:
            self.to_tray()              # keeps running in the background; quit via the tray menu
        else:
            self.iconify()              # no tray library: minimise instead of stopping the listener

    def quit_app(self):
        if self.mount and self.mount[0].poll() is None:
            self.mount[0].terminate()
        if self.tray:
            try:
                self.tray.stop()
            except Exception:
                pass
        self.destroy()


if __name__ == "__main__":
    if "--install-startup" in sys.argv or "--uninstall-startup" in sys.argv:
        on = "--install-startup" in sys.argv
        sys.exit(0 if set_startup(on) else 1)
    if sys.platform == "win32" and not is_admin():
        # Always runs as Administrator: ask for elevation (UAC); if it is declined or fails, ask again, then quit.
        for _ in range(2):
            if relaunch_elevated():
                sys.exit(0)
        try:
            ctypes.windll.user32.MessageBoxW(None, "Phone Explorer must run as Administrator. Please accept the Windows prompt.",
                                             "Phone Explorer", 0x10)
        except Exception:
            pass
        sys.exit(1)
    if sys.platform != "win32":
        print("Note: Explorer launch and drive mapping only work on Windows; discovery still runs.")
    App(sys.argv[1:]).mainloop()
