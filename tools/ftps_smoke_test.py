"""
Smoke test for the phone's FTPS (explicit TLS) option of Phone Explorer.
Usage:  python ftps_smoke_test.py PHONE_IP PIN [PORT]
Needs Settings > Phone Explorer > "FTPS (TLS encryption)" on (stop + start sharing after switching it).
Checks AUTH TLS + PROT P, lists the root, uploads / downloads / deletes a small file in a temporary folder 'PE_TEST'.
The certificate is self-signed, so it is not verified here; compare the printed SHA-256 with the one in the app.
"""
import hashlib
import io
import ssl
import sys
from ftplib import FTP_TLS

ip, pin = sys.argv[1], sys.argv[2]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 2121

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

ftp = FTP_TLS(context=ctx)
ftp.encoding = "utf-8"
ftp.connect(ip, port, timeout=10)
ftp.auth()
der = ftp.sock.getpeercert(binary_form=True)
print("TLS ok, certificate SHA-256:", ":".join(f"{b:02X}" for b in hashlib.sha256(der).digest()))
ftp.login("phone", pin)
ftp.prot_p()
print("login ok, data connections encrypted, pwd =", ftp.pwd())

lines = []
ftp.retrlines("LIST", lines.append)
print("root entries:", len(lines))

ftp.mkd("PE_TEST")
ftp.cwd("PE_TEST")
ftp.storbinary("STOR hello.txt", io.BytesIO(b"hello over tls"))
buf = io.BytesIO()
ftp.retrbinary("RETR hello.txt", buf.write)
assert buf.getvalue() == b"hello over tls", "download mismatch"
print("upload + download ok, size:", ftp.size("hello.txt"))
ftp.delete("hello.txt")
ftp.cwd("/")
ftp.rmd("PE_TEST")
ftp.quit()
print("FTPS test passed")
