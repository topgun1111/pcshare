#!/usr/bin/env python3
"""
pcprint.py - one-file print service for the LANShare Android app (Windows; Linux/macOS via `lp`).

Run it (double-click or `python pcprint.py`). It sets everything up, then opens a window with the live print log.
The window is only a viewer: the print service runs separately in the background, so closing the window
(even by mistake) does NOT stop printing. Run the file again any time to reopen the window.
First run sets everything up:
  - installs itself to %APPDATA%\\LANSharePrint and starts with Windows
  - opens the firewall port for the LAN and Tailscale (one UAC prompt, first run only)
  - installs SumatraPDF via winget if missing (silent PDF printing with printer / duplex / copies / colour)
  - installs the pure-Python `pypdf` package with pip if missing (FinePrint-style layout features)
  - listens on port 8799 and prints whatever the app sends ("Print on PC")
Pictures sent together are laid out on shared sheets (tight grid, per-picture rotation, automatic turn-to-fit).
FinePrint-style options (chosen in the app): any installed printer, copies, one/two-sided, colour/mono, 1/2/4/6/9 pages per
sheet (+border), booklet, page range, odd/even, reverse order, watermark, header/footer ({page} {pages} {date} {time} {file}).
Fitting options: scaling (shrink / fit / actual size / fill-and-crop / custom %), margins, position (centre / top / top-left),
turn-pages-to-fit-the-sheet.
Layout / fitting / watermark / header-footer work for PDF, image and .txt/.log/.md files; other files get printer + copies only.
Python stdlib only (pypdf is optional and installed automatically).
v16: pictures are shrunk to what the sheet needs (Pillow: smaller PDF / spool, EXIF turn and PNG/GIF/BMP/TIFF conversion without PowerShell); the app
shrinks big JPEGs before uploading; missing Python packages (pypdf, Pillow) are installed automatically, also on first use while the service runs.
v15: "Print all files as one job" - a mixed selection (PDF, pictures, text, Office) is turned into pages, joined in order and laid out as ONE
job (pages per sheet, fitting, duplex run across all files, no half-empty sheet per file).
v14: N pages per sheet picks a portrait or landscape sheet (whichever shows the pages larger, e.g. landscape slides 4-up now get a landscape
sheet), and PDF pages are measured / cut by their CropBox (the visible area) instead of the MediaBox.
v13: no double prints - the app sends a key with every file and a repeated key / a late picture of a finished batch is ignored; a failed print
command is retried only if it failed at once and left nothing in the print queue.
v12: sturdier service (worker that survives any error, idle-socket timeout, queue / disk limits, hourly clean-up, no PowerShell races,
cached printer status, window restarts a dead service), `GET /jobs` (current job + history), window buttons: Test page, Clear queue,
Queue folder, Open log.
"""
import collections, ctypes, importlib, io, ipaddress, itertools, json, math, os, queue, re, shutil, socket, subprocess, sys, threading, time, traceback, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8799
VERSION = "17"
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


_LOGLOCK = threading.Lock()   # several threads log at once (HTTP handlers, print worker, printer watcher)
MAX_QUEUE = 50                # waiting jobs; more are refused with 503 so a stuck printer cannot fill the disk
MIN_FREE = 150 << 20          # keep this much disk free: uploads are refused below it
INBOX_CAP = 1 << 30           # old files in the queue folder are deleted (oldest first) above this total size


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    with _LOGLOCK:
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


_PSLOCK = threading.Lock()


def ps_script(name, body):
    """Path of a helper .ps1 in the app folder. Written only when missing or changed, atomically: several threads run PowerShell at
    the same time, and rewriting a script that another PowerShell is reading made the status / print calls fail at random."""
    path = os.path.join(APP, name)
    with _PSLOCK:
        try:
            with open(path, "r", encoding="utf-8-sig") as f:
                if f.read() == body:
                    return path
        except OSError:
            pass
        os.makedirs(APP, exist_ok=True)
        tmp = path + ".%d.tmp" % os.getpid()
        with open(tmp, "w", encoding="utf-8-sig") as f:
            f.write(body)
        os.replace(tmp, path)
    return path


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
param([string]$Path, [string]$Printer, [int]$Copies = 1)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($Path)
try {
  $pd = New-Object System.Drawing.Printing.PrintDocument
  if ($Printer) { $pd.PrinterSettings.PrinterName = $Printer }
  $pd.PrinterSettings.Copies = $Copies
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


def print_image(path, printer="", copies=1):
    ps1 = ps_script("printimg.ps1", PS_IMG)
    cmd =["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path]
    if printer:
        cmd += ["-Printer", printer]
    cmd += ["-Copies", str(copies)]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120, creationflags=0x08000000)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout or f"exit {r.returncode}").strip()[:300])


# ------------------------------------------------------------------ FinePrint-style options
PAPERS = {"A3": (842, 1191), "A4": (595, 842), "A5": (420, 595), "Letter": (612, 792), "Legal": (612, 1008)}
LAYOUT = {1: (1, 1, False), 2: (2, 1, True), 4: (2, 2, False), 6: (3, 2, True), 9: (3, 3, False)}   # cols, rows, landscape sheet
TEXT_EXT = {".txt", ".log", ".md"}
OFFICE_EXT = {".doc", ".docx", ".rtf", ".odt", ".xls", ".xlsx", ".csv", ".ods", ".ppt", ".pptx", ".odp"}   # converted to PDF first when layout options are asked for
GLYPHS = {"\u011f": "gbreve", "\u011e": "Gbreve", "\u015f": "scedilla", "\u015e": "Scedilla", "\u0130": "Idotaccent", "\u0131": "dotlessi"}
HW = {' ': 278, '!': 278, '"': 355, '#': 556, '$': 556, '%': 889, '&': 667, "'": 191, '(': 333, ')': 333, '*': 389, '+': 584,
      ',': 278, '-': 333, '.': 278, '/': 278, ':': 278, ';': 278, '<': 584, '=': 584, '>': 584, '?': 556, '@': 1015,
      'A': 667, 'B': 667, 'C': 722, 'D': 722, 'E': 667, 'F': 611, 'G': 778, 'H': 722, 'I': 278, 'J': 500, 'K': 667, 'L': 556,
      'M': 833, 'N': 722, 'O': 778, 'P': 667, 'Q': 778, 'R': 722, 'S': 667, 'T': 611, 'U': 722, 'V': 667, 'W': 944, 'X': 667,
      'Y': 667, 'Z': 611, '[': 278, '\\': 278, ']': 278, '^': 469, '_': 556, '`': 333, 'a': 556, 'b': 556, 'c': 500, 'd': 556,
      'e': 556, 'f': 278, 'g': 556, 'h': 556, 'i': 222, 'j': 222, 'k': 500, 'l': 222, 'm': 833, 'n': 556, 'o': 556, 'p': 556,
      'q': 556, 'r': 333, 's': 500, 't': 278, 'u': 556, 'v': 500, 'w': 722, 'x': 500, 'y': 500, 'z': 500, '{': 334, '|': 260,
      '}': 334, '~': 584}
_PYPDF = [None]


def have_pypdf():
    if _PYPDF[0] is None:
        try:
            import pypdf  # noqa: F401
            _PYPDF[0] = True
        except ImportError:
            return False   # not cached: it may get installed while the service runs
    return _PYPDF[0]


PKGS = (("pypdf", "pypdf"), ("PIL", "Pillow"))   # (import name, pip name): everything the service can use besides the standard library
PKG_LOCK = threading.Lock()   # requests run in threads: only one pip at a time


def have_pkg(mod):
    try:
        importlib.import_module(mod)
        return True
    except Exception:
        return False


def _pip(*args):
    kw = dict(timeout=300, capture_output=True, text=True, creationflags=0x08000000 if WIN else 0)
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--user", "--quiet", "--disable-pip-version-check", *args], **kw)
    if r.returncode != 0 and not WIN:   # Linux/macOS: "externally managed environment"
        r = subprocess.run([sys.executable, "-m", "pip", "install", "--user", "--quiet", "--break-system-packages", *args], **kw)
    return r


def ensure_packages(only=None):
    """Installs whatever of PKGS is missing (pip --user; installs pip itself first if the Python has none). Returns True when all wanted ones import."""
    with PKG_LOCK:
        missing = [(m, p) for m, p in PKGS if (only is None or m == only) and not have_pkg(m)]
        if not missing:
            return True
        log("installing " + ", ".join(p for _, p in missing) + " ...")
        try:
            if subprocess.run([sys.executable, "-m", "pip", "--version"], capture_output=True, timeout=30, creationflags=0x08000000 if WIN else 0).returncode != 0:
                subprocess.run([sys.executable, "-m", "ensurepip", "--user"], capture_output=True, timeout=120, creationflags=0x08000000 if WIN else 0)
            for m, p in missing:
                r = _pip(p)
                if r.returncode != 0:
                    log(f"pip install {p} failed: {(r.stderr or r.stdout or '').strip()[-300:]}")
            importlib.invalidate_caches()
            import site
            if site.ENABLE_USER_SITE and site.getusersitepackages() not in sys.path and os.path.isdir(site.getusersitepackages()):
                sys.path.append(site.getusersitepackages())   # a fresh --user install of a running interpreter
        except Exception as e:
            log(f"package install skipped: {e}")
        ok = all(have_pkg(m) for m, _ in missing)
        _PYPDF[0] = None
        return ok


def ensure_pypdf():   # kept for the callers that ask for "the packages"
    ensure_packages()


def need_pypdf():
    """pypdf, installed on the spot if it is missing (first use)."""
    return have_pypdf() or ensure_packages("pypdf")


def have_pil():
    return have_pkg("PIL")


def _int(v, d, lo, hi):
    try:
        return max(lo, min(hi, int(v)))
    except (TypeError, ValueError):
        return d


def _scale(v):
    """Custom scale in percent (10-500); 0 = not set."""
    n = _int(re.sub(r"\D", "", v or ""), 0, 0, 500)
    return max(10, n) if n else 0


