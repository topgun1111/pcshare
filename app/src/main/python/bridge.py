"""Chaquopy entry point. Called from ServerService.kt."""
import os

_state = {}


def set_ifaces(spec):
    """'ip/prefix,ip/prefix' from Android LinkProperties (fallback when ioctl/ARP are restricted)."""
    import lanshare
    lanshare.set_extra_ifaces(spec)


def start(root, cfg_path, device_name, ifaces=""):
    if _state:
        return _state["port"]
    os.environ["LANSHARE_CFG"] = cfg_path
    os.environ["LANSHARE_NAME"] = device_name
    import lanshare
    lanshare.set_extra_ifaces(ifaces)
    port, srv = lanshare.serve(root, block=False, open_browser=False)
    _state.update(port=port, srv=srv)
    return port


def info():
    import lanshare
    return {"name": lanshare.CFG.get("name")}


def stop():
    srv = _state.pop("srv", None)
    _state.clear()
    try:
        import lanshare
        if getattr(lanshare, "DISC", None):
            lanshare.DISC.stop()
    except Exception:
        pass
    if srv:
        srv.shutdown()
        srv.server_close()
