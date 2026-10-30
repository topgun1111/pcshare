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
import base64
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


# ------------------------------------------------------------------------------------ terminal (Remote shell)
# One REAL shell per session (Windows ConPTY: cmd / PowerShell with prompt, history, Tab, Ctrl+C, cd that stays...).
# The phone shows it with xterm.js. Protocol (POST /term, form body, password like /exec):
#   act=open   sid shell(cmd|ps) cols rows   -> starts the session (same sid again = the same session, never a second shell)
#   act=read   sid o wait                    -> long-poll: raw terminal bytes (base64) after offset o; {data,o,alive,code,reset}
#   act=in     sid seq data(base64)          -> keys typed on the phone (seq counts up: a repeated request is not typed twice)
#   act=resize sid cols rows
#   act=close  sid
TERM_BUF_MAX = 4 << 20      # bytes of output kept per session (a phone that reconnects gets the rest of it)
TERM_SESS_MAX = 3
TERM_IDLE = 300             # s: the phone normally ends its shell itself (act=close on Back / app closed). If it just vanished
                            # (app killed, phone off, no network) nothing asks about the session for this long -> the reaper closes it
_ID_RE_T = re.compile(r"[A-Za-z0-9_-]{8,64}")
_terms = {}
_terms_lock = threading.Lock()
_term_reaper_on = False


def _term_reaper():
    """Closes shells that the phone stopped asking about (TERM_IDLE s), and ended shells 60 s after the last request."""
    while True:
        time.sleep(10)
        now = time.time()
        gone = []
        with _terms_lock:
            for k, t in list(_terms.items()):
                idle = now - t.touch
                if idle > TERM_IDLE or (t.dead and idle > 60):
                    del _terms[k]
                    gone.append(t)
        for t in gone:
            threading.Thread(target=t.close, daemon=True).start()


