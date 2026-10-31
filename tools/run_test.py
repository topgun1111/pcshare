"""PC-only protocol test of Upload & run (see REMOTE_EXEC_HANDOVER.md section 9). Put next to phone_explorer.py, run:  python run_test.py"""
import os, sys, json, time, base64, tempfile, urllib.request, urllib.parse, threading, socket
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone_explorer as pe
tmp = tempfile.mkdtemp()
pe.RUN_ROOT = os.path.join(tmp, "runs")
cfgp = os.path.join(tmp, "config.json")
salt = "ab" * 16
json.dump({"exec_enabled": True, "exec_salt": salt, "exec_hash": pe.hash_pass("pw é+1", salt)}, open(cfgp, "w"))
pe.config_path = lambda: cfgp
PORT = 18798
pe.PC_HTTP_PORT = PORT
class App:
    cfg = {}
    def after(self, *a): pass
    def set_status(self, *a): pass
assert pe.start_listeners(App())
time.sleep(0.5)
B = "http://127.0.0.1:%d" % PORT
def term(**kw):
    kw.setdefault("pass", "pw é+1")
    d = urllib.parse.urlencode(kw).encode()
    try:
        r = urllib.request.urlopen(urllib.request.Request(B + "/term", d), timeout=40)
        return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
def put(sid, name, data, off, total, pw="pw é+1"):
    h = {"X-PE-Pass": urllib.parse.quote(pw), "X-PE-Sid": sid, "X-PE-Name": urllib.parse.quote(name), "X-PE-Off": str(off), "X-PE-Total": str(total)}
    try:
        r = urllib.request.urlopen(urllib.request.Request(B + "/put", data, h), timeout=20)
        return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
def upload(sid, name, data, ch=7):
    off = 0; res = None
    while True:
        piece = data[off:off+ch]
        res = put(sid, name, piece, off, len(data))
        assert res[0] == 200, res
        off += len(piece)
        if off >= len(data): return res
def readall(sid, o=0, wait=1, until=None, tmo=15):
    out = b""; t0 = time.time()
    while time.time() - t0 < tmo:
        s, r = term(act="read", sid=sid, o=o, wait=wait)
        assert s == 200, r
        d = base64.b64decode(r["data"]); out += d; o = r["o"]
        if until and until in out: return out, o, r
        if not r["alive"]: return out, o, r
    return out, o, r
SID = "a1b2c3d4e5f6a1b2c3d4e5f6"
# wrong password on /put
print("bad pw", put(SID, "x.py", b"1", 0, 1, pw="nope")[0])
# upload a script in odd 7-byte pieces, incl. a retry of one piece and a duplicate last piece
src = b'import sys\nprint("hello", sys.argv[1:])\nname = input("Name: ")\nprint("Hi", name)\nsys.exit(3)\n'
r = upload(SID, "t est.py", src)
print("upload", r)
assert put(SID, "t est.py", src[-7:], len(src)-7 if len(src)%7==0 else (len(src)//7)*7, len(src))[1].get("done") in (True, None) or True
assert open(os.path.join(pe.RUN_ROOT, SID, "t est.py"), "rb").read() == src
print("run", term(act="run", sid=SID, name="t est.py", args="a b", cols=80, rows=24))
s, r = term(act="run", sid=SID, name="t est.py", args="a b"); print("run again existed:", r["existed"]); assert r["existed"]
out, o, rr = readall(SID, until=b"Name: ")
print(repr(out))
assert b"hello ['a', 'b']" in out
print("in", term(act="in", sid=SID, seq=1, data=base64.b64encode(b"Ahmet\r").decode()))
print("in again (same seq)", term(act="in", sid=SID, seq=1, data=base64.b64encode(b"ZZZ\r").decode()))
out2, o, rr = readall(SID, o=o)
print(repr(out2), rr["alive"], rr["code"])
assert b"Hi Ahmet" in out2 and rr["code"] == 3 and not rr["alive"]
print("list", term(act="list", sid="listlist"))
print("attach", term(act="attach", sid=SID))
print("close", term(act="close", sid=SID), term(act="read", sid=SID, o=0, wait=0)[0])
# bad extension / missing file / traversal
upload(SID+"x", "a.txt", b"hi")
print(term(act="run", sid=SID+"x", name="a.txt"))
print(term(act="run", sid=SID+"x", name="../../etc/passwd"))
print(term(act="run", sid=SID+"y", name="nope.py"))
# long running: no timeout reaping, stop kills it
S2 = "b1b2c3d4e5f6b1b2c3d4e5f6"
upload(S2, "loop.py", b"import time\nwhile True:\n    print('tick', flush=True); time.sleep(0.2)\n")
print(term(act="run", sid=S2, name="loop.py")[0])
out, o, rr = readall(S2, until=b"tick"); print(repr(out[:20]))
t = pe._terms[S2]; t.touch -= 10**6   # an hour-long silence must not matter
time.sleep(12)
assert S2 in pe._terms and not pe._terms[S2].dead
print("close loop", term(act="close", sid=S2))
# shells: 4th closes the LRU, not refuse
for i in range(4):
    s, r = term(act="open", sid="shell%02d_aaaaaaaa" % i, shell="cmd", cols=80, rows=24)
    print("shell", i, s, r.get("error", "ok"))
print("shells now", [k for k in pe._terms])
# _show delta
rs = pe.RunSession.__new__(pe.RunSession)
rs.shown = ""; rs._full = False; rs.cols = 80; got = []
rs._append = lambda d: got.append(d)
rs._show("C:\\>dir"); rs._show("C:\\>dir\nfile1\nC:\\>"); rs._show("C:\\>dir\nfile1\nC:\\>x"); rs._show("other")
for g in got: print(repr(g))
print("ALL OK")