def parse_opts(q):
    g = lambda k: ((q.get(k) or [""])[0]).strip()
    o = dict(printer=g("printer")[:200], copies=_int(g("copies"), 1, 1, 99),
             duplex=g("duplex") if g("duplex") in ("off", "long", "short") else "",
             color=g("color") if g("color") in ("color", "mono") else "",
             fit=g("fit") if g("fit") in ("fit", "shrink", "noscale", "fill") else "shrink",
             margin=_int(g("margin"), -1, 0, 72),   # points; -1 = the built-in default for that kind of file
             scale=_scale(g("scale")), align=g("align") if g("align") in ("top", "topleft") else "center",
             autorot=g("autorot") == "1",
             paper=g("paper") if g("paper") in PAPERS else "", nup=_int(g("nup"), 1, 1, 9),
             booklet=g("booklet") == "1", border=g("border") == "1",
             pages=g("pages") if g("pages") in ("odd", "even") else "", reverse=g("reverse") == "1",
             range=re.sub(r"[^0-9,\- ]", "", g("range"))[:80], wm=g("wm")[:60], wm_under=g("wm_under") == "1",
             hdr=g("hdr")[:120], ftr=g("ftr")[:120], noauto=g("noauto") == "1")
    if o["nup"] not in LAYOUT:
        o["nup"] = 1
    if o["booklet"] and not o["duplex"]:
        o["duplex"] = "short"   # booklets are printed on both sides, flipped on the short edge
    return o


def describe_opts(o):
    d = []
    if o["copies"] > 1: d.append(f"{o['copies']} copies")
    if o["booklet"]: d.append("booklet")
    elif o["nup"] > 1: d.append(f"{o['nup']}-up")
    if o["duplex"]: d.append({"off": "one-sided", "long": "duplex long edge", "short": "duplex short edge"}[o["duplex"]])
    if o["color"] == "mono": d.append("mono")
    if o["range"]: d.append("pages " + o["range"])
    if o["pages"]: d.append(o["pages"] + " pages")
    if o["reverse"]: d.append("reversed")
    if o["scale"]: d.append(f"scale {o['scale']}%")
    elif o["fit"] in ("fill", "noscale", "fit"): d.append({"fill": "fill page", "noscale": "actual size", "fit": "fit"}[o["fit"]])
    if o["margin"] >= 0: d.append(f"margin {o['margin']}pt")
    if o["align"] != "center": d.append("top-left" if o["align"] == "topleft" else "top")
    if o["autorot"]: d.append("turn to fit")
    if o["wm"]: d.append("watermark")
    if o["hdr"] or o["ftr"]: d.append("header/footer")
    return ", ".join(d)


def needs_layout(o):
    return bool(o["nup"] > 1 or o["booklet"] or o["range"] or o["pages"] or o["reverse"] or o["wm"] or o["hdr"] or o["ftr"] or o["border"])


def needs_fitting(o):
    """True when the page has to be re-placed on the sheet by us (the printer driver alone cannot do margins / custom scale / fill)."""
    return bool(o["margin"] >= 0 or o["scale"] or o["align"] != "center" or o["autorot"] or o["fit"] == "fill")


def neutral_fit(o):
    """The same options without any fitting (used when the file was already fitted while it was converted to PDF)."""
    return dict(o, margin=-1, scale=0, align="center", autorot=False, fit="shrink")


def eff_margin(o, default):
    """Sheet margin in points: the chosen one, but never less than the printer's hard margin (it cannot print closer to the edge anyway).
    Not chosen (-1) = the built-in default for that kind of file."""
    m = o["margin"]
    return default if m < 0 else max(float(m), o.get("hard", 0.0))


def fit_cell(w, h, cw, ch, o, per):
    """One page of size w x h in a cell of cw x ch -> (scale, turned 90 degrees, shown width, shown height)."""
    best = None
    for turn in ((False, True) if o["autorot"] else (False,)):
        pw, ph = (h, w) if turn else (w, h)
        fk = min(cw / pw, ch / ph)
        if best is None or fk > best[0] * 1.0001:
            best = (fk, turn, pw, ph)
    fk, turn, pw, ph = best
    if o["scale"]: sc = o["scale"] / 100.0
    elif o["fit"] == "noscale": sc = 1.0
    elif o["fit"] == "fill" and per == 1: sc = max(cw / pw, ch / ph)
    elif o["fit"] == "fit" or per > 1: sc = fk   # several pages per sheet are always reduced to their cell
    else: sc = min(1.0, fk)                       # shrink to fit
    if per > 1: sc = min(sc, fk)                  # a page never spills into its neighbours
    return sc, turn, pw * sc, ph * sc


# ---- tiny PDF writer (watermark / header overlays, image + text -> PDF)
class Pdf:
    def __init__(self):
        self.objs = []

    def add(self, body):
        self.objs.append(body)
        return len(self.objs)

    def stream(self, data, extra=b""):
        return self.add(b"<< /Length %d %s >>\nstream\n" % (len(data), extra) + data + b"\nendstream")

    def save(self, root):
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offs = []
        for i, b in enumerate(self.objs, 1):
            offs.append(len(out))
            out += b"%d 0 obj\n" % i + b + b"\nendobj\n"
        x = len(out)
        out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(self.objs) + 1)
        for o in offs:
            out += b"%010d 00000 n \n" % o
        out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(self.objs) + 1, root, x)
        return bytes(out)


def enc_text(s, extra):
    """PDF string bytes: WinAnsi plus the Turkish letters WinAnsi lacks (mapped to spare codes via /Differences)."""
    out = bytearray()
    for ch in s:
        if ch in GLYPHS:
            if ch not in extra:
                extra[ch] = len(extra) + 1
            out.append(extra[ch])
        elif ch in "\r\n\t":
            out.append(32)
        else:
            try:
                out += ch.encode("cp1252")
            except UnicodeEncodeError:
                out += b"?"
    return bytes(out).replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def tw(s, size, mono=False):
    return len(s) * 600 * size / 1000.0 if mono else sum(HW.get(c, 556) for c in s) * size / 1000.0


def make_pdf(pages, extra, font="Helvetica", opacity=0.28, image=None):
    """pages = [(w, h, content bytes)] sharing one font (/F1), one opacity state (/GW) and an optional JPEG (/Im0)."""
    pdf = Pdf()
    diff = b" ".join(b"/" + GLYPHS[ch].encode() for ch, _ in sorted(extra.items(), key=lambda kv: kv[1]))
    enc = (b"<< /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [1 %s] >>" % diff) if extra else b"/WinAnsiEncoding"
    f = pdf.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /%s /Encoding %s >>" % (font.encode(), enc))
    g = pdf.add(b"<< /Type /ExtGState /ca %.2f /CA %.2f >>" % (opacity, opacity))
    ims = []
    for d, iw, ih, comps in (image if isinstance(image, list) else ([image] if image else [])):
        ims.append(pdf.stream(d, b"/Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /%s /BitsPerComponent 8 /Filter /DCTDecode"
                              % (iw, ih, b"DeviceGray" if comps == 1 else b"DeviceRGB")))
    xo = (b"/XObject << %s >>" % b" ".join(b"/Im%d %d 0 R" % (n, r) for n, r in enumerate(ims))) if ims else b""
    res = pdf.add(b"<< /Font << /F1 %d 0 R >> /ExtGState << /GW %d 0 R >> %s >>" % (f, g, xo))
    n = len(pages)
    pages_id = len(pdf.objs) + 1 + 2 * n
    kids = []
    for w, h, c in pages:
        cid = pdf.stream(zlib.compress(c), b"/Filter /FlateDecode")
        kids.append(pdf.add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %.2f %.2f] /Contents %d 0 R /Resources %d 0 R >>" % (pages_id, w, h, cid, res)))
    pdf.add(b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), n))
    cat = pdf.add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
    return pdf.save(cat)


def jpeg_info(b):
    i = 2
    while i + 9 < len(b):
        if b[i] != 0xFF:
            i += 1
            continue
        m = b[i + 1]
        if m in (0xC0, 0xC1, 0xC2):
            return (b[i + 7] << 8 | b[i + 8], b[i + 5] << 8 | b[i + 6], b[i + 9])   # width, height, components
        if m == 0xFF:
            i += 1
        elif m == 0xD8 or 0xD0 <= m <= 0xD7 or m == 0x01:
            i += 2
        else:
            i += 2 + ((b[i + 2] << 8) | b[i + 3])
    return None


def jpeg_orientation(b):
    try:
        i = 2
        while i + 4 < len(b) and b[i] == 0xFF:
            m, L = b[i + 1], (b[i + 2] << 8) | b[i + 3]
            if m == 0xE1 and b[i + 4:i + 10] == b"Exif\0\0":
                t = b[i + 10:i + 2 + L]
                end = "little" if t[:2] == b"II" else "big"
                u16 = lambda o: int.from_bytes(t[o:o + 2], end)
                ifd = int.from_bytes(t[4:8], end)
                for k in range(u16(ifd)):
                    e = ifd + 2 + 12 * k
                    if u16(e) == 0x0112:
                        return u16(e + 8)
                return 1
            if m == 0xDA:
                break
            i += 2 + L
    except Exception:
        pass
    return 1


PS_CONV = r"""
param([string]$In, [string]$Out)
Add-Type -AssemblyName System.Drawing
$i = [System.Drawing.Image]::FromFile($In)
try {
  try { switch ($i.GetPropertyItem(274).Value[0]) { 2 {$i.RotateFlip('RotateNoneFlipX')} 3 {$i.RotateFlip('Rotate180FlipNone')} 4 {$i.RotateFlip('Rotate180FlipX')} 5 {$i.RotateFlip('Rotate90FlipX')} 6 {$i.RotateFlip('Rotate90FlipNone')} 7 {$i.RotateFlip('Rotate270FlipX')} 8 {$i.RotateFlip('Rotate270FlipNone')} } } catch {}
  $b = New-Object System.Drawing.Bitmap($i.Width, $i.Height)
  $g = [System.Drawing.Graphics]::FromImage($b)
  $g.Clear([System.Drawing.Color]::White)
  $g.DrawImage($i, 0, 0, $i.Width, $i.Height)
  $g.Dispose()
  $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
  $ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
  $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]92)
  $b.Save($Out, $enc, $ep)
  $b.Dispose()
} finally { $i.Dispose() }
"""


def out_pdf(path, tag):
    return os.path.join(INBOX, os.path.splitext(os.path.basename(path))[0] + "." + tag + ".pdf")


def target_px(o):
    """Longest side in pixels a picture needs on this sheet layout (about 300 dpi for the cell it is printed in)."""
    pw, ph = PAPERS.get(o["paper"], PAPERS["A4"])
    per = 2 if o["booklet"] else o["nup"]
    return int((3508 if per == 1 else 2480 if per < 6 else 1754) * max(pw, ph) / 842.0)


