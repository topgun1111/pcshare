"""FTP client cache check (part 5). Usage: python ftp_cache_test.py <app base url, e.g. http://192.168.1.5:8080> <dev id of a saved ftp: server> [folder, default /]
Needs a writable folder on the server. Not run on a device yet."""
import json, sys, time, urllib.parse as u, urllib.request as r
base, dev = sys.argv[1].rstrip("/"), sys.argv[2]
fold = sys.argv[3] if len(sys.argv) > 3 else "/"
def get(p):
    with r.urlopen(base + p, timeout=60) as f: return json.load(f)
def ls(fresh=False):
    q = "/api/ls?dev=%s&path=%s%s" % (u.quote(dev), u.quote(fold), "&fresh=1" if fresh else "")
    t = time.time(); d = get(q); return d["items"], int((time.time() - t) * 1000)
get("/api/ftpcache?clear=1"); get("/api/ftpcache?ttl=30")
_, miss_ms = ls(); _, hit_ms = ls()
st = get("/api/ftpcache")
assert st["miss"] >= 1 and st["hits"] >= 1, st
print("miss %d ms, hit %d ms" % (miss_ms, hit_ms)); assert hit_ms <= miss_ms or hit_ms < 30
a, _ = ls(fresh=True); b, _ = ls()
assert [i["name"] for i in a] == [i["name"] for i in b], "fresh=1 must reload and refill the cache"
print("TIMING LOG:", *get("/api/ftpcache")["log"][:5], sep="\n  ")
print("MANUAL: upload / rename in the app, then ls() again must show the change at once (cache dropped by FtpFs.dirty).")
get("/api/ftpcache?clear=1"); assert get("/api/ftpcache")["folders"] == 0
print("ALL OK")
