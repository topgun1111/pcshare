#!/usr/bin/env python3
"""
pcprint.py - one-file print service for the LANShare Android app (Windows; Linux/macOS via `lp`).

Run it (double-click or `python pcprint.py`). It sets everything up, then opens a window with the live print log.
The window is only a viewer: the print service runs separately in the background, so closing the window
(even by mistake) does NOT stop printing. Run the file again any time to reopen the window.
First run sets everything up:
  - installs itself to %APPDATA%\\LANSharePrint and starts with Windows
  - opens the firewall port for the LAN and Tailscale (one UAC prompt, first run only)
  - installs SumatraPDF via winget if missing (silent PDF printing; optional)
  - listens on port 8799 and prints whatever the app sends ("Print on PC") on the default printer
Python stdlib only.
"""
import ctypes, ipaddress, json, os, queue, re, shutil, socket, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8799
VERSION = "6"
WIN = os.name == "nt"
MAX_BYTES = 300 << 20
APP = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "LANSharePrint") if WIN else os.path.expanduser("~/.lansharep")
INBOX = os.path.join(APP, "queue")
LOG = os.path.join(APP, "log.txt")
TARGET = os.path.join(APP, "pcprint.py")
FW_RULE = "LANSharePrint-TS"   # v3 rule: LAN + Tailscale; the old "LANSharePrint" rule is removed
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
# only document/image types are printed: anything else is refused so the app can never make the PC run a program
ALLOWED = {".pdf", ".txt", ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tif", ".tiff", ".doc", ".docx", ".rtf", ".odt",
           ".xls", ".xlsx", ".csv", ".ppt", ".pptx", ".odp", ".ods", ".log", ".md"}
TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")        # Tailscale (CGNAT) addresses
TAILSCALE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")
IMAGES = {".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tif", ".tiff"}


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    try:
        os.makedirs(APP, exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        if os.path.getsize(LOG) > 500_000:
            os.replace(LOG, LOG + ".old")
    except OSError:
        pass
    if sys.stdout:
        try: print(line)
        except Exception: pass


# ------------------------------------------------------------------ printing
def find_sumatra():
    for p in (shutil.which("SumatraPDF"),
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "SumatraPDF", "SumatraPDF.exe"),
              os.path.join(os.environ.get("ProgramFiles", ""), "SumatraPDF", "SumatraPDF.exe"),
              os.path.join(os.environ.get("ProgramFiles(x86)", ""), "SumatraPDF", "SumatraPDF.exe")):
        if p and os.path.isfile(p):
            return p
    return None


# Silent image printing through .NET (Windows 11's new Paint ignores "/pt" and just opens the picture)
PS_IMG = r"""
param([string]$Path)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($Path)
try {
  $pd = New-Object System.Drawing.Printing.PrintDocument
  $pd.DefaultPageSettings.Landscape = ($img.Width -gt $img.Height)
  $pd.add_PrintPage({
    param($sender, $e)
    $b = $e.PageBounds; $m = 25
    $w = $b.Width - 2 * $m; $h = $b.Height - 2 * $m
    $k = [Math]::Min($w / $img.Width, $h / $img.Height)
    $dw = $img.Width * $k; $dh = $img.Height * $k
    $r = New-Object System.Drawing.RectangleF(($b.X + $m + ($w - $dw) / 2), ($b.Y + $m + ($h - $dh) / 2), $dw, $dh)
    $e.Graphics.DrawImage($img, $r)
    $e.HasMorePages = $false
  }.GetNewClosure())
  $pd.Print()
} finally { $img.Dispose() }
"""


def print_image(path):
    ps1 = os.path.join(APP, "printimg.ps1")
    with open(ps1, "w", encoding="utf-8-sig") as f:
        f.write(PS_IMG)
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path],
                       capture_output=True, text=True, timeout=120, creationflags=0x08000000)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout or f"exit {r.returncode}").strip()[:300])