class _ConPty:
    """Windows 10 1809+ pseudo console through ctypes (nothing to install)."""

    def __init__(self, cmdline, cols, rows, cwd, flags=0x00080000):   # EXTENDED_STARTUPINFO_PRESENT only (as in Microsoft's sample); CREATE_NO_WINDOW (0x08000000) gave a silent console
        import ctypes.wintypes as wt
        k32 = ctypes.windll.kernel32
        self.k32, self.wt = k32, wt

        class COORD(ctypes.Structure):
            _fields_ = [("X", ctypes.c_short), ("Y", ctypes.c_short)]

        class STARTUPINFOW(ctypes.Structure):
            _fields_ = [("cb", wt.DWORD), ("lpReserved", wt.LPWSTR), ("lpDesktop", wt.LPWSTR), ("lpTitle", wt.LPWSTR),
                        ("dwX", wt.DWORD), ("dwY", wt.DWORD), ("dwXSize", wt.DWORD), ("dwYSize", wt.DWORD),
                        ("dwXCountChars", wt.DWORD), ("dwYCountChars", wt.DWORD), ("dwFillAttribute", wt.DWORD),
                        ("dwFlags", wt.DWORD), ("wShowWindow", wt.WORD), ("cbReserved2", wt.WORD),
                        ("lpReserved2", ctypes.c_void_p), ("hStdInput", wt.HANDLE), ("hStdOutput", wt.HANDLE),
                        ("hStdError", wt.HANDLE)]

        class STARTUPINFOEXW(ctypes.Structure):
            _fields_ = [("StartupInfo", STARTUPINFOW), ("lpAttributeList", ctypes.c_void_p)]

        class PROCESS_INFORMATION(ctypes.Structure):
            _fields_ = [("hProcess", wt.HANDLE), ("hThread", wt.HANDLE), ("dwProcessId", wt.DWORD), ("dwThreadId", wt.DWORD)]

        self.COORD = COORD
        for fn, res, args in (
                ("CreatePipe", wt.BOOL, [ctypes.POINTER(wt.HANDLE), ctypes.POINTER(wt.HANDLE), ctypes.c_void_p, wt.DWORD]),
                ("CreatePseudoConsole", ctypes.c_long, [COORD, wt.HANDLE, wt.HANDLE, wt.DWORD, ctypes.POINTER(ctypes.c_void_p)]),
                ("ResizePseudoConsole", ctypes.c_long, [ctypes.c_void_p, COORD]),
                ("ClosePseudoConsole", None, [ctypes.c_void_p]),
                ("InitializeProcThreadAttributeList", wt.BOOL, [ctypes.c_void_p, wt.DWORD, wt.DWORD, ctypes.POINTER(ctypes.c_size_t)]),
                ("UpdateProcThreadAttribute", wt.BOOL, [ctypes.c_void_p, wt.DWORD, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_void_p]),
                ("DeleteProcThreadAttributeList", None, [ctypes.c_void_p]),
                ("CreateProcessW", wt.BOOL, [wt.LPCWSTR, wt.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, wt.BOOL, wt.DWORD, ctypes.c_void_p, wt.LPCWSTR, ctypes.c_void_p, ctypes.POINTER(PROCESS_INFORMATION)]),
                ("ReadFile", wt.BOOL, [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]),
                ("WriteFile", wt.BOOL, [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]),
                ("CloseHandle", wt.BOOL, [wt.HANDLE]),
                ("WaitForSingleObject", wt.DWORD, [wt.HANDLE, wt.DWORD]),
                ("GetExitCodeProcess", wt.BOOL, [wt.HANDLE, ctypes.POINTER(wt.DWORD)]),
                ("TerminateProcess", wt.BOOL, [wt.HANDLE, wt.UINT])):
            f = getattr(k32, fn)
            f.restype, f.argtypes = res, args
        in_r, in_w, out_r, out_w = wt.HANDLE(), wt.HANDLE(), wt.HANDLE(), wt.HANDLE()
        if not k32.CreatePipe(ctypes.byref(in_r), ctypes.byref(in_w), None, 0) or not k32.CreatePipe(ctypes.byref(out_r), ctypes.byref(out_w), None, 0):
            raise OSError("CreatePipe failed")
        self.hpc = ctypes.c_void_p()
        hr = k32.CreatePseudoConsole(COORD(cols, rows), in_r, out_w, 0, ctypes.byref(self.hpc))
        if hr != 0:
            raise OSError("this Windows has no pseudo console (needs Windows 10 version 1809 or newer), code 0x%08X" % (hr & 0xFFFFFFFF))
        k32.CloseHandle(in_r)       # the console owns these ends now
        k32.CloseHandle(out_w)
        self.h_in, self.h_out = in_w, out_r
        size = ctypes.c_size_t(0)
        k32.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(size))
        self._attr = ctypes.create_string_buffer(size.value)
        if not k32.InitializeProcThreadAttributeList(self._attr, 1, 0, ctypes.byref(size)):
            raise OSError("InitializeProcThreadAttributeList failed")
        if not k32.UpdateProcThreadAttribute(self._attr, 0, 0x00020016, self.hpc, ctypes.sizeof(ctypes.c_void_p), None, None):   # PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE
            raise OSError("UpdateProcThreadAttribute failed")
        si = STARTUPINFOEXW()
        si.StartupInfo.cb = ctypes.sizeof(STARTUPINFOEXW)
        si.lpAttributeList = ctypes.cast(self._attr, ctypes.c_void_p)
        pi = PROCESS_INFORMATION()
        cmd = ctypes.create_unicode_buffer(cmdline)
        if not k32.CreateProcessW(None, cmd, None, None, False, flags, None, cwd, ctypes.byref(si), ctypes.byref(pi)):   # EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW
            err = ctypes.GetLastError()
            raise OSError("could not start the shell (error %d)" % err)
        self.h_proc, self.pid = pi.hProcess, pi.dwProcessId
        k32.CloseHandle(pi.hThread)
        self.closed = False

    def read(self):
        buf = ctypes.create_string_buffer(65536)
        n = self.wt.DWORD(0)
        if not self.k32.ReadFile(self.h_out, buf, 65536, ctypes.byref(n), None) or n.value == 0:
            return b""
        return buf.raw[:n.value]

    def write(self, data):
        n = self.wt.DWORD(0)
        i = 0
        while i < len(data):
            chunk = data[i:i + 4096]
            if not self.k32.WriteFile(self.h_in, chunk, len(chunk), ctypes.byref(n), None) or n.value == 0:
                return False
            i += n.value
        return True

    def resize(self, cols, rows):
        self.k32.ResizePseudoConsole(self.hpc, self.COORD(cols, rows))

    def alive(self):
        return self.k32.WaitForSingleObject(self.h_proc, 0) == 258        # WAIT_TIMEOUT

    def exit_code(self):
        c = self.wt.DWORD(0)
        self.k32.GetExitCodeProcess(self.h_proc, ctypes.byref(c))
        return int(c.value)

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            if self.alive():
                kill_pid_tree(self.pid)
                self.k32.TerminateProcess(self.h_proc, 1)
        except Exception:
            pass
        try:
            self.k32.ClosePseudoConsole(self.hpc)       # also ends a blocked read()
        except Exception:
            pass
        for h in (self.h_in, self.h_out, self.h_proc):
            try:
                self.k32.CloseHandle(h)
            except Exception:
                pass
        try:
            self.k32.DeleteProcThreadAttributeList(self._attr)
        except Exception:
            pass


