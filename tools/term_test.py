"""PC-only check of the Remote shell ConPTY. Put next to phone_explorer.py, run:  python term_test.py
Tries the shell start with two different flag sets and prints how many bytes the console sends."""
import os, sys, time, threading, traceback
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

def main():
    import phone_explorer as pe
    home = os.path.expanduser("~")
    for name, flags in (("A: normal", 0x00080000), ("B: with CREATE_NO_WINDOW", 0x00080000 | 0x08000000)):
        print("==", name)
        try:
            p = pe._ConPty("cmd.exe", 80, 24, home, flags)
        except TypeError:
            print("(old phone_explorer.py: no flags option, using its default)"); p = pe._ConPty("cmd.exe", 80, 24, home)
        except Exception as e:
            print("start failed:", e); continue
        got = []
        def rd():
            while True:
                d = p.read()
                if not d: break
                got.append(d)
        threading.Thread(target=rd, daemon=True).start()
        time.sleep(3)
        b = b"".join(got); print("before key:", len(b), "bytes", repr(b[:300]))
        if b"\x1b[6n" in b:
            p.write(b"\x1b[1;1R"); time.sleep(2); print("after cursor answer:", len(b"".join(got)), "bytes")
        p.write(b"echo hello\r"); time.sleep(3)
        b = b"".join(got); print("after echo:", len(b), "bytes", repr(b[-300:]))
        p.close()

try:
    main()
except Exception:
    traceback.print_exc()
input("\nDone. Press Enter to close this window...")