def print_file(path):
    ext = os.path.splitext(path)[1].lower()
    if not WIN:
        subprocess.run(["lp", path], check=True, timeout=60)
        return
    if ext == ".pdf":
        sm = find_sumatra()
        if sm:
            subprocess.run([sm, "-print-to-default", "-silent", path], check=True, timeout=180)
            return
    if ext in IMAGES:
        try:
            print_image(path)
            return
        except Exception as e:
            log(f"image print failed ({e}), falling back to shell print")
    os.startfile(path, "print")   # whatever app is registered for this file type prints it on the default printer


PRINTQ = queue.Queue()
DEFAULT_PRINTER = [None]   # refreshed regularly; shown in the window and reported to the app
PRINTER_PROBLEM = [None]   # e.g. "paper jam" (None = nothing reported)

# One PowerShell call: default printer + its status + the states of the jobs waiting in its Windows queue.
PS_STATE = r"""
$d = Get-CimInstance Win32_Printer | Where-Object { $_.Default }
if ($d) {
  $p = Get-Printer -Name $d.Name -ErrorAction SilentlyContinue
  $j = @(Get-PrintJob -PrinterName $d.Name -ErrorAction SilentlyContinue | ForEach-Object { [string]$_.JobStatus })
  [pscustomobject]@{ name=$d.Name; status=[string]$p.PrinterStatus; offline=[bool]$d.WorkOffline; detected=[int]$d.DetectedErrorState; port=[string]$p.PortName; jobs=$j } | ConvertTo-Json -Compress
}
"""
STATUS_TEXT = {   # Get-Printer PrinterStatus -> plain words
    "PaperJam": "paper jam", "PaperOut": "out of paper", "PaperProblem": "paper problem", "Offline": "offline or asleep",
    "OutputBinFull": "output tray full", "NoToner": "out of toner/ink", "TonerLow": "low on toner/ink",
    "UserIntervention": "needs attention at the printer", "DoorOpen": "cover or door open", "Error": "reports an error",
    "OutOfMemory": "printer out of memory", "Paused": "print queue is paused", "NotAvailable": "not available",
    "ManualFeed": "waiting for manual paper feed"}
DETECTED_TEXT = {3: "low on paper", 4: "out of paper", 5: "low on toner/ink", 6: "out of toner/ink", 7: "cover or door open",
                 8: "paper jam", 9: "offline or asleep", 10: "needs service", 11: "output tray full"}
JOB_TEXT = (("PaperOut", "out of paper"), ("UserIntervention", "needs attention at the printer"), ("Offline", "offline"),
            ("Blocked", "queue is blocked"), ("Error", "print job error"), ("Paused", "job is paused"))


def describe_problem(info):
    """Plain-language problem from a printer-info dict, or None. Only what the printer/driver actually reports."""
    if not info:
        return None
    if info.get("offline"):
        return "set to 'Use printer offline' in Windows"
    st = STATUS_TEXT.get(info.get("status") or "")
    return st or DETECTED_TEXT.get(info.get("detected"))


def job_problem(info):
    for flag, text in JOB_TEXT:
        if any(flag in (j or "") for j in info.get("jobs", [])):
            return text
    return None


def printer_info():
    if not WIN:
        return None
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", PS_STATE], capture_output=True, text=True,
                             timeout=30, creationflags=0x08000000).stdout.strip()
        info = json.loads(out) if out else None
        if info:
            if isinstance(info.get("jobs"), str):
                info["jobs"] = [info["jobs"]]
            info["jobs"] = info.get("jobs") or []
        return info
    except Exception:
        return None


def refresh_printer():
    while True:
        try:
            if WIN:
                info = printer_info()
                DEFAULT_PRINTER[0] = info["name"] if info else None
                PRINTER_PROBLEM[0] = describe_problem(info)
            else:
                out = subprocess.run(["lpstat", "-d"], capture_output=True, text=True, timeout=10).stdout.split(":")[-1].strip()
                DEFAULT_PRINTER[0] = out or None
        except Exception:
            DEFAULT_PRINTER[0] = None
        time.sleep(20)


