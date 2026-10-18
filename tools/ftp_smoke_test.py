"""
Smoke test for the phone's FTP server using Python's ftplib (same protocol Explorer speaks).
Usage:  python ftp_smoke_test.py PHONE_IP PIN [PORT]
Creates/removes a temporary folder 'PE_TEST' in the phone's storage root.
"""
import io
import sys
from ftplib import FTP

ip, pin = sys.argv[1], sys.argv[2]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 2121

ftp = FTP()
ftp.encoding = "utf-8"
ftp.connect(ip, port, timeout=10)
ftp.login("phone", pin)
print("login ok, pwd =", ftp.pwd())

lines = []
ftp.retrlines("LIST", lines.append)
print("root entries:", len(lines))
print("\n".join(lines[:5]))

ftp.mkd("PE_TEST")
ftp.cwd("PE_TEST")
ftp.storbinary("STOR hello.txt", io.BytesIO(b"hello phone"))
buf = io.BytesIO()
ftp.retrbinary("RETR hello.txt", buf.write)
assert buf.getvalue() == b"hello phone", "download mismatch"
print("size:", ftp.size("hello.txt"))
ftp.rename("hello.txt", "renamed ğü.txt")
names = ftp.nlst()
print("after rename:", names)
ml = []
ftp.retrlines("MLSD", ml.append)
assert any("type=file" in x for x in ml), "MLSD failed"
print("MLSD:", ml[0])
ftp.delete(names[0])
ftp.cwd("..")
ftp.rmd("PE_TEST")
ftp.quit()
print("ALL OK")