class _PosixPty:
    """Same interface on Linux / macOS: only used to test the protocol away from Windows."""

    def __init__(self, cmdline, cols, rows, cwd):
        import pty, fcntl, termios, struct
        self._fcntl, self._termios, self._struct = fcntl, termios, struct
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                os.chdir(cwd or os.path.expanduser("~"))
            except Exception:
                pass
            os.environ["TERM"] = "xterm-256color"
            os.execvp("/bin/sh", ["/bin/sh", "-c", cmdline])
        self.resize(cols, rows)
        self.closed = False
        self._code = None

    def read(self):
        try:
            return os.read(self.fd, 65536)
        except OSError:
            return b""

    def write(self, data):
        try:
            os.write(self.fd, data)
            return True
        except OSError:
            return False

    def resize(self, cols, rows):
        try:
            self._fcntl.ioctl(self.fd, self._termios.TIOCSWINSZ, self._struct.pack("HHHH", rows, cols, 0, 0))
        except Exception:
            pass

    def alive(self):
        if self._code is not None:
            return False
        try:
            p, st = os.waitpid(self.pid, os.WNOHANG)
        except ChildProcessError:
            self._code = 0
            return False
        if p == 0:
            return True
        self._code = os.waitstatus_to_exitcode(st)
        return False

    def exit_code(self):
        self.alive()
        return self._code if self._code is not None else -1

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            os.kill(self.pid, signal.SIGHUP)
        except Exception:
            pass
        try:
            os.close(self.fd)
        except Exception:
            pass
        try:
            os.waitpid(self.pid, os.WNOHANG)
        except Exception:
            pass


def kill_pid_tree(pid):
    try:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True, timeout=10, creationflags=_NO_WIN)
    except Exception:
        pass


def _shell_cmdline(shell):
    if sys.platform == "win32":
        if shell == "ps":
            sysroot = os.environ.get("SystemRoot", r"C:\Windows")
            return r'"%s\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo' % sysroot
        return os.environ.get("COMSPEC", "cmd.exe")
    return "bash -i" if shutil.which("bash") else "sh -i"


class TermSession:
    def __init__(self, sid, shell, cols, rows):
        self.id, self.shell = sid, shell
        self.cols, self.rows = cols, rows
        self.buf = bytearray()
        self.base = 0                      # absolute offset of buf[0]
        self.cv = threading.Condition()
        self.seq = 0                       # last typed-input batch applied
        self.in_lock = threading.Lock()
        self.touch = time.time()
        self.dead = False
        self.code = None
        cwd = os.path.expanduser("~")
        cmd = _shell_cmdline(shell)
        self.pty = (_ConPty if sys.platform == "win32" else _PosixPty)(cmd, cols, rows, cwd if os.path.isdir(cwd) else None)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        try:
            while True:
                d = self.pty.read()
                if not d:
                    break
                with self.cv:
                    self.buf += d
                    if len(self.buf) > TERM_BUF_MAX:
                        cut = len(self.buf) - TERM_BUF_MAX
                        del self.buf[:cut]
                        self.base += cut
                    self.cv.notify_all()
        except Exception:
            pass
        finally:
            time.sleep(0.2)
            try:
                self.code = self.pty.exit_code()
            except Exception:
                self.code = -1
            with self.cv:
                self.dead = True
                self.cv.notify_all()

    def read(self, o, wait):
        """Bytes after absolute offset o (waits up to [wait] s for some). Same o again = same answer (nothing is consumed)."""
        self.touch = time.time()
        end = time.time() + wait
        with self.cv:
            reset = False
            if o < self.base:
                o, reset = self.base, True      # the phone was away longer than the buffer holds
            while o >= self.base + len(self.buf) and not self.dead:
                left = end - time.time()
                if left <= 0:
                    break
                self.cv.wait(min(left, 1.0))
            data = bytes(self.buf[o - self.base:o - self.base + 262144])
            return {"data": base64.b64encode(data).decode(), "from": o, "o": o + len(data), "alive": not (self.dead and o + len(data) >= self.base + len(self.buf)),
                    "code": self.code, "reset": reset}

    def write(self, seq, data):
        self.touch = time.time()
        with self.in_lock:
            if seq and seq <= self.seq:
                return True                  # this batch was already typed (the answer got lost, the phone asked again)
            ok = self.pty.write(data)
            if ok and seq:
                self.seq = seq
            return ok

    def resize(self, cols, rows):
        self.touch = time.time()
        self.cols, self.rows = cols, rows
        self.pty.resize(cols, rows)

    def close(self):
        self.pty.close()


def term_open(sid, shell, cols, rows):
    global _term_reaper_on
    with _terms_lock:
        if not _term_reaper_on:
            _term_reaper_on = True
            threading.Thread(target=_term_reaper, daemon=True).start()
        now = time.time()
        for k, t in list(_terms.items()):
            if t.dead and now - t.touch > 60 or now - t.touch > TERM_IDLE:
                del _terms[k]
                threading.Thread(target=t.close, daemon=True).start()
        t = _terms.get(sid)
        if t is not None:
            return t, True
        if len(_terms) >= TERM_SESS_MAX:
            raise RuntimeError("too many terminals are open on the PC already")
        t = TermSession(sid, shell, cols, rows)
        _terms[sid] = t
    _keep_awake_term()
    return t, False