def watch_printer(seconds=10):
    """After a file was handed over: wait a few seconds and report what the printer says if the job gets stuck (else None)."""
    if not WIN:
        return None
    end = time.time() + seconds
    issue = None
    while time.time() < end:
        time.sleep(2)
        info = printer_info()
        if not info:
            return None
        if not info["jobs"]:
            return None   # the job left the Windows queue: the printer took it
        issue = job_problem(info) or describe_problem(info)
        if issue and not issue.startswith("low on"):
            PRINTER_PROBLEM[0] = issue
            return issue
    return None


def friendly(e, name):
    ext = os.path.splitext(name)[1] or "this file type"
    if isinstance(e, OSError) and getattr(e, "winerror", None) in (1155, 1156):
        return f"no app on the PC can print {ext} files"
    if isinstance(e, subprocess.TimeoutExpired):
        return "printing took too long"
    return (str(e) or e.__class__.__name__)[:300]


def print_worker():
    while True:
        path, evt, res = PRINTQ.get()
        name = os.path.basename(path)
        try:
            pr = DEFAULT_PRINTER[0]
            log(f"printing {name}" + (f" on {pr}" if pr else ""))
            print_file(path)
            warn = watch_printer()
            if warn:
                log(f"WARNING {name}: printer reports {warn}")
                res["warning"] = warn
            else:
                log("printed " + name)
            res.update(ok=True, printer=pr)
            evt.set()   # tell the phone
            time.sleep(4)   # let the spooler pick the file up before the next one starts
        except Exception as e:
            msg = friendly(e, name)
            log(f"PRINT FAILED {name}: {msg}")
            res["error"] = msg
            evt.set()
        PRINTQ.task_done()


def cleanup_old(days=3):
    cut = time.time() - days * 86400
    try:
        for n in os.listdir(INBOX):
            p = os.path.join(INBOX, n)
            if os.path.isfile(p) and os.path.getmtime(p) < cut:
                os.remove(p)
    except OSError:
        pass


# ------------------------------------------------------------------ HTTP service
def safe_name(n):
    n = os.path.basename((n or "file").replace("\\", "/"))
    n = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", n).strip(" .")
    return n[:120] or "file"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LANSharePrint/" + VERSION

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
        self.close_connection = True

    def local_only(self):   # LAN + Tailscale clients only, never the open internet
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
            if ip.version == 4 and ip in TAILSCALE_V4 or ip.version == 6 and ip in TAILSCALE_V6:
                return True   # a device on your own tailnet
            return ip.is_private or ip.is_loopback or ip.is_link_local
        except ValueError:
            return False

    def do_GET(self):
        if not self.local_only():
            return self.reply(403, {"error": "LAN/Tailscale only"})
        if urlparse(self.path).path == "/ping":
            return self.reply(200, {"ok": True, "name": socket.gethostname(), "version": VERSION, "queued": PRINTQ.qsize(),
                                    "printer": DEFAULT_PRINTER[0], "problem": PRINTER_PROBLEM[0]})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if not self.local_only():
            return self.reply(403, {"error": "LAN/Tailscale only"})
        u = urlparse(self.path)
        if u.path == "/stop":   # only the window on this PC may stop the service
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self.reply(403, {"error": "local only"})
            self.reply(200, {"ok": True})
            log("service stopped from the window")
            threading.Timer(0.3, lambda: os._exit(0)).start()
            return
        if u.path != "/print":
            return self.reply(404, {"error": "not found"})
        name = safe_name(parse_qs(u.query).get("name", ["file"])[0])
        ext = os.path.splitext(name)[1].lower()
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(400, {"error": "missing Content-Length"})
        if ext not in ALLOWED:
            return self.reply(415, {"error": f"{ext or 'this file type'} cannot be printed (allowed: pdf, images, office docs, txt)"})
        if n > MAX_BYTES:
            return self.reply(413, {"error": "file too large"})
        os.makedirs(INBOX, exist_ok=True)
        dest = os.path.join(INBOX, f"{time.strftime('%H%M%S')}_{name}")
        left = n
        try:
            with open(dest, "wb") as f:
                while left > 0:
                    b = self.rfile.read(min(1 << 20, left))
                    if not b:
                        raise ConnectionError("upload interrupted")
                    f.write(b)
                    left -= len(b)
        except Exception as e:
            try: os.remove(dest)
            except OSError: pass
            log(f"upload failed {name}: {e}")
            return
        log(f"received {name} ({n} bytes) from {self.client_address[0]}")
        evt, res = threading.Event(), {}
        PRINTQ.put((dest, evt, res))
        if not evt.wait(90):   # still waiting in the queue: the phone treats this as sent
            return self.reply(200, {"ok": True, "queued": True})
        if "error" in res:
            return self.reply(500, {"error": res["error"]})   # the phone shows the reason
        self.reply(200, {"ok": True, "printed": True, "printer": res.get("printer"), "warning": res.get("warning")})


