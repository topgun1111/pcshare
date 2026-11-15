from tracker.config import Config


def test_config_roundtrip(tmp_path):
    p = tmp_path / "c.json"
    c = Config(target_fill=0.4)
    c.save(p)
    assert Config.load(p).target_fill == 0.4
    assert Config().ipc_path


def test_config_detector_defaults_and_unknown_keys(tmp_path):
    c = Config()
    assert (c.det_conf, c.det_iou, c.det_size, c.device) == (0.25, 0.45, 640, "auto")
    p = tmp_path / "c.json"
    p.write_text('{"det_conf": 0.4, "gelecekte_eklenecek": 1}', encoding="utf-8")
    assert Config.load(p).det_conf == 0.4