def pil_jpeg(path, o, flat=False):
    """Pillow: picture -> (jpeg bytes, w, h, components) with the EXIF turn applied, shrunk to what the sheet needs.
    None = Pillow is missing, or the file is fine as it is (a baseline JPEG that is not too big), or it failed (the old path then runs).
    flat=True: a JPEG that only needs the EXIF turn is converted too (callers that cannot turn it with a page matrix)."""
    if not have_pil():
        return None
    try:
        from PIL import Image, ImageOps
        Image.MAX_IMAGE_PIXELS = None
        tp = target_px(o)
        im = Image.open(path)
        big = max(im.size) > tp * 1.15
        jpg = im.format == "JPEG" and im.mode in ("RGB", "L")
        if jpg and not big and not (flat and im.getexif().get(0x0112, 1) not in (0, 1)):
            return None
        if jpg and big:
            im.draft("RGB" if im.mode == "RGB" else "L", (tp, tp))   # the decoder scales down while reading: much faster for big photos
        im = ImageOps.exif_transpose(im)
        if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
            im = im.convert("RGBA")
            bg = Image.new("RGB", im.size, (255, 255, 255))
            bg.paste(im, mask=im.getchannel("A"))
            im = bg
        elif im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        if max(im.size) > tp:
            im.thumbnail((tp, tp), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=90)
        d = buf.getvalue()
        info = jpeg_info(d)
        return (d,) + tuple(info) if info else None
    except Exception as e:
        log(f"Pillow could not convert {os.path.basename(path)}: {e}")
        return None


MAX_FRAMES = 300   # frames printed from one multi-page TIFF


def tiff_frames(path, o):
    """Multi-page TIFF -> one JPEG file per frame (in the queue folder). [] = not a TIFF, one frame only, or no Pillow (the first frame is then used)."""
    if os.path.splitext(path)[1].lower() not in (".tif", ".tiff") or not have_pil():
        return []
    try:
        from PIL import Image, ImageSequence
        Image.MAX_IMAGE_PIXELS = None
        im = Image.open(path)
        if getattr(im, "n_frames", 1) < 2:
            return []
        tp, base, out = target_px(o), os.path.splitext(os.path.basename(path))[0], []
        for i, fr in enumerate(ImageSequence.Iterator(im)):
            if i >= MAX_FRAMES:
                log(f"{os.path.basename(path)}: only the first {MAX_FRAMES} frames are printed")
                break
            try:
                if fr.mode.startswith("I;16") or fr.mode in ("I", "F"):
                    fr = fr.point(lambda v: v / 256.0).convert("L")
                elif fr.mode in ("RGBA", "LA") or (fr.mode == "P" and "transparency" in fr.info):
                    fr = fr.convert("RGBA")
                    bg = Image.new("RGB", fr.size, (255, 255, 255))
                    bg.paste(fr, mask=fr.getchannel("A"))
                    fr = bg
                elif fr.mode not in ("RGB", "L"):
                    fr = fr.convert("RGB")
                else:
                    fr = fr.copy()
                if max(fr.size) > tp:
                    fr.thumbnail((tp, tp), Image.LANCZOS)
                dest = os.path.join(INBOX, f"{base}.f{i + 1:03d}.jpg")
                fr.save(dest, "JPEG", quality=90)
                out.append(dest)
            except Exception as e:
                log(f"{os.path.basename(path)}: frame {i + 1} skipped: {e}")
        return out if len(out) > 1 else out[:0]
    except Exception as e:
        log(f"TIFF frames of {os.path.basename(path)} could not be read: {e}")
        return []


def merge_pdfs(parts, dest):
    from pypdf import PdfReader, PdfWriter
    wr, keep = PdfWriter(), []
    for p in parts:
        rd = PdfReader(p)
        keep.append(rd)
        for pg in rd.pages:
            wr.add_page(pg)
    with open(dest, "wb") as f:
        wr.write(f)
    return dest


def img_to_pdf(path, o, exact=False):
    """Image -> one-page PDF (paper size from the options, EXIF rotation applied, fit with a margin). A multi-page TIFF -> one page per frame."""
    fr = tiff_frames(path, o) if need_pypdf() else []
    if fr:
        return merge_pdfs([img_to_pdf(f, o, exact) for f in fr], out_pdf(path, "img"))
    data = open(path, "rb").read()
    ext = os.path.splitext(path)[1].lower()
    jpg = pil_jpeg(path, o, True)
    if not jpg and ext in (".jpg", ".jpeg") and data[:2] == b"\xff\xd8":
        info = jpeg_info(data)
        if info and info[2] in (1, 3) and (jpeg_orientation(data) == 1 or not WIN):
            jpg = (data,) + info
    if not jpg:
        if not WIN:
            raise RuntimeError("this image type can only be converted on Windows")
        ps1, tmp = ps_script("convimg.ps1", PS_CONV), os.path.join(APP, "conv.jpg")
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path, tmp],
                           capture_output=True, text=True, timeout=120, creationflags=0x08000000)
        if r.returncode != 0 or not os.path.isfile(tmp):
            raise RuntimeError((r.stderr or r.stdout or "image conversion failed").strip()[:300])
        data = open(tmp, "rb").read()
        try: os.remove(tmp)
        except OSError: pass
        info = jpeg_info(data)
        if not info:
            raise RuntimeError("image conversion failed")
        jpg = (data,) + info
    d, iw, ih, comps = jpg
    pw, ph = PAPERS.get(o["paper"], PAPERS["A4"])
    if iw > ih:
        pw, ph = ph, pw
    m = eff_margin(o, 28)
    if exact:   # page = the picture itself (joined jobs): the sheet layout then fits it like any other page
        kk = 842.0 / max(iw, ih)
        pw, ph, m = iw * kk, ih * kk, 0
    fw, fh = pw - 2 * m, ph - 2 * m
    k = max(fw / iw, fh / ih) if o["fit"] == "fill" else min(fw / iw, fh / ih)   # fill = cover the area inside the margins, crop the rest
    w, h = iw * k, ih * k
    c = b"q %.2f %.2f %.2f %.2f re W n %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q" % (m, m, fw, fh, w, h, (pw - w) / 2, (ph - h) / 2)
    dest = out_pdf(path, "img")
    open(dest, "wb").write(make_pdf([(pw, ph, c)], {}, image=jpg))
    return dest


def txt_to_pdf(path, o):
    raw = open(path, "rb").read()
    text = ""
    for e in ("utf-8-sig", "cp1254", "cp1252"):
        try:
            text = raw.decode(e)
            break
        except UnicodeDecodeError:
            pass
    pw, ph = PAPERS.get(o["paper"], PAPERS["A4"])
    mm = eff_margin(o, 50)
    size = max(4.0, min(40.0, 10.0 * (o["scale"] / 100.0 if o["scale"] else 1.0)))   # custom scale = font size
    lead = size * 1.2
    cpl, lpp = max(1, int((pw - 2 * mm) / (0.6 * size))), max(1, int((ph - 2 * mm) / lead))
    lines = []
    for ln in text.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n").split("\n"):
        ln = re.sub(r"[\x00-\x08\x0b\x0e-\x1f]", "", ln).expandtabs(4)
        while len(ln) > cpl:
            lines.append(ln[:cpl])
            ln = ln[cpl:]
        lines.append(ln)
    extra, pages = {}, []
    for i in range(0, max(len(lines), 1), lpp):
        c = b"BT /F1 %.1f Tf %.1f TL %.1f %.1f Td\n" % (size, lead, mm, ph - mm - size)
        for ln in lines[i:i + lpp]:
            c += b"(%s) Tj T*\n" % enc_text(ln, extra)
        pages.append((pw, ph, c + b"ET"))
    dest = out_pdf(path, "txt")
    open(dest, "wb").write(make_pdf(pages, extra, font="Courier"))
    return dest


def page_set(rng, n):
    if not rng.strip():
        return list(range(n))
    out = []
    for part in rng.replace(" ", "").split(","):
        if not part:
            continue
        try:
            a, dash, b = part.partition("-")
            if dash:
                s, e = (int(a) if a else 1), (int(b) if b else n)
            else:
                s = e = int(a)
        except ValueError:
            raise ValueError(f"bad page range '{rng}'")
        s, e = max(1, min(n, s)), max(1, min(n, e))
        out += range(s - 1, e) if s <= e else range(s - 1, e - 2, -1)
    return out