def pc_addresses():
    """(all IPv4 addresses of this PC, the Tailscale one or None)."""
    found = set()
    try:
        found |= {i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
    except OSError:
        pass
    if WIN:   # getaddrinfo often misses the Tailscale adapter
        try:
            out = subprocess.run(["ipconfig"], capture_output=True, text=True, creationflags=0x08000000).stdout
            found |= set(re.findall(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", out))
        except Exception:
            pass
    ips = []
    for x in found:
        try:
            a = ipaddress.ip_address(x)
        except ValueError:
            continue
        if a.version == 4 and not a.is_loopback and not x.startswith("169.254.") and not x.endswith(".255") and not x.startswith("255.") and not x.startswith("0."):
            ips.append(x)
    ips.sort()
    ts = next((x for x in ips if ipaddress.ip_address(x) in TAILSCALE_V4), None)
    return ips, ts


def serve():
    os.makedirs(INBOX, exist_ok=True)
    cleanup_old()
    threading.Thread(target=print_worker, daemon=True).start()
    threading.Thread(target=refresh_printer, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), H)
    srv.daemon_threads = True
    ips, ts = pc_addresses()
    log(f"print service ready on port {PORT} (this PC: {', '.join(ips)}{'; Tailscale: ' + ts if ts else ''})")
    srv.serve_forever()


# ------------------------------------------------------------------ one-time setup (Windows)
def ping():
    """Info dict of the local print service, or None if it is not running."""
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=1.5) as s:
            s.sendall(b"GET /ping HTTP/1.0\r\n\r\n")
            data = b""
            while True:
                b = s.recv(4096)
                if not b: break
                data += b
        return json.loads(data.split(b"\r\n\r\n", 1)[1].decode())
    except Exception:
        return None


def is_running():
    return ping() is not None


def stop_service():
    try:
        socket.create_connection(("127.0.0.1", PORT), timeout=1.5).close()
        import urllib.request
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/stop", data=b"", method="POST"), timeout=3).read()
    except Exception:
        pass
    for _ in range(10):
        if not is_running(): return True
        time.sleep(0.3)
    if WIN:   # old version without /stop: kill whatever listens on the port
        out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True, creationflags=0x08000000).stdout
        for line in out.splitlines():
            c = line.split()
            if len(c) >= 5 and c[3] == "LISTENING" and c[1].endswith(f":{PORT}"):
                subprocess.run(["taskkill", "/F", "/PID", c[4]], capture_output=True, creationflags=0x08000000)
        time.sleep(1)
    return not is_running()


def start_service():
    """Make sure the current version of the service runs in the background (detached, no window)."""
    info = ping()
    if info and info.get("version") != VERSION:
        stop_service()
        info = None
    if not info:
        subprocess.Popen([pyw(), TARGET, "--serve"], creationflags=0x00000008 | 0x08000000, close_fds=True)
        for _ in range(15):
            time.sleep(0.3)
            if is_running(): break
    return is_running()


def is_admin():
    try: return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception: return False


def fw_rule_exists():
    r = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={FW_RULE}"],
                       capture_output=True, text=True, creationflags=0x08000000)
    return r.returncode == 0 and FW_RULE in r.stdout


