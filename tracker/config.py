"""Ayarlar (JSON ile yüklenip kaydedilebilir)."""
from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, fields
from pathlib import Path


def default_ipc_path() -> str:
    return r"\\.\pipe\mpv" if os.name == "nt" else "/tmp/mpv.sock"


@dataclass
class Config:
    ipc_path: str = ""
    target_fill: float = 0.30
    min_zoom: float = 0.0
    max_zoom: float = 2.5
    alpha_pos: float = 0.15
    alpha_zoom: float = 0.08
    dead_zone_pos: float = 0.01
    dead_zone_zoom: float = 0.05
    model_path: str = "models/yolov8n.onnx"
    model_type: str = "auto"  # auto (dosya adında yolox → yolox, yoksa yolov8) | yolov8 | yolox
    yolox_decoded: bool = False  # YOLOX --decode_in_inference ile dışa aktarıldıysa true
    det_conf: float = 0.25
    det_iou: float = 0.45
    det_size: int = 640
    device: str = "auto"  # auto | dml | cuda | cpu
    live_det_conf: float = 0.1  # canlı mod: ByteTrack düşük skor aşaması için (low_thr ile aynı)
    live_fps: float = 10.0  # canlı mod en fazla işleme hızı (screenshot-raw IPC'de ağır)
    pick_tol: float = 0.03  # kutu dışı tıklama toleransı (kare yüksekliği oranı)
    seek_jump: float = 2.0  # sn; time-pos bundan fazla ileri atlarsa / geri giderse atlama sayılır
    lookahead_s: float = 0.15  # sn; hedef merkezi hız yönünde bu kadar ileri alınır (0: kapalı)
    max_lead: float = 0.15  # lookahead'in kare genişliği/yüksekliği oranında üst sınırı
    kf_accel: float = 0.5  # hedef Kalman süreç gürültüsü: ivme std (kare boyutu/sn²)
    kf_meas: float = 0.01  # hedef Kalman ölçüm gürültüsü std (kare boyutu oranı)
    lost_hold_s: float = 1.0  # sn (video zamanı); hedef kaybolunca son görünümde bu kadar bekle
    auto_window_s: float = 5.0  # A2: canlı kalite penceresi, sn (0: otomatik geçiş kapalı)
    auto_fps_ratio: float = 0.6  # A2: canlı hız < live_fps × bu → taranmış ize geç
    auto_lag_ms: float = 300.0  # A2: ortalama adım gecikmesi bunu aşarsa geç
    auto_lost_frac: float = 0.3  # A2: penceredeki kayıp kare oranı bunu aşarsa geç
    scan_throttle_s: float = 0.15  # A3: oynatma açıkken tarama örnek başına uyur (0: kapalı)
    offline_fps: float = 10.0  # offline: saniyede işlenen kare (video bundan hızlıysa atlanır)
    offline_interp_s: float = 0.6  # offline: bu süreden kısa hedef kayıpları doğrusal doldurulur
    offline_alpha: float = 0.4  # offline: sıfır-faz EMA katsayısı (konum; boyut bunun 0.4'ü)
    reid_model: str = "models/osnet_x0_25.onnx"  # OSNet ONNX (girdi 256x128)
    reid_live: str = "auto"  # canlıda Re-ID: auto (model dosyası varsa aç) | on | off
    team_live: bool = False  # canlıda takım rengi elemesi (yalnız Re-ID açıkken; spor için)
    number_live: bool = False  # canlıda forma numarası elemesi (numara modeli de gerekir)
    kp_reid: bool = True  # eklem takibi kayıptan dönünce kişiyi Re-ID görünümüyle de ara
    reid_thres: float = 0.6  # kosinüs benzerliği alt sınırı (hedefi yeni ID'ye bağlamak için)
    reid_margin: float = 0.05  # en iyi aday ikinciden en az bu kadar iyi olmalı
    reid_every_s: float = 0.3  # video sn; galeri besleme/eşleştirme en sık bu aralıkla
    reid_gallery: int = 8  # galerideki en çok görünüm sayısı
    reid_min_h: float = 0.08  # embedding için kutu yüksekliği alt sınırı (kare yüksekliği oranı)
    reid_max_iou: float = 0.2  # başka kutuyla bu IoU'dan fazla örtüşen kırpıntı kullanılmaz
    reid_min_age: int = 3  # aday iz en az bu kadar kare yaşamış olmalı (canlı)
    reid_min_samples: int = 3  # offline: zincire girecek izin en az örnek sayısı
    reid_link_gap_s: float = 20.0  # offline: iki iz arası en fazla boşluk (sn)
    team_min_samples: int = 40  # takım kümeleme alt sınırı (canlı: özellik, offline: iz başına)
    team_refit: int = 100  # canlı: bu kadar yeni özellikte bir yeniden kümele
    team_margin: float = 0.15  # merkezlere uzaklık farkı oranı bundan azsa etiket belirsiz
    team_min_px: int = 100  # üst gövdede çim dışı geçerli piksel alt sınırı
    number_model: str = "models/jersey_number.onnx"  # isteğe bağlı numara sınıflandırıcı
    number_conf: float = 0.6  # tek okuma için softmax güveni alt sınırı
    number_min_votes: int = 3  # hedef numarası için en az bu kadar aynı okuma
    number_share: float = 0.6  # ... ve oy ağırlığının en az bu payı
    hud: bool = True  # canlı mod: köşede küçük durum etiketi (Takip: ID 5 / hedef kayıp)
    lost_zoomout_s: float = 2.0  # sn; bekleme sonrası 1x'e kademeli zoom-out süresi (0: anında)
    point_box: float = 0.06  # sağ tık nesne takibi: başlangıç kutusu (kare yüksekliği oranı)
    point_refine: bool = True  # tıklanan rengin bitişik bölgesinden kutuyu tahmin et
    point_min_score: float = 0.45  # şablon benzerliği (NCC) alt sınırı; altı = nesne kayıp
    pose_model: str = "models/yolov8s-pose.onnx"  # eklem takibi (isteğe bağlı)
    pose_conf: float = 0.3  # kişi (poz) tespit eşiği
    single_pose: bool = False  # G2: poz modeli kişi dedektörü de olsun (tek çıkarım)
    wholebody: str = "auto"  # O2: tüm-vücut poz (el/yüz/parmak): auto (dosya varsa) | on | off
    wholebody_model: str = "models/rtmpose-wholebody.onnx"  # RTMPose-wholebody ONNX (133 nokta)
    wb_tol: float = 0.05  # tüm-vücut küçük noktaları (parmak, yüz) tıklama toleransı (boy oranı)
    wb_min_conf: float = 0.3  # RTMPose nokta güveni alt sınırı (SimCC ölçeği YOLO'dan farklıdır)
    kp_tol: float = 0.12  # sağ tık eklemin bu kadar (boy oranı) yakınındaysa
    kp_min_conf: float = 0.35  # eklem güveni alt sınırı (altı = eklem kayıp)
    kp_box: float = 0.10  # eklem etrafındaki takip kutusu (kişi boyu oranı)
    update_repo: str = ""  # "sahip/depo": Yardım → güncelleme denetimi (boşsa update.json)
    dot_lead_s: float = 0.08  # sn; artı hız yönünde bu kadar ileri çizilir (0=kapalı)
    kp_hold: int = 6  # eklem/kişi görünmeyince bu kadar adım son konumda tut (0: hemen kayıp)
    kp_hold_s: float = 1.5  # sn; kayıpken öngörülen konumu bu kadar döndür (0: B2 kapalı)
    kp_world: bool = True  # eklem takibi: kamera hareketi telafisi (dünya konumu)
    kp_filter: bool = True  # eklem konumuna One-Euro süzgeci
    kp_infer: bool = True  # Android 10b: eksik bilek/ayak bileği üst eklemlerden kestirilir (parça kutusu)
    kp_reacq_single: bool = True  # uzun kayıptan sonra karede tek uygun kişi varsa yeniden bağla
    set_min_zoom: float = 0.35  # T1: küme kadrajı en az bu (log2) zoom; sığmayan uzaklar çıkar
    set_hyst: float = 0.25  # T1: çıkan hedef, eşiğin bu (log2) üstünde sığınca döner
    cut_detect: bool = True  # G4: çekim kesmesi algıla (canlı + offline); kesmede izler bırakılır
    cut_hist: float = 0.35  # G4: 2x2 ızgara renk histogramı uzaklığı eşiği (0..1)
    cut_diff: float = 0.18  # G4: küçük gri önizleme ortalama mutlak fark eşiği (0..1)
    gmc: bool = True  # ByteTrack tahmini kamera hareketiyle telafi edilir (canlı + offline)
    gmc_side: int = 480  # kamera kestirimi için kare en uzun kenarı (px)
    point_max_side: int = 640  # nesne izleyici kareyi en uzun kenarı bu olacak şekilde küçültür

    def __post_init__(self) -> None:
        if not self.ipc_path:
            self.ipc_path = default_ipc_path()

    @classmethod
    def load(cls, path: str | Path) -> Config:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        known = {f.name for f in fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known})

    def save(self, path: str | Path) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")