def booklet_order(n):
    """Page order for a folded booklet: per sheet front = (last, first), back = (second, second-to-last)."""
    m = (n + 3) // 4 * 4
    seq = []
    for i in range(m // 4):
        seq += [m - 1 - 2 * i, 2 * i, 2 * i + 1, m - 2 - 2 * i]
    return [x if x < n else None for x in seq]


def fill(s, i, n, name):
    return (s.replace("{page}", str(i + 1)).replace("{pages}", str(n)).replace("{date}", time.strftime("%Y-%m-%d"))
             .replace("{time}", time.strftime("%H:%M")).replace("{file}", name))


def zones(text, y, w, size, extra):
    parts = text.split("|")
    parts = (["", parts[0], ""] if len(parts) == 1 else (parts + ["", ""])[:3])
    c = b""
    for k, t in enumerate(parts):
        if not t.strip():
            continue
        t = t.strip()
        x = (28, (w - tw(t, size)) / 2, w - 28 - tw(t, size))[k]
        c += b"BT /F1 %.1f Tf 0.25 g %.2f %.2f Td (%s) Tj ET\n" % (size, x, y, enc_text(t, extra))
    return c


def clip_to_crop(page, rd):
    """A page with a CropBox smaller than its MediaBox shows only the CropBox. When such a page is placed on a sheet its content must be cut
    at the CropBox too, otherwise what is hidden in a viewer (scan borders, bleed, off-page objects) shows up next to the neighbouring pages."""
    try:
        mb, cb = page.mediabox, page.cropbox
        if (float(cb.left), float(cb.bottom), float(cb.width), float(cb.height)) == (float(mb.left), float(mb.bottom), float(mb.width), float(mb.height)):
            return
        from pypdf.generic import ContentStream, FloatObject
        cs = ContentStream(page.get_contents(), rd)
        cs.operations = ([([], b"q"), ([FloatObject(float(cb.left)), FloatObject(float(cb.bottom)), FloatObject(float(cb.width)), FloatObject(float(cb.height))], b"re"),
                          ([], b"W"), ([], b"n")] + list(cs.operations) + [([], b"Q")])
        page.replace_contents(cs)
    except Exception as e:
        log(f"crop box clip skipped: {e}")


def best_sheet(o, dims, per, short, long_, M, mg=12):
    """Sheet orientation for N pages per sheet -> (columns, rows, landscape). Every page is fitted into its cell on a portrait and on a landscape
    sheet; the orientation that shows the pages larger wins (the usual one for the layout wins a tie). One orientation for the whole job."""
    best = None
    default_land = LAYOUT[per][2]
    for land in (default_land, not default_land):
        cols, rows = SHEET_GRID[per][1 if land else 0]
        sw, sh = (long_, short) if land else (short, long_)
        iw, ih = (sw - 2 * M) / cols - 2 * mg, (sh - 2 * M) / rows - 2 * mg
        if iw <= 0 or ih <= 0:
            continue
        score = 0.0
        for w, h in dims:
            if w <= 0 or h <= 0:
                continue
            k = min(iw / w, ih / h)
            if o["autorot"]:
                k = max(k, min(iw / h, ih / w))
            score += k * k * w * h
        if best is None or score > best[0] * 1.0001:
            best = (score, cols, rows, land)
    if best is None:
        c, r, l = LAYOUT[per]
        return c, r, l
    return best[1], best[2], best[3]


def process_pdf(src, o, name):
    """Page selection, order, booklet / N-up, watermark, header/footer -> (new pdf path, sheets)."""
    if not need_pypdf():
        raise RuntimeError("layout options need the 'pypdf' package on the PC and it could not be installed automatically (pip install pypdf)")
    from pypdf import PdfReader, PdfWriter, Transformation
    rd = PdfReader(src)
    if rd.is_encrypted:
        try: rd.decrypt("")
        except Exception: pass
    pages = list(rd.pages)
    idx = page_set(o["range"], len(pages))
    if o["pages"]:
        idx = [i for i in idx if (i + 1) % 2 == (1 if o["pages"] == "odd" else 0)]
    if o["reverse"]:
        idx.reverse()
    if not idx:
        raise ValueError("no pages left after the page selection")
    for i in set(idx):
        try:
            if pages[i].get("/Rotate"):
                pages[i].transfer_rotation_to_content()
        except Exception:
            pass
        clip_to_crop(pages[i], rd)
    if o["booklet"]:
        order = [None if x is None else idx[x] for x in booklet_order(len(idx))]
        cols, rows, land = 2, 1, True
    else:
        order = idx
        cols, rows, land = LAYOUT[o["nup"]]
    per = cols * rows
    box = lambda p: (float(p.cropbox.left), float(p.cropbox.bottom), float(p.cropbox.width), float(p.cropbox.height))   # the visible area (= MediaBox when there is no CropBox)
    wr = PdfWriter()
    sheets, rects, clips = [], [], []
    fitting = needs_fitting(o)
    M = eff_margin(o, 0) if fitting else 0   # sheet margin in points (not less than the printer's hard margin)
    first = next(i for i in order if i is not None)
    if per == 1 and not fitting and all(box(pages[i])[:2] == (0.0, 0.0) for i in idx):
        for i in order:
            sheets.append(wr.add_page(pages[i]))
            rects.append([])
            clips.append(None)
    else:
        fw, fh = box(pages[first])[2:]
        first_land = fw > fh
        if o["paper"]:
            fw, fh = PAPERS[o["paper"]]
        short, long_ = sorted((fw, fh))
        if per > 1 and not o["booklet"]:   # portrait or landscape sheet, whichever shows the pages larger (slides want landscape, text pages portrait)
            cols, rows, land = best_sheet(o, [box(pages[i])[2:] for i in order if i is not None], per, short, long_, M)
        sw, sh = (long_, short) if land else (short, long_)
        for s0 in range(0, len(order), per):
            chunk = order[s0:s0 + per]
            if per == 1:
                l, b, w, h = box(pages[chunk[0]])
                if fitting and (o["paper"] or o["autorot"]):
                    # paper size chosen (or all sheets must look alike): keep the orientation of the first page when turning pages
                    sw, sh = (long_, short) if (first_land if o["autorot"] else w > h) else (short, long_)
                else:
                    sw, sh = w, h
            sheet = wr.add_blank_page(sw, sh)
            cw, ch, rc = (sw - 2 * M) / cols, (sh - 2 * M) / rows, []
            clip = None
            for k, i in enumerate(chunk):
                if i is None:
                    continue
                l, b, w, h = box(pages[i])
                mg = 0 if per == 1 else (8 if o["booklet"] else 12)
                ix, iy = M + (k % cols) * cw + mg, sh - M - (k // cols + 1) * ch + mg   # inner cell: lower-left corner and size
                iw, ih = cw - 2 * mg, ch - 2 * mg
                if fitting:
                    sc, turn, dw, dh = fit_cell(w, h, iw, ih, o, per)
                else:
                    sc, turn = (1.0 if per == 1 else min(iw / w, ih / h)), False
                    dw, dh = w * sc, h * sc
                x = ix if o["align"] == "topleft" else ix + (iw - dw) / 2
                y = iy + (ih - dh) if o["align"] != "center" else iy + (ih - dh) / 2
                if turn:   # quarter turn counter-clockwise: the page then spans x-dw..x, so shift it right by its shown width
                    tf = Transformation().translate(-l, -b).rotate(90).scale(sc, sc).translate(x + dw, y)
                else:
                    tf = Transformation().translate(-l, -b).scale(sc, sc).translate(x, y)
                sheet.merge_transformed_page(pages[i], tf)
                rc.append((x, y, dw, dh))
                if per == 1 and M > 0 and (dw > iw + 0.5 or dh > ih + 0.5):
                    clip = (M, M, sw - 2 * M, sh - 2 * M)   # the page overflows its margins (fill / big custom scale): covered below
            sheets.append(sheet)
            rects.append(rc)
            clips.append(clip)
    n = len(sheets)
    dims = [(float(s.mediabox.width), float(s.mediabox.height)) for s in sheets]
    extra = {}

    def overlay(contents, over, opacity=0.28):
        ov = PdfReader(io.BytesIO(make_pdf([(d[0], d[1], c) for d, c in zip(dims, contents)], extra, opacity=opacity))).pages
        for s, p in zip(sheets, ov):
            s.merge_page(p, over=over)

    if any(clips):   # white frame over the margins so a page that was scaled up / filled is cut off at the margin
        cs = []
        for (w, h), cp in zip(dims, clips):
            cs.append(b" " if not cp else b"1 g 0 0 %.2f %.2f re %.2f %.2f %.2f %.2f re f*" % (w, h, cp[0], cp[1], cp[2], cp[3]))
        overlay(cs, True, 1.0)
    if o["wm"]:
        cs = []
        for (w, h) in dims:
            t = fill(o["wm"], 0, n, name)
            ang = math.atan2(h, w)
            size = max(8.0, min(150.0, math.hypot(w, h) * 0.7 / max(tw(t, 1), 0.1)))
            tx = tw(t, size)
            cs.append(b"q /GW gs 0.5 g %.4f %.4f %.4f %.4f %.2f %.2f cm BT /F1 %.1f Tf 1 0 0 1 %.2f %.2f Tm (%s) Tj ET Q"
                      % (math.cos(ang), math.sin(ang), -math.sin(ang), math.cos(ang), w / 2, h / 2, size, -tx / 2, -size * 0.35, enc_text(t, extra)))
        overlay(cs, not o["wm_under"])
    if o["hdr"] or o["ftr"] or o["border"]:
        cs = []
        for i, ((w, h), rc) in enumerate(zip(dims, rects)):
            c = b""
            if o["border"] and per > 1:
                for (x, y, rw, rh) in rc:
                    c += b"0.6 G 0.5 w %.2f %.2f %.2f %.2f re S\n" % (x, y, rw, rh)
            if o["hdr"]:
                c += zones(fill(o["hdr"], i, n, name), h - 22, w, 9, extra)
            if o["ftr"]:
                c += zones(fill(o["ftr"], i, n, name), 14, w, 9, extra)
            cs.append(c or b" ")
        overlay(cs, True, 1.0)
    dest = out_pdf(src, "print")
    with open(dest, "wb") as f:
        wr.write(f)
    return dest, n


def sumatra_args(sm, pdf, o, pr):
    st = [f"{o['copies']}x"]
    st.append({"off": "simplex", "long": "duplex", "short": "duplexshort"}.get(o["duplex"], ""))
    if o["color"] == "mono": st.append("monochrome")
    elif o["color"] == "color": st.append("color")
    st.append("shrink" if o["fit"] == "fill" else o["fit"])   # "fill" was already done while laying the page out
    if o["paper"]: st.append("paper=" + o["paper"])
    cmd = [sm, "-print-to", pr] if pr else [sm, "-print-to-default"]
    return cmd + ["-print-settings", ",".join(x for x in st if x), "-silent", pdf]


PS_PRINTTO = r"""
param([string]$File, [string]$Printer)
Start-Process -FilePath $File -Verb PrintTo -ArgumentList ('"' + $Printer + '"') -WindowStyle Hidden -ErrorAction Stop
"""


# ---- office documents -> PDF (so layout / duplex / colour options work): Microsoft Office through COM, else LibreOffice
OFFICE_COM = {".doc": "Word.Application", ".docx": "Word.Application", ".rtf": "Word.Application", ".odt": "Word.Application",
              ".xls": "Excel.Application", ".xlsx": "Excel.Application", ".csv": "Excel.Application", ".ods": "Excel.Application",
              ".ppt": "PowerPoint.Application", ".pptx": "PowerPoint.Application", ".odp": "PowerPoint.Application"}
CF = 0x08000000 if WIN else 0   # CREATE_NO_WINDOW (subprocess only accepts creationflags on Windows)

# hidden + read-only; the app is only closed when it has no other document open (never closes the user's own Word / Excel / PowerPoint)
PS_OFFICE = r"""
param([string]$Src, [string]$Dst)
$ErrorActionPreference = 'Stop'
$ext = [IO.Path]::GetExtension($Src).ToLower()
if ($ext -in '.doc','.docx','.rtf','.odt') {
  $app = New-Object -ComObject Word.Application
  $app.Visible = $false; $app.DisplayAlerts = 0
  try { $d = $app.Documents.Open($Src, $false, $true); try { $d.ExportAsFixedFormat($Dst, 17) } finally { $d.Close($false) } }
  finally { if ($app.Documents.Count -eq 0) { $app.Quit() } }
} elseif ($ext -in '.xls','.xlsx','.csv','.ods') {
  $app = New-Object -ComObject Excel.Application
  $app.Visible = $false; $app.DisplayAlerts = $false
  try { $b = $app.Workbooks.Open($Src, 0, $true); try { $b.ExportAsFixedFormat(0, $Dst) } finally { $b.Close($false) } }
  finally { if ($app.Workbooks.Count -eq 0) { $app.Quit() } }
} elseif ($ext -in '.ppt','.pptx','.odp') {
  $app = New-Object -ComObject PowerPoint.Application
  try { $p = $app.Presentations.Open($Src, -1, 0, 0); try { $p.SaveAs($Dst, 32) } finally { $p.Close() } }
  finally { if ($app.Presentations.Count -eq 0) { $app.Quit() } }
} else { exit 2 }
"""


def has_com(progid):
    if not WIN:
        return False
    try:
        import winreg
        winreg.CloseKey(winreg.OpenKey(winreg.HKEY_CLASSES_ROOT, progid + r"\CLSID"))
        return True
    except OSError:
        return False


def find_soffice():
    for p in (shutil.which("soffice"), shutil.which("libreoffice"),
              os.path.join(os.environ.get("ProgramFiles", ""), "LibreOffice", "program", "soffice.exe"),
              os.path.join(os.environ.get("ProgramFiles(x86)", ""), "LibreOffice", "program", "soffice.exe")):
        if p and os.path.isfile(p):
            return p
    return None


def office_engine(ext=None):
    """Which program can turn office files into PDF on this PC (None = none): shown in the app, which then offers a layout preview."""
    exts = [ext] if ext else sorted(OFFICE_COM)
    if any(has_com(OFFICE_COM[e]) for e in exts):
        return "Microsoft Office"
    return "LibreOffice" if find_soffice() else None


def office_to_pdf(path):
    ext = os.path.splitext(path)[1].lower()
    dst = out_pdf(path, "office")
    errs = []
    if has_com(OFFICE_COM.get(ext, "")):
        ps1 = ps_script("office2pdf.ps1", PS_OFFICE)
        try:
            os.remove(dst)
        except OSError:
            pass
        try:
            r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path, dst],
                               capture_output=True, text=True, timeout=180, creationflags=CF)
            if r.returncode == 0 and os.path.isfile(dst) and os.path.getsize(dst) > 0:
                return dst
            errs.append("Microsoft Office: " + ((r.stderr or r.stdout or "").strip().splitlines() or ["exit %d" % r.returncode])[0][:150])
        except subprocess.TimeoutExpired:
            errs.append("Microsoft Office took too long (password or a dialog open?)")
    so = find_soffice()
    if so:
        outdir = os.path.join(APP, "lo-out")
        os.makedirs(outdir, exist_ok=True)
        tmp = os.path.join(outdir, os.path.splitext(os.path.basename(path))[0] + ".pdf")
        try:
            os.remove(tmp)
        except OSError:
            pass
        try:
            from pathlib import Path
            prof = Path(APP, "lo-profile").resolve().as_uri()   # own profile: works while the user has LibreOffice open
            r = subprocess.run([so, "-env:UserInstallation=" + prof, "--headless", "--norestore", "--convert-to", "pdf", "--outdir", outdir, path],
                               capture_output=True, text=True, timeout=180, creationflags=CF)
            if os.path.isfile(tmp) and os.path.getsize(tmp) > 0:
                os.replace(tmp, dst)
                return dst
            errs.append("LibreOffice: " + ((r.stderr or r.stdout or "").strip().splitlines() or ["no output"])[0][:150])
        except subprocess.TimeoutExpired:
            errs.append("LibreOffice took too long")
    raise RuntimeError("; ".join(errs) or "neither Microsoft Office nor LibreOffice is installed on the PC")