def fw_add():
    for n in ("LANSharePrint", FW_RULE):   # drop the old LAN-only rule and any previous copy of this one
        subprocess.run(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={n}"], capture_output=True, creationflags=0x08000000)
    # any network profile (the Tailscale adapter is usually "Public"), but only from the local subnet and the tailnet range
    subprocess.run(["netsh", "advfirewall", "firewall", "add", "rule", f"name={FW_RULE}", "dir=in", "action=allow", "protocol=TCP",
                    f"localport={PORT}", "profile=any", "remoteip=LocalSubnet,100.64.0.0/10"], capture_output=True, creationflags=0x08000000)


def ensure_firewall():
    if fw_rule_exists():
        return True
    if is_admin():
        fw_add()
    else:
        print("Windows will ask for permission once, to open the print port in the firewall (LAN + Tailscale)...")
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, f'"{TARGET}" --fw', None, 0)
        for _ in range(40):
            time.sleep(1)
            if fw_rule_exists():
                break
    return fw_rule_exists()


def pyw():
    w = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return w if os.path.isfile(w) else sys.executable


def ensure_autostart():
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
        winreg.SetValueEx(k, "LANSharePrint", 0, winreg.REG_SZ, f'"{pyw()}" "{TARGET}" --serve')


def ensure_sumatra():
    if find_sumatra() or not shutil.which("winget"):
        return
    print("Installing SumatraPDF (silent PDF printing)...")
    try:
        subprocess.run(["winget", "install", "-e", "--id", "SumatraPDF.SumatraPDF", "--silent",
                        "--accept-source-agreements", "--accept-package-agreements"], timeout=300, creationflags=0x08000000)
    except Exception as e:
        log(f"SumatraPDF install skipped: {e}")


def setup_windows():
    os.makedirs(INBOX, exist_ok=True)
    me = os.path.abspath(__file__)
    if os.path.normcase(me) != os.path.normcase(TARGET):
        shutil.copyfile(me, TARGET)   # always refresh the installed copy
    fw = ensure_firewall()
    ensure_autostart()
    ensure_sumatra()
    ok = start_service()
    if not ok:
        print(f"Print service FAILED to start - see {LOG}")
        input("Press Enter to close...")
        return
    if not fw:
        log("WARNING: firewall rule was not added; the phone may not be able to connect")
    subprocess.Popen([pyw(), TARGET, "--ui"], creationflags=0x00000008 | 0x08000000, close_fds=True)   # window opens, this console closes


# ------------------------------------------------------------------ window: live print log (viewer only)
def one_window():
    """Only one window at a time (Windows named mutex)."""
    h = ctypes.windll.kernel32.CreateMutexW(None, False, "LANSharePrintWindow")
    if ctypes.windll.kernel32.GetLastError() == 183:   # ERROR_ALREADY_EXISTS
        return None
    return h