def _keep_awake_term():
    """Same idea as for commands: a PC that goes to sleep drops the connection."""
    if sys.platform != "win32":
        return

    def loop():
        try:
            while True:
                ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
                time.sleep(5)
                with _terms_lock:
                    if not any(not t.dead for t in _terms.values()):
                        break
        except Exception:
            pass
        try:
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
        except Exception:
            pass
    threading.Thread(target=loop, daemon=True).start()


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
                if u.path == "/term":
                    return self._do_term(q)
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

        def _auth(self, q):
            """Same checks as /exec (remote commands ON, password, lock after 5 wrong ones). Returns True, or answers the error and returns False."""
            cfg = live_cfg(app)
            if not cfg.get("exec_enabled"):
                self._reply(403, {"error": "Remote commands are turned off on this PC"})
                return False
            ip = self.client_address[0].split("%")[0]
            f = _fails.setdefault(ip, [0, 0.0])
            if time.time() < f[1]:
                self._reply(429, {"error": "too many wrong passwords - try again in a minute"})
                return False
            if not cfg.get("exec_hash"):
                self._reply(403, {"error": "No password is set on the PC (Phone Explorer window -> Remote commands)"})
                return False
            if not check_pass(cfg, q.get("pass", [""])[0][:200]):
                f[0] += 1
                if f[0] >= 5:
                    f[0] = 0
                    f[1] = time.time() + 60
                self._reply(403, {"error": "wrong password"})
                return False
            f[0] = 0
            return True

        def _do_term(self, q):
            """Real terminal session (see the protocol at TermSession). Every request is safe to repeat."""
            if not self._auth(q):
                return
            g = lambda k, d="": q.get(k, [d])[0]
            act, sid = g("act"), g("sid")
            if not _ID_RE_T.fullmatch(sid):
                return self._reply(400, {"error": "bad session id"})

            def num(k, d, lo, hi):
                try:
                    return max(lo, min(hi, int(g(k, str(d)))))
                except ValueError:
                    return d
            cols, rows = num("cols", 80, 20, 500), num("rows", 24, 5, 200)
            if act == "open":
                try:
                    t, existed = term_open(sid, "ps" if g("shell") == "ps" else "cmd", cols, rows)
                except (RuntimeError, OSError) as e:
                    return self._reply(500 if isinstance(e, OSError) else 429, {"error": str(e)})
                if existed:
                    t.resize(cols, rows)
                else:
                    try:
                        app.after(0, app.set_status, "Remote shell opened")
                    except Exception:
                        pass
                return self._reply(200, {"ok": True, "existed": existed, "base": t.base, "end": t.base + len(t.buf), "alive": not t.dead, "seq": t.seq})
            with _terms_lock:
                t = _terms.get(sid)
            if t is None:
                if act == "close":      # already gone (reaped, or the program was restarted): closing twice is fine
                    return self._reply(200, {"ok": True})
                return self._reply(404, {"error": "terminal not found - it was closed or the Phone Explorer program on the PC was restarted"})
            if act == "read":
                wait = max(0.0, min(25.0, float(num("wait", 20, 0, 25))))
                return self._reply(200, t.read(num("o", 0, 0, 1 << 62), wait))
            if act == "in":
                try:
                    data = base64.b64decode(g("data"), validate=False)[:65536]
                except Exception:
                    return self._reply(400, {"error": "bad data"})
                if t.dead:
                    return self._reply(409, {"error": "the shell has ended"})
                return self._reply(200, {"ok": bool(t.write(num("seq", 0, 0, 1 << 62), data))})
            if act == "resize":
                t.resize(cols, rows)
                return self._reply(200, {"ok": True})
            if act == "close":
                with _terms_lock:
                    _terms.pop(sid, None)
                threading.Thread(target=t.close, daemon=True).start()
                return self._reply(200, {"ok": True})
            return self._reply(400, {"error": "unknown act"})

        def _do_exec(self, q):
            """Runs a command line on this PC and returns its output. Off by default - the person at the PC
            must press the "Remote commands" button to turn it ON, and the phone must send the password.
            No id = old behaviour (one request waits for the end). With id = job: the first request starts it, later
            ones (poll=1, or the same request again) wait up to `wait` s for the end and answer {"done": false} if it runs on."""
            if q.get("probe"):      # the phone asks which version this is (no command, no password)
                return self._reply(200, {"v": 3, "term": 1, "exec": bool(live_cfg(app).get("exec_enabled"))})
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
