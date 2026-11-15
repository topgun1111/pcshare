"""Tek takip programı kuralı: son başlayan sahiplik alır, eskisi kendiliğinden durur."""
import threading

from tracker.single import SingleInstance


def test_latest_claim_wins_and_old_owner_notices(tmp_path):
    lock = tmp_path / "tracker.lock"
    a, b = SingleInstance(lock), SingleInstance(lock)
    assert a.claim() and a.owned()
    assert b.claim()
    assert b.owned() and not a.owned()
    a.release()  # sahibi olmayan silemez
    assert lock.exists() and b.owned()
    b.release()
    assert not lock.exists()


def test_watch_sets_stop_when_ownership_lost(tmp_path):
    lock = tmp_path / "tracker.lock"
    a, b = SingleInstance(lock), SingleInstance(lock)
    a.claim()
    stop, logs = threading.Event(), []
    a.watch(stop, every=0.01, log=logs.append)
    assert not stop.wait(0.05)  # hâlâ sahibiz
    b.claim()
    assert stop.wait(2.0) and logs


def test_unwritable_lock_disables_guard_instead_of_crashing(tmp_path):
    blocker = tmp_path / "file"
    blocker.write_text("x")
    inst = SingleInstance(blocker / "sub" / "tracker.lock")  # klasör oluşturulamaz
    assert inst.claim() is False
    assert inst.owned() is True  # koruma yok = kapanma yok
