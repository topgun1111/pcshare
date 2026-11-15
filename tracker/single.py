"""Tek takip programı kuralı: mpv her açıldığında track.lua yeni bir `tracker live` başlatır;
eski süreçler (başka mpv'den kalma / bekleyen) aynı mpv'yi aynı anda sürmesin diye en son
başlayan sahiplik alır, eskiler ≤ 1 sn içinde kendiliğinden çıkar. PID öldürülmez (PID yeniden
kullanımında yabancı süreç vurulmasın): sahiplik bir kilit dosyasındaki rastgele belirteçtir."""
from __future__ import annotations

import os
import threading
import time
import uuid
from pathlib import Path


class SingleInstance:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        # uuid: Windows saati kaba, aynı süreçte iki nesne aynı time_ns alabiliyordu (CI hatası)
        self.token = f"{os.getpid()}-{time.time_ns()}-{uuid.uuid4().hex}"

    def claim(self) -> bool:
        """Sahipliği al (öncekini düşürür). Dosya yazılamazsa False: koruma devre dışı kalır."""
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(self.token, encoding="utf-8")
            return True
        except OSError:
            return False

    def owned(self) -> bool:
        """Kilit hâlâ bizde mi? Okunamıyorsa (geçici hata) sahip say: yanlışlıkla çıkma."""
        try:
            return self.path.read_text(encoding="utf-8").strip() == self.token
        except FileNotFoundError:
            return True
        except OSError:
            return True

    def release(self) -> None:
        if self.owned():
            try:
                self.path.unlink()
            except OSError:
                pass

    def watch(self, stop: threading.Event, every: float = 1.0, log=print) -> threading.Thread:
        """Sahiplik başkasına geçince `stop`'u kurar (run_live döngüsü çıkar)."""
        def run() -> None:
            while not stop.wait(every):
                if not self.owned():
                    log("başka bir takip programı devraldı; bu süreç kapanıyor.")
                    stop.set()
                    return

        th = threading.Thread(target=run, daemon=True)
        th.start()
        return th