# ---- several pictures on shared sheets (tight grid, per-picture rotation, automatic turn-to-fit)
SHEET_GRID = {1: ((1, 1), (1, 1)), 2: ((1, 2), (2, 1)), 4: ((2, 2), (2, 2)), 6: ((2, 3), (3, 2)), 9: ((3, 3), (3, 3))}   # (cols, rows) on a portrait / landscape sheet
SHEET_MARGIN, SHEET_GAP = 14.0, 8.0   # points
BATCHES = {}   # batch id -> {"t": time, "items": {index: (file, rotation)}}   (the app sends each picture as its own request)
BATCH_LOCK = threading.Lock()   # every request runs in its own thread
FINISHED_BATCHES = {}   # batch id -> time its sheets were queued: a picture of that batch that arrives again is ignored (it would print alone)
SEEN = {}               # request key -> {"t", "evt", "res"}: the app sends a key with every file; the same key again = the same file, never printed twice
SEEN_LOCK = threading.Lock()
SEEN_KEEP = 900         # seconds a key / finished batch is remembered


def prep_image(path, tag, o=None):
    """-> (jpeg bytes, raw width, raw height, components, EXIF turn in degrees clockwise that still has to be applied)."""
    r = pil_jpeg(path, o) if o else None
    if r:
        return r + (0,)   # shrunk and turned by Pillow
    data = open(path, "rb").read()
    if os.path.splitext(path)[1].lower() in (".jpg", ".jpeg") and data[:2] == b"\xff\xd8":
        info, ori = jpeg_info(data), jpeg_orientation(data)
        if info and info[2] in (1, 3) and ori in (1, 3, 6, 8):
            return data, info[0], info[1], info[2], {1: 0, 3: 180, 6: 90, 8: 270}[ori]   # embedded untouched, turned by the page matrix
    if not WIN:
        raise RuntimeError("this image type can only be converted on Windows")
    ps1, tmp = ps_script("convimg.ps1", PS_CONV), os.path.join(APP, "conv%s.jpg" % tag)
    r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path, tmp],
                       capture_output=True, text=True, timeout=120, creationflags=0x08000000)
    if r.returncode != 0 or not os.path.isfile(tmp):
        raise RuntimeError((r.stderr or r.stdout or "image conversion failed").strip()[:300])
    try:
        data = open(tmp, "rb").read()
    finally:
        try: os.remove(tmp)
        except OSError: pass
    info = jpeg_info(data)
    if not info:
        raise RuntimeError("image conversion failed")
    return data, info[0], info[1], info[2], 0   # PowerShell already applied the EXIF turn