def ui():
    if WIN:
        keep = one_window()
        if keep is None:
            return
    try:
        import tkinter as tk
        from tkinter import messagebox
    except ImportError:
        print("tkinter is not installed; showing the log in this console instead (Ctrl+C to leave)")
        return tail_console()
    start_service()
    root = tk.Tk()
    root.title("LANShare Print")
    root.geometry("760x420")
    root.minsize(480, 260)
    BG, FG = "#10151f", "#d7dde8"
    root.configure(bg=BG)
    top = tk.Frame(root, bg=BG)
    top.pack(fill="x", padx=10, pady=(10, 4))
    dot = tk.Label(top, text="\u25cf", fg="#888", bg=BG, font=("Segoe UI", 14))
    dot.pack(side="left")
    status = tk.Label(top, text="checking...", fg=FG, bg=BG, font=("Segoe UI", 10, "bold"))
    status.pack(side="left", padx=6)
    ontop = tk.BooleanVar(value=False)
    tk.Checkbutton(top, text="Always on top", variable=ontop, fg=FG, bg=BG, selectcolor=BG, activebackground=BG, activeforeground=FG,
                   command=lambda: root.attributes("-topmost", ontop.get())).pack(side="right")
    box = tk.Frame(root, bg=BG)
    box.pack(fill="both", expand=True, padx=10, pady=4)
    txt = tk.Text(box, bg="#0b0f17", fg=FG, insertbackground=FG, relief="flat", wrap="word", state="disabled", font=("Consolas", 10))
    sb = tk.Scrollbar(box, command=txt.yview)
    txt.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    txt.pack(side="left", fill="both", expand=True)
    txt.tag_configure("ok", foreground="#6ee7a0")
    txt.tag_configure("fail", foreground="#ff7b7b")
    txt.tag_configure("dim", foreground="#7d8aa3")
    bot = tk.Frame(root, bg=BG)
    bot.pack(fill="x", padx=10, pady=(4, 10))
    tk.Label(bot, text="Closing this window does not stop printing.", fg="#7d8aa3", bg=BG).pack(side="left")

    def toggle():
        if is_running():
            if messagebox.askyesno("Stop print service", "Stop the print service?\nThe phone will not be able to print until you start it again or restart Windows."):
                stop_service()
        else:
            start_service()
        refresh()
    btn = tk.Button(bot, text="Stop service", command=toggle, relief="flat", bg="#3a1d24", fg="#ffb3b3")
    btn.pack(side="right")

    pos = [0]

    def add(line):
        tag = "fail" if "FAILED" in line or "WARNING" in line else "ok" if " printed " in line else "dim" if "ready" in line or "stopped" in line else ""
        atend = txt.yview()[1] >= 0.999
        txt.configure(state="normal")
        txt.insert("end", line + "\n", tag)
        txt.configure(state="disabled")
        if atend: txt.see("end")

    def poll():
        try:
            size = os.path.getsize(LOG)
            if size < pos[0]: pos[0] = 0   # log was rotated
            if size > pos[0]:
                with open(LOG, "rb") as f:
                    f.seek(pos[0])
                    data = f.read()
                    pos[0] = f.tell()
                for line in data.decode("utf-8", "replace").splitlines():
                    add(line)
        except OSError:
            pass
        root.after(700, poll)

    _ips, _ts = pc_addresses()
    ips = ("Tailscale " + _ts + "  \u00b7  " if _ts else "") + ", ".join(x for x in _ips if x != _ts)
    def refresh():
        info = ping()
        if info:
            dot.configure(fg="#4ade80")
            q = info.get("queued", 0)
            btn.configure(text="Stop service", bg="#3a1d24", fg="#ffb3b3")
            pr = info.get("printer")
            prob = info.get("problem")
            dot.configure(fg="#fbbf24" if prob else "#4ade80")
            status.configure(text=f"Running  \u00b7  {ips}  \u00b7  port {PORT}  \u00b7  " + (f"printer: {pr}" if pr else "NO DEFAULT PRINTER") + (f"  \u00b7  \u26a0 {prob}" if prob else "") + (f"  \u00b7  {q} printing" if q else ""))
        else:
            dot.configure(fg="#f87171")
            btn.configure(text="Start service", bg="#1d3a2a", fg="#b3ffd0")
            status.configure(text="Stopped")

    def tick():
        refresh()
        root.after(3000, tick)

    def initial():   # last lines first
        try:
            with open(LOG, "rb") as f:
                f.seek(max(0, os.path.getsize(LOG) - 30000))
                data = f.read()
                pos[0] = f.tell()
            lines = data.decode("utf-8", "replace").splitlines()
            for line in lines[-150:]:
                add(line)
        except OSError:
            pass

    initial()
    refresh()
    poll()
    tick()
    root.mainloop()   # closing the window only ends this viewer; the service keeps running


def tail_console():
    pos = 0
    try:
        while True:
            try:
                with open(LOG, "rb") as f:
                    f.seek(pos); data = f.read(); pos = f.tell()
                if data: print(data.decode("utf-8", "replace"), end="")
            except OSError:
                pass
            time.sleep(1)
    except KeyboardInterrupt:
        pass


def main():
    a = sys.argv[1:]
    if "--fw" in a:
        fw_add()
    elif "--ui" in a:
        ui()
    elif "--serve" in a or not WIN:
        if is_running():
            return
        serve()
    else:
        setup_windows()


if __name__ == "__main__":
    main()
