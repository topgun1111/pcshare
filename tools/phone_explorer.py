"""
Phone Explorer - PC side (part 2)

Finds phones on the Wi-Fi (UDP broadcast), keeps a list of saved devices (with PIN), opens the phone's
storage in Windows Explorer (ftp://phone:PIN@IP:PORT/) and can optionally map it as a drive letter
(needs rclone + WinFsp). Optional system tray icon if `pystray` and `Pillow` are installed
(the packaged .exe includes them).

Run:   python phone_explorer.py [--pair "phoneexplorer://..."] [--auto] [--tray]
       --pair  import a pairing link from the phone's QR screen and save the device
       --auto  open the default device right after start (also: tick "Auto-open on start")
       --tray  start hidden in the system tray
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
from urllib.parse import parse_qs, unquote, urlparse

try:  # optional
    import pystray
    from PIL import Image, ImageDraw
except Exception:  # pragma: no cover
    pystray = None

DISCOVERY_PORT = 41234
MAGIC = b"PHONEEXPLORER_DISCOVER"
BG, CARD, FG, MUTED, ACCENT = "#0F1720", "#1B2735", "white", "#9FB0C3", "#3B82F6"


# ---------------------------------------------------------------------------------------- config

def config_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "PhoneExplorer")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "config.json")


def load_config():
    cfg = {"devices": [], "default": "", "auto_open": False, "drive": "P"}
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
        with open(config_path(), "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
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


# -------------------------------------------------------------------------------------------- UI

class App(tk.Tk):
    def __init__(self, args):
        super().__init__()
        self.title("Phone Explorer")
        self.geometry("520x580")
        self.minsize(480, 540)
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
        self.scan(auto_open=("--auto" in args) or bool(self.cfg.get("auto_open")))
        if "--tray" in args and pystray:
            self.after(200, self.to_tray)

    # --- layout
    def _build(self):
        tk.Label(self, text="Phone Explorer", font=("Segoe UI", 20, "bold"), fg=FG, bg=BG).pack(pady=(14, 0))
        tk.Label(self, text="Phone and PC must be on the same Wi-Fi", fg=MUTED, bg=BG).pack()

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

        pair = tk.Frame(self, bg=BG)
        pair.pack(fill="x", padx=18, pady=(10, 0))
        tk.Label(pair, text="Pairing link from the phone's QR screen:", fg=MUTED, bg=BG).pack(anchor="w")
        self.link = tk.StringVar()
        tk.Entry(pair, textvariable=self.link).pack(side="left", expand=True, fill="x")
        self._btn(pair, "Import", CARD, lambda: self.import_link(self.link.get())).pack(side="left", padx=(6, 0))

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
        self._btn(self, "Set selected phone as default", CARD, self.on_default).pack(anchor="w", padx=18, pady=(6, 0))

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
            self.set_status("Select a phone first (press Scan or import a pairing link).")
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
            self.to_tray()
        else:
            self.quit_app()

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
    if sys.platform != "win32":
        print("Note: Explorer launch and drive mapping only work on Windows; discovery still runs.")
    App(sys.argv[1:]).mainloop()