def images_to_sheets(items, o, name):
    """items = [(file, rotation 0/90/180/270 clockwise)] -> (pdf path, sheets). Mirrors the preview in the app (ui.html pvSheets)."""
    imgs = []
    exp = []
    for p, rot in items:   # every frame of a multi-page TIFF is a picture of its own
        fr = tiff_frames(p, o)
        exp.extend([(f, rot) for f in fr] if fr else [(p, rot)])
    items = exp
    for n, (p, rot) in enumerate(items):
        d, rw, rh, comps, base = prep_image(p, n, o)
        ew, eh = (rh, rw) if base in (90, 270) else (rw, rh)   # size as the picture looks after EXIF
        imgs.append(dict(d=d, rw=rw, rh=rh, comps=comps, base=base, ew=ew, eh=eh, rot=rot))
    if o["reverse"]:
        imgs.reverse()
    for n, im in enumerate(imgs):
        im["n"] = n
    per = o["nup"]
    pw, ph = PAPERS.get(o["paper"], PAPERS["A4"])
    M, G = eff_margin(o, SHEET_MARGIN), SHEET_GAP
    fill = o["fit"] == "fill"   # cover the whole cell and crop what sticks out

    def plan(portrait):
        sw, sh = (pw, ph) if portrait else (ph, pw)
        cols, rows = SHEET_GRID[per][0 if portrait else 1]
        cw, ch = (sw - 2 * M - G * (cols - 1)) / cols, (sh - 2 * M - G * (rows - 1)) / rows
        sheets, score = [], 0.0
        for s0 in range(0, len(imgs), cols * rows):
            cells = []
            for j, im in enumerate(imgs[s0:s0 + cols * rows]):
                best = None
                for extra in ((0,) if o["noauto"] else (0, 90)):
                    r = (im["rot"] + extra) % 360
                    dw, dh = (im["eh"], im["ew"]) if r % 180 else (im["ew"], im["eh"])
                    k = min(cw / dw, ch / dh)
                    if best is None or k > best[0] * 1.0001:
                        best = (k, r, dw, dh)
                fk, r, dw, dh = best
                score += fk * fk * dw * dh
                k = max(cw / dw, ch / dh) if fill else fk
                cells.append((im, r, k, cw if fill else dw * k, ch if fill else dh * k,
                              M + (j % cols) * (cw + G) + cw / 2, sh - M - (j // cols) * (ch + G) - ch / 2))
            sheets.append(cells)
        return sw, sh, sheets, score

    a, b = plan(True), plan(False)
    sw, sh, sheets, _ = b if b[3] > a[3] * 1.0001 else a   # one sheet orientation for the whole job (matters for two-sided printing)
    pages = []
    for cells in sheets:
        c = b""
        for im, r, k, dispw, disph, cx, cy in cells:
            tr = (r + im["base"]) % 360
            co, si = {0: (1, 0), 90: (0, 1), 180: (-1, 0), 270: (0, -1)}[tr]
            W, H = im["rw"] * k, im["rh"] * k
            A, B, C, D = co * W, -si * W, si * H, co * H
            clip = b"%.2f %.2f %.2f %.2f re W n " % (cx - dispw / 2, cy - disph / 2, dispw, disph) if fill else b""
            c += b"q %s%.3f %.3f %.3f %.3f %.3f %.3f cm /Im%d Do Q\n" % (clip, A, B, C, D, cx - .5 * A - .5 * C, cy - .5 * B - .5 * D, im["n"])
            if o["border"]:
                c += b"q 0.6 G 0.5 w %.2f %.2f %.2f %.2f re S Q\n" % (cx - dispw / 2, cy - disph / 2, dispw, disph)
        pages.append((sw, sh, c))
    dest = out_pdf(items[0][0], "sheet")
    open(dest, "wb").write(make_pdf(pages, {}, image=[(i["d"], i["rw"], i["rh"], i["comps"]) for i in imgs]))
    if o["wm"] or o["hdr"] or o["ftr"]:
        dest, _ = process_pdf(dest, dict(neutral_fit(o), nup=1, booklet=False, border=False, range="", pages="", reverse=False), name)
    return dest, len(pages)


class JoinJob(list):
    """Received files of any kind that are to be printed as ONE job (the app's "Print all files as one job")."""


def join_to_pdf(paths, o, notes):
    """Every file -> PDF pages, all pages joined in the order the files were selected -> one PDF."""
    if not need_pypdf():
        raise RuntimeError("printing files as one job needs the 'pypdf' package on the PC and it could not be installed automatically (pip install pypdf)")
    from pypdf import PdfReader, PdfWriter
    wr, used, keep = PdfWriter(), 0, []
    for p in paths:
        ext = os.path.splitext(p)[1].lower()
        name = os.path.basename(p).split("_", 1)[-1]
        try:
            if ext == ".pdf": src = p
            elif ext in IMAGES: src = img_to_pdf(p, o, exact=True)
            elif ext in TEXT_EXT: src = txt_to_pdf(p, dict(o, scale=0, hard=0.0, margin=0 if o["margin"] >= 0 else -1))   # sheet margin comes from the layout step
            elif ext in OFFICE_EXT: src = office_to_pdf(p)
            else: raise RuntimeError("type not supported")
            rd = PdfReader(src)
            if rd.is_encrypted:
                try: rd.decrypt("")
                except Exception: pass
            keep.append(rd)
            for pg in rd.pages:
                wr.add_page(pg)
            used += 1
        except Exception as e:
            log(f"joined job: {name} skipped: {e}")
            notes.append(f"{name} skipped ({str(e)[:100]})")
    if not used:
        raise RuntimeError("none of the files could be turned into pages")
    dest = out_pdf(paths[0], "join")
    with open(dest, "wb") as f:
        wr.write(f)
    return dest


PS_SPOOL = r"""
param([string]$Printer)
try { @(Get-PrintJob -PrinterName $Printer -ErrorAction Stop).Count } catch { 'x' }
"""
RETRY_FAST = 4.0   # seconds: a print command that fails later than this was already busy printing / spooling


def spool_count(printer):
    """Number of jobs waiting in the PC's queue for that printer (None = cannot tell). Used to see whether a failed command left a job behind."""
    try:
        if WIN:
            if not printer:
                return None
            r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_script("spool.ps1", PS_SPOOL), printer],
                               capture_output=True, text=True, timeout=10, creationflags=CF)
            t = (r.stdout or "").strip()
            return int(t) if t.isdigit() else None
        r = subprocess.run(["lpstat", "-o"] + ([printer] if printer else []), capture_output=True, text=True, timeout=10)
        return len([l for l in (r.stdout or "").splitlines() if l.strip()])
    except Exception:
        return None


def run_retry(cmd, printer=None, **kw):
    """A print command that exits with an error is tried once more after a pause (the spooler is often busy for a moment) - but ONLY when
    it failed straight away and left nothing in the print queue. A command that ran for a while, or that left a new job in the queue, may
    already have sent the document: trying again would print it twice. A timeout is never retried either."""
    n0 = spool_count(printer)
    t0 = time.time()
    try:
        subprocess.run(cmd, check=True, **kw)
    except subprocess.CalledProcessError as e:
        took = time.time() - t0
        n1 = spool_count(printer)
        if n0 is not None and n1 is not None and n1 > n0:
            log(f"print command exited with {e.returncode}, but the job is in the queue - not sending it again")
            return
        if took > RETRY_FAST:
            log(f"print command failed after {took:.0f}s (exit {e.returncode}) - not retried, it may have printed")
            raise
        log(f"print command failed at once (exit {e.returncode}), trying once more")
        time.sleep(3)
        subprocess.run(cmd, check=True, **kw)


SWAP_FILE = os.path.join(APP, "duplex_swap.txt")   # printer names (one per line, # = comment, * = every printer) whose landscape sheets come out upside down on the back


def duplex_swap(pr):
    try:
        with open(SWAP_FILE, encoding="utf-8") as f:
            names = [l.strip().lower() for l in f if l.strip() and not l.lstrip().startswith("#")]
    except OSError:
        return False
    return "*" in names or (pr or "").lower() in names


def hard_margin(pr):
    """The printer's hard margin in points (it cannot print closer to the edge; 0 = unknown / not Windows)."""
    if not WIN:
        return 0.0
    try:
        info = printer_info(pr or None, max_age=600)
        v = max(float((info or {}).get("hx") or 0), float((info or {}).get("hy") or 0)) * 0.72   # hundredths of an inch -> points
        return min(36.0, math.ceil(v * 2) / 2.0)
    except Exception:
        return 0.0


def swap_duplex(pdf, o, pr):
    """Printers that turn the back of landscape sheets upside down: long <-> short edge for those sheets (switch: duplex_swap.txt)."""
    if o["duplex"] not in ("long", "short") or not duplex_swap(pr or DEFAULT_PRINTER[0]) or not have_pypdf():
        return o
    try:
        from pypdf import PdfReader
        pg = PdfReader(pdf).pages[0]
        if float(pg.cropbox.width) > float(pg.cropbox.height):
            log(f"landscape sheets: duplex {o['duplex']} edge sent as {'short' if o['duplex'] == 'long' else 'long'} edge (duplex_swap.txt)")
            return dict(o, duplex="short" if o["duplex"] == "long" else "long")
    except Exception as e:
        log(f"duplex swap skipped: {e}")
    return o


def send_pdf(pdf, o, pr, sm, notes):
    o = swap_duplex(pdf, o, pr)
    if not WIN:
        cmd = ["lp"] + (["-d", pr] if pr else []) + ["-n", str(o["copies"])]
        sides = {"off": "one-sided", "long": "two-sided-long-edge", "short": "two-sided-short-edge"}.get(o["duplex"])
        if sides: cmd += ["-o", "sides=" + sides]
        if o["color"] == "mono": cmd += ["-o", "print-color-mode=monochrome"]
        run_retry(cmd + [pdf], printer=pr or DEFAULT_PRINTER[0], timeout=120)
    elif sm:
        run_retry(sumatra_args(sm, pdf, o, pr), printer=pr or DEFAULT_PRINTER[0], timeout=300, creationflags=0x08000000)
    else:
        if pr or o["duplex"] or o["color"]:
            notes.append("printer / duplex / colour ignored: SumatraPDF is not installed")
        for _ in range(min(o["copies"], 10)):
            os.startfile(pdf, "print")


def print_file(path, o):
    """Prints one file with the chosen options; returns notes (things that could not be honoured)."""
    notes = []
    pr = o["printer"] or None
    sm = find_sumatra() if WIN else None
    if o["margin"] >= 0:
        o = dict(o, hard=hard_margin(pr))   # margin "None" = as close to the edge as this printer can print
        if o["hard"] > o["margin"]:
            log(f"margin {o['margin']}pt raised to the printer's hard margin {o['hard']:g}pt")
    if isinstance(path, JoinJob):   # mixed files sent as one job: join the pages, then lay out once
        pdf = join_to_pdf(path, o, notes)
        if needs_layout(o) or needs_fitting(o):
            pdf, sheets = process_pdf(pdf, o, os.path.basename(path[0]).split("_", 1)[-1])
            log(f"laid out {len(path)} joined files: {sheets} sheet{'s' if sheets != 1 else ''}")
        send_pdf(pdf, dict(o, fit="noscale") if needs_fitting(o) and o["paper"] else o, pr, sm, notes)
        return notes
    if isinstance(path, list):   # pictures sent together: one set of sheets
        pdf, sheets = images_to_sheets(path, o, "pictures")
        log(f"laid out {len(path)} pictures: {sheets} sheet{'s' if sheets != 1 else ''}")
        send_pdf(pdf, dict(o, fit="noscale") if o["paper"] else o, pr, sm, notes)
        return notes
    ext = os.path.splitext(path)[1].lower()
    name = os.path.basename(path).split("_", 1)[-1]
    fitting = needs_fitting(o)
    layout = needs_layout(o)
    pdf = path if ext == ".pdf" else None
    converted = False   # office document turned into a PDF below: from here on it is handled like a real PDF
    ol = o   # options for process_pdf: pictures and text are fitted while they are converted, so only a real PDF is fitted again
    if ext in IMAGES and (sm or layout or fitting or not WIN):
        try:
            pdf = img_to_pdf(path, o)
            ol = neutral_fit(o)
        except Exception as e:
            if layout or fitting:
                raise
            log(f"image conversion failed ({e}), printing the picture directly")
    elif ext in TEXT_EXT and (layout or fitting):
        pdf = txt_to_pdf(path, o)
        ol = neutral_fit(o)
    elif ext in OFFICE_EXT and (layout or fitting or ((o["duplex"] or o["color"]) and sm)):
        try:
            pdf = office_to_pdf(path)
            converted = True
        except Exception as e:
            log(f"{ext} -> PDF failed: {e}")
            notes.append(f"could not convert {ext} to PDF ({str(e)[:140]}) - printed by its own app: only printer and copies apply")
            pdf = None
    elif (layout or fitting) and ext != ".pdf":
        notes.append(f"layout / fitting options skipped: {ext} files only support printer and copies")
    if pdf and (layout or (fitting and (ext == ".pdf" or converted))):
        pdf, sheets = process_pdf(pdf, ol, name)
        log(f"laid out {name}: {sheets} sheet{'s' if sheets != 1 else ''}")
    if pdf:
        if fitting and o["paper"]:
            o = dict(o, fit="noscale")   # the sheet already has the chosen paper size, the printer must not scale it again
        send_pdf(pdf, o, pr, sm, notes)
        return notes
    if ext in IMAGES and WIN:
        print_image(path, pr or "", o["copies"])
        if o["duplex"] or o["color"]: notes.append("duplex / colour need SumatraPDF")
        return notes
    if not WIN:
        subprocess.run(["lp"] + (["-d", pr] if pr else []) + ["-n", str(o["copies"]), path], check=True, timeout=60)
        return notes
    if o["duplex"] or o["color"]:
        notes.append("duplex / colour only work for PDF, image and text files")
    for _ in range(min(o["copies"], 10)):
        if pr:
            ps1 = ps_script("printto.ps1", PS_PRINTTO)
            r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1, path, pr],
                               capture_output=True, text=True, timeout=120, creationflags=0x08000000)
            if r.returncode != 0:
                raise RuntimeError(f"no app on the PC can print {ext} files to a chosen printer - choose the default printer")
        else:
            os.startfile(path, "print")   # whatever app is registered for this file type prints it on the default printer
        time.sleep(1)
    return notes


PRINTQ = queue.Queue()
CURRENT = [None]                          # name of the job being printed right now
HISTORY = collections.deque(maxlen=40)    # finished jobs, newest last: {t, name, ok, printer, note, warning, error}
SEQ = itertools.count(1)                  # makes every received file name unique, even within the same second
DEFAULT_PRINTER = [None]   # refreshed regularly; shown in the window and reported to the app
PRINTER_PROBLEM = [None]   # e.g. "paper jam" (None = nothing reported)

