"""Entry point called from LanShareService. Runs lanshare.main() and exposes the UI URL."""
import os, sys, webbrowser

URL = None
ERROR = None


def _capture(url, *a, **k):
    global URL
    URL = url
    return True


def get_error():
    return ERROR


def get_url():
    return URL


def start(files_dir, root):
    os.environ["LANSHARE_CFG"] = os.path.join(files_dir, "lanshare.json")
    os.environ["HOME"] = files_dir
    webbrowser.open = _capture          # WebView loads the URL instead of an external browser
    sys.argv = ["lanshare.py", root]
    global ERROR
    try:
        import lanshare
        lanshare.main()                  # blocks (serve_forever)
    except BaseException as e:           # surface startup failures in the app UI
        import traceback
        ERROR = traceback.format_exc()[-1500:]


def rescan():
    """Called on Wi-Fi/hotspot changes: refresh own IPs + sweep the subnet."""
    try:
        import lanshare
        d = lanshare.DISC
        if d:
            getattr(d, "scan_now")()
    except Exception:
        pass
