"""Adds the Phone Explorer route (POST /phoneshare) to YOUR pcprint.py without replacing anything else.
Usage (on the PC):  python patch_pcprint.py "%APPDATA%\\LANSharePrint\\pcprint.py"   (and also the copy in your repo / Downloads)
Then restart the service: open the pcprint.py window and press Exit / Stop, start pcprint.py again (VERSION is raised by 1 so an old service is restarted)."""
import re, sys, shutil

ROUTE = '''        if u.path == "/phoneshare":   # phone says "open my storage here": Explorer opens the phone's FTP share (the phone's address is the caller's, nothing to type)
            q = parse_qs(u.query)
            user, pin = q.get("user", ["phone"])[0], q.get("pin", [""])[0]
            if not (re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", user) and re.fullmatch(r"[A-Za-z0-9]{1,32}", pin)):
                return self.reply(400, {"error": "bad user / pin"})
            ip = self.client_address[0].split("%")[0]
            if ":" in ip:
                return self.reply(400, {"error": "IPv4 only"})
            try:
                port = max(1, min(65535, int(q.get("port", ["2121"])[0])))
            except ValueError:
                port = 2121
            url = f"ftp://{user}:{pin}@{ip}:{port}/"
            try:
                if WIN: subprocess.Popen(["explorer.exe", url])   # explorer.exe (not os.startfile): ftp: may be bound to a browser, this forces the Explorer folder view
                else: subprocess.Popen(["xdg-open", url])
            except Exception as e:
                log(f"phone storage: cannot open Explorer: {e}")
                return self.reply(500, {"error": str(e)})
            log(f"phone storage opened: ftp://{user}:***@{ip}")
            return self.reply(200, {"ok": True})
'''

def main(path):
    s = open(path, encoding="utf-8").read()
    if '"/phoneshare"' in s:
        print("already patched:", path); return
    m = re.search(r'^(        cp = u\.path\[2:\] if u\.path\.startswith\("/p/"\).*)$', s, re.M) or re.search(r'^(        if u\.path == "/convert":.*)$', s, re.M)
    if not m:
        sys.exit("cannot find the place in do_POST - tell Claude the pcprint.py version")
    s = s[:m.start()] + ROUTE + s[m.start():]
    v = re.search(r'^VERSION = "(\d+)"', s, re.M)
    if v:
        s = s[:v.start()] + f'VERSION = "{int(v.group(1)) + 1}"' + s[v.end():]
    shutil.copyfile(path, path + ".bak")
    open(path, "w", encoding="utf-8").write(s)
    print("patched:", path, "(backup .bak)")

if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(__doc__)
    for a in sys.argv[1:]: main(a)