# One PowerShell call: default printer + its status + the states of the jobs waiting in its Windows queue.
PS_STATE = r"""
param([string]$Name)
$d = if ($Name) { Get-CimInstance Win32_Printer | Where-Object { $_.Name -eq $Name } } else { Get-CimInstance Win32_Printer | Where-Object { $_.Default } }
if ($d) {
  $p = Get-Printer -Name $d.Name -ErrorAction SilentlyContinue
  $j = @(Get-PrintJob -PrinterName $d.Name -ErrorAction SilentlyContinue | ForEach-Object { [string]$_.JobStatus })
  $hx = 0; $hy = 0
  try { Add-Type -AssemblyName System.Drawing; $ps = New-Object System.Drawing.Printing.PrinterSettings; $ps.PrinterName = $d.Name; $hx = [double]$ps.DefaultPageSettings.HardMarginX; $hy = [double]$ps.DefaultPageSettings.HardMarginY } catch { }
  [pscustomobject]@{ name=$d.Name; status=[string]$p.PrinterStatus; offline=[bool]$d.WorkOffline; detected=[int]$d.DetectedErrorState; port=[string]$p.PortName; jobs=$j; hx=$hx; hy=$hy } | ConvertTo-Json -Compress
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


PRINTERS = []   # names of all printers installed on the PC


def list_printers():
    if not WIN:
        try:
            return [l.split()[1] for l in subprocess.run(["lpstat", "-a"], capture_output=True, text=True, timeout=10).stdout.splitlines() if l.strip()]
        except Exception:
            return []
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-Printer | ForEach-Object { $_.Name } | ConvertTo-Json -Compress"],
                             capture_output=True, text=True, timeout=30, creationflags=0x08000000).stdout.strip()
        v = json.loads(out) if out else []
        return [v] if isinstance(v, str) else [str(x) for x in v]
    except Exception:
        return []


_PI_CACHE = {}   # printer name (None = default) -> (time, info)
_PI_LOCK = threading.Lock()


def printer_info(name=None, max_age=0):
    """Printer state through PowerShell (1-3 s). max_age > 0 reuses an answer that young: the phone pings often and every ping
    used to start its own PowerShell. The watcher after a print passes 0 (always fresh)."""
    if not WIN:
        return None
    if max_age > 0:
        with _PI_LOCK:
            hit = _PI_CACHE.get(name)
        if hit and time.time() - hit[0] < max_age:
            return hit[1]
    info = _printer_info(name)
    with _PI_LOCK:
        _PI_CACHE[name] = (time.time(), info)
        if len(_PI_CACHE) > 20:
            _PI_CACHE.pop(next(iter(_PI_CACHE)))
    return info


def _printer_info(name=None):
    try:
        ps1 = ps_script("pstate.ps1", PS_STATE)
        out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1] + (["-Name", name] if name else []),
                             capture_output=True, text=True, timeout=30, creationflags=0x08000000).stdout.strip()
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
            PRINTERS[:] = list_printers()
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


def watch_printer(seconds=10, printer=None):
    """After a file was handed over: wait a few seconds and report what the printer says if the job gets stuck (else None)."""
    if not WIN:
        return None
    end = time.time() + seconds
    issue = None
    while time.time() < end:
        time.sleep(2)
        info = printer_info(printer)
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


def record(name, res):
    HISTORY.append(dict(t=time.strftime("%H:%M:%S"), name=name, ok="error" not in res, printer=res.get("printer"),
                        note=res.get("note"), warning=res.get("warning"), error=res.get("error")))


def print_one(path, evt, res, o):
    name = f"{len(path)} files (one job)" if isinstance(path, JoinJob) else f"{len(path)} pictures" if isinstance(path, list) else os.path.basename(path)
    CURRENT[0] = name
    try:
        pr = o["printer"] or DEFAULT_PRINTER[0]
        d = describe_opts(o)
        log(f"printing {name}" + (f" on {pr}" if pr else "") + (f" [{d}]" if d else ""))
        notes = print_file(path, o)
        if notes:
            res["note"] = "; ".join(notes)
            log(f"NOTE {name}: {res['note']}")
        warn = watch_printer(10, o["printer"] or None)
        if warn:
            log(f"WARNING {name}: printer reports {warn}")
            res["warning"] = warn
        else:
            log("printed " + name)
        res.update(ok=True, printer=pr)
        record(name, res)
        evt.set()   # tell the phone
        time.sleep(4)   # let the spooler pick the file up before the next one starts
    except Exception as e:
        msg = friendly(e, name) if not isinstance(e, (RuntimeError, ValueError)) else str(e)[:300]
        log(f"PRINT FAILED {name}: {msg}")
        res["error"] = msg
        record(name, res)
        evt.set()
    finally:
        CURRENT[0] = None


def print_worker():
    """One job at a time. Whatever goes wrong with one job (even a bug), the loop survives: before, a crash here left the service
    answering /ping as 'ready' while nothing was ever printed again."""
    while True:
        try:
            path, evt, res, o = PRINTQ.get()
        except Exception:
            continue
        try:
            print_one(path, evt, res, o)
        except BaseException as e:   # not reachable for normal errors (print_one handles them), this is the last line of defence
            log(f"PRINT WORKER ERROR: {e!r}\n{traceback.format_exc()}")
            res.setdefault("error", "internal error: " + str(e)[:200])
            evt.set()
        finally:
            PRINTQ.task_done()


def cleanup_old(days=3):
    """Deletes received files / generated PDFs older than `days`, then the oldest ones while the folder is above INBOX_CAP.
    Runs at start and every hour (before: only at start, so a PC that stays on for weeks collected everything)."""
    cut = time.time() - days * 86400
    keep = []
    for d in (INBOX, os.path.join(APP, "lo-out")):
        try:
            names = os.listdir(d)
        except OSError:
            continue
        for n in names:
            p = os.path.join(d, n)
            try:
                if not os.path.isfile(p):
                    continue
                st = os.stat(p)
                if st.st_mtime < cut:
                    os.remove(p)
                else:
                    keep.append((st.st_mtime, st.st_size, p))
            except OSError:
                pass
    total = sum(k[1] for k in keep)
    for _, size, p in sorted(keep):
        if total <= INBOX_CAP:
            break
        try:
            os.remove(p)
            total -= size
        except OSError:
            pass


def housekeeping():
    while True:
        time.sleep(3600)
        try:
            cleanup_old()
            for k in [k for k, v in list(BATCHES.items()) if time.time() - v["t"] > 900]:
                with BATCH_LOCK:
                    BATCHES.pop(k, None)
        except Exception as e:
            log(f"housekeeping: {e}")


# ------------------------------------------------------------------ HTTP service
def safe_name(n):
    n = os.path.basename((n or "file").replace("\\", "/"))
    n = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", n).strip(" .")
    return n[:120] or "file"


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LANSharePrint/" + VERSION
    timeout = 60   # idle socket limit: a phone that drops off Wi-Fi mid-upload used to keep its handler thread (and file) forever

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

    CONVERT_LOCK = threading.Lock()   # one conversion at a time (Word / LibreOffice do not like parallel automation)

    def convert(self, u):
        name = safe_name(parse_qs(u.query).get("name", ["file"])[0])
        ext = os.path.splitext(name)[1].lower()
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(400, {"error": "missing Content-Length"})
        if ext not in OFFICE_EXT:
            return self.reply(415, {"error": f"{ext or 'this file type'} is not an office document"})
        if n > MAX_BYTES:
            return self.reply(413, {"error": "file too large"})
        os.makedirs(INBOX, exist_ok=True)
        src = os.path.join(INBOX, f"pv{int(time.time() * 1000) % 10**9}_{name}")
        left, pdf = n, None
        try:
            with open(src, "wb") as f:
                while left > 0:
                    b = self.rfile.read(min(1 << 20, left))
                    if not b:
                        raise ConnectionError("upload interrupted")
                    f.write(b)
                    left -= len(b)
            with H.CONVERT_LOCK:
                pdf = office_to_pdf(src)
            data = open(pdf, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True
            log(f"preview conversion {name}: {len(data)} bytes")
        except ConnectionError as e:
            log(f"preview upload failed {name}: {e}")
        except Exception as e:
            log(f"preview conversion failed {name}: {e}")
            try:
                self.reply(500, {"error": str(e)[:300]})
            except OSError:
                pass
        finally:
            for p in (src, pdf):
                try:
                    if p: os.remove(p)
                except OSError:
                    pass

    def do_GET(self):
        if not self.local_only():
            return self.reply(403, {"error": "LAN/Tailscale only"})
        u = urlparse(self.path)
        if u.path == "/ping":
            want = (parse_qs(u.query).get("printer") or [""])[0]
            pr, prob = DEFAULT_PRINTER[0], PRINTER_PROBLEM[0]
            if want and want in PRINTERS:   # live state of the printer chosen in the app
                pr = want
                info = printer_info(want, max_age=6)
                prob = describe_problem(info) or (job_problem(info) if info else None)
            return self.reply(200, {"ok": True, "name": socket.gethostname(), "version": VERSION, "queued": PRINTQ.qsize(),
                                    "busy": CURRENT[0], "printer": pr, "problem": prob, "default": DEFAULT_PRINTER[0], "printers": PRINTERS,
                                    "pypdf": have_pypdf(), "engine": "SumatraPDF" if (WIN and find_sumatra()) else None,
                                    "office": office_engine(), "convert": True})
        if u.path == "/jobs":   # what is printing, how many wait, and the last finished jobs (for the window / future app screens)
            return self.reply(200, {"ok": True, "current": CURRENT[0], "queued": PRINTQ.qsize(), "history": list(HISTORY)})
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
        if u.path == "/clear":   # drop every job that is still waiting (the one being printed right now is not interrupted)
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self.reply(403, {"error": "local only"})
            dropped = 0
            while True:
                try:
                    _, evt, res, _ = PRINTQ.get_nowait()
                except queue.Empty:
                    break
                res["error"] = "cancelled on the PC"
                evt.set()
                PRINTQ.task_done()
                dropped += 1
            if dropped:
                log(f"queue cleared from the window: {dropped} job{'s' if dropped != 1 else ''} dropped")
            return self.reply(200, {"ok": True, "dropped": dropped})
        if u.path == "/convert":   # office file -> PDF, only so the app can show a real page preview (nothing is printed)
            return self.convert(u)
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
        o = parse_opts(parse_qs(u.query))
        if o["printer"] and PRINTERS and o["printer"] not in PRINTERS:
            return self.reply(400, {"error": f"unknown printer '{o['printer']}'"})
        if PRINTQ.qsize() >= MAX_QUEUE:
            return self.reply(503, {"error": f"print queue is full ({MAX_QUEUE} waiting) - the printer is probably stuck"})
        os.makedirs(INBOX, exist_ok=True)
        try:
            if shutil.disk_usage(INBOX).free < n + MIN_FREE:
                return self.reply(507, {"error": "not enough free disk space on the PC"})
        except OSError:
            pass
        dest = os.path.join(INBOX, f"{time.strftime('%H%M%S')}{next(SEQ) % 1000:03d}_{name}")   # unique even for two files in one second
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
            self.close_connection = True   # the rest of the body is unread: this connection cannot be reused
            return
        log(f"received {name} ({n} bytes) from {self.client_address[0]}")
        evt, res = threading.Event(), {}
        qs = parse_qs(u.query)
        bid = re.sub(r"[^0-9A-Za-z]", "", (qs.get("batch") or [""])[0])[:20]
        key = re.sub(r"[^0-9A-Za-z_-]", "", (qs.get("key") or [""])[0])[:60]
        job = dest

        def drop_dest():
            try: os.remove(dest)
            except OSError: pass

        def answer(ev, rs):
            if not ev.wait(90):   # still waiting in the queue: the phone treats this as sent
                return self.reply(200, {"ok": True, "queued": True})
            if "error" in rs:
                return self.reply(500, {"error": rs["error"]})   # the phone shows the reason
            self.reply(200, {"ok": True, "printed": True, "printer": rs.get("printer"), "warning": rs.get("warning"), "note": rs.get("note")})

        ent = None
        if key:   # the same file arriving again (an app retry, a reconnect over Tailscale ...) must not print twice
            with SEEN_LOCK:
                for k in [k for k, v in SEEN.items() if time.time() - v["t"] > SEEN_KEEP]:
                    SEEN.pop(k, None)
                old = SEEN.get(key)
                if old is None:
                    ent = SEEN[key] = {"t": time.time(), "evt": None, "res": None}
            if old is not None:
                drop_dest()
                log(f"repeated request ignored: {name} (key {key})")
                if old["evt"] is not None:
                    return answer(old["evt"], old["res"])   # same answer as the first one gets
                return self.reply(200, {"ok": True, "duplicate": True, "batched": bool(bid)})
        join = (qs.get("join") or [""])[0] == "1"       # mixed files for ONE job (any type)
        skip = (qs.get("skip") or [""])[0] == "1"       # the app could not convert this one: it only keeps the count right
        if bid and (ext in IMAGES or join):   # several files for one job / set of sheets: wait for the last one
            gq = lambda k: (qs.get(k) or [""])[0]
            idx, cnt, rot = _int(gq("idx"), 0, 0, 999), _int(gq("n"), 1, 1, 1000), _int(gq("rot"), 0, 0, 359) // 90 * 90 % 360
            with BATCH_LOCK:
                for k in [k for k, v in BATCHES.items() if time.time() - v["t"] > SEEN_KEEP]:
                    BATCHES.pop(k, None)
                for k in [k for k, t in FINISHED_BATCHES.items() if time.time() - t > SEEN_KEEP]:
                    FINISHED_BATCHES.pop(k, None)
                finished = bid in FINISHED_BATCHES
                if not finished:
                    bt = BATCHES.setdefault(bid, {"t": time.time(), "items": {}})
                    bt["items"][idx] = None if skip else (dest, rot)
            if skip:
                drop_dest()
            if finished:   # this set of sheets is already queued: a late or repeated picture would print alone on a sheet
                drop_dest()
                log(f"picture of an already finished batch ignored: {name}")
                return self.reply(200, {"ok": True, "duplicate": True, "batched": True})
            if idx < cnt - 1:
                return self.reply(200, {"ok": True, "batched": True})
            end = time.time() + 6   # pictures that arrive out of order (parallel uploads): give the stragglers a moment
            while time.time() < end:
                with BATCH_LOCK:
                    if len(bt["items"]) >= cnt:
                        break
                time.sleep(0.25)
            with BATCH_LOCK:
                got = [bt["items"][k] for k in sorted(bt["items"]) if bt["items"][k] is not None]
                job = JoinJob(d for d, _ in got) if join else got
                BATCHES.pop(bid, None)
                FINISHED_BATCHES[bid] = time.time()
            if not got:
                return self.reply(400, {"error": "nothing to print"})
        if ent is not None:
            ent["evt"], ent["res"] = evt, res
        PRINTQ.put((job, evt, res, o))
        answer(evt, res)


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


class Server(ThreadingHTTPServer):
    request_queue_size = 32   # default 5: a phone sending a batch of pictures plus pings could get connection refused

    def handle_error(self, request, client_address):   # a phone that vanished mid-request is normal: one short line, no traceback spam
        e = sys.exc_info()[1]
        if not isinstance(e, (ConnectionError, TimeoutError, socket.timeout)):
            log(f"request from {client_address[0]} failed: {e!r}")


def serve():
    os.makedirs(INBOX, exist_ok=True)
    threading.excepthook = lambda a: log(f"THREAD CRASH in {getattr(a.thread, 'name', '?')}: {a.exc_value!r}\n"
                                         + "".join(traceback.format_exception(a.exc_type, a.exc_value, a.exc_traceback)))
    try:
        srv = Server(("0.0.0.0", PORT), H)
    except OSError as e:   # pythonw has no console: without this line a second copy / busy port just vanished silently
        log(f"cannot listen on port {PORT}: {e}")
        return
    srv.daemon_threads = True
    cleanup_old()
    threading.Thread(target=print_worker, daemon=True, name="print-worker").start()
    threading.Thread(target=refresh_printer, daemon=True, name="printer-state").start()
    threading.Thread(target=housekeeping, daemon=True, name="housekeeping").start()
    if not all(have_pkg(m) for m, _ in PKGS):
        threading.Thread(target=ensure_packages, daemon=True).start()
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
    ensure_pypdf()
    ok = start_service()
    if not ok:
        print(f"Print service FAILED to start - see {LOG}")
        input("Press Enter to close...")
        return
    if not fw:
        log("WARNING: firewall rule was not added; the phone may not be able to connect")
    subprocess.Popen([pyw(), TARGET, "--ui"], creationflags=0x00000008 | 0x08000000, close_fds=True)   # window opens, this console closes


# ------------------------------------------------------------------ window: live print log (viewer only)
def test_page_pdf(info):
    """A4 test page: printer / PC / version, a 20 pt frame (shows how much the printer clips), grey ramp and colour swatches."""
    w, h = PAPERS["A4"]
    lines = ["LANShare Print - test page", time.strftime("%Y-%m-%d %H:%M:%S"), "PC: " + socket.gethostname(),
             "Printer: " + str((info or {}).get("printer") or "default printer"), "Service version: " + VERSION]
    extra = {}
    c = b"0.5 w 0 G 20 20 %.1f %.1f re S\n" % (w - 40, h - 40)
    c += b"BT /F1 22 Tf 60 %.1f Td (%s) Tj ET\n" % (h - 90, enc_text(lines[0], extra))
    for i, ln in enumerate(lines[1:]):
        c += b"BT /F1 12 Tf 60 %.1f Td (%s) Tj ET\n" % (h - 130 - 20 * i, enc_text(ln, extra))
    for i in range(11):
        c += b"%.2f g %.1f %.1f 36 36 re f\n" % (i / 10.0, 60 + i * 40, h - 330)
    for i, (r, g, b) in enumerate(((0, 1, 1), (1, 0, 1), (1, 1, 0), (1, 0, 0), (0, 1, 0), (0, 0, 1))):
        c += b"%d %d %d rg %.1f %.1f 60 36 re f\n" % (r, g, b, 60 + i * 80, h - 400)
    return make_pdf([(w, h, c)], extra)


def post_local(path, data=b"", timeout=15):
    import urllib.request
    return urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=data, method="POST"), timeout=timeout).read()


def open_path(p):
    try:
        if WIN: os.startfile(p)
        else: subprocess.Popen(["xdg-open" if sys.platform != "darwin" else "open", p])
    except Exception as e:
        log(f"cannot open {p}: {e}")


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

    stopped = [False]   # True after "Stop service": then the window must not restart it by itself
    restarting = [False]

    def toggle():
        if is_running():
            if messagebox.askyesno("Stop print service", "Stop the print service?\nThe phone will not be able to print until you start it again or restart Windows."):
                stopped[0] = True
                stop_service()
        else:
            stopped[0] = False
            start_service()
        refresh()
    btn = tk.Button(bot, text="Stop service", command=toggle, relief="flat", bg="#3a1d24", fg="#ffb3b3")
    btn.pack(side="right")

    def test_page():
        info = ping()
        if not info:
            messagebox.showwarning("Test page", "The print service is not running.")
            return

        def go():
            try:
                post_local("/print?name=test-page.pdf", test_page_pdf(info), 150)
            except Exception as e:
                log(f"test page failed: {e}")
        threading.Thread(target=go, daemon=True).start()

    def clear_queue():
        try:
            n = json.loads(post_local("/clear")).get("dropped", 0)
        except Exception as e:
            log(f"clear queue failed: {e}")
            return
        messagebox.showinfo("Clear queue", f"{n} waiting job{'s' if n != 1 else ''} cancelled." if n else "Nothing was waiting.")

    for label, cmd in (("Clear queue", clear_queue), ("Queue folder", lambda: open_path(INBOX)),
                       ("Open log", lambda: open_path(LOG)), ("Test page", test_page)):
        tk.Button(bot, text=label, command=cmd, relief="flat", bg="#1b2433", fg=FG).pack(side="right", padx=(0, 6))

    pos = [0]

    def add(line):
        tag = "fail" if any(k in line for k in ("FAILED", "WARNING", "CRASH", "ERROR", "cannot listen")) else "ok" if " printed " in line else "dim" if "ready" in line or "stopped" in line else ""
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
            busy = info.get("busy")
            status.configure(text=f"Running  \u00b7  {ips}  \u00b7  port {PORT}  \u00b7  " + (f"printer: {pr}" if pr else "NO DEFAULT PRINTER") + (f"  \u00b7  \u26a0 {prob}" if prob else "")
                             + (f"  \u00b7  printing {busy}" if busy else "") + (f"  \u00b7  {q} waiting" if q else ""))
        else:
            dot.configure(fg="#f87171")
            btn.configure(text="Start service", bg="#1d3a2a", fg="#b3ffd0")
            status.configure(text="Stopped" if stopped[0] else "Stopped  \u00b7  restarting...")
        return info

    def revive():
        try:
            start_service()
        finally:
            restarting[0] = False

    def tick():
        if refresh() is None and not stopped[0] and not restarting[0]:
            restarting[0] = True   # the service died (crash, killed in Task Manager): bring it back unless the user stopped it here
            log("print service was not running - restarting it")
            threading.Thread(target=revive, daemon=True).start()
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
    try:
        main()
    except KeyboardInterrupt:
        pass
    except BaseException as e:   # pythonw hides tracebacks: the log is the only place a crash can be read
        if not isinstance(e, SystemExit):
            log(f"CRASH: {e!r}\n{traceback.format_exc()}")
        raise
