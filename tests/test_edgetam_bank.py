from tracker.edgetam_bank import MemoryBank


def _bank(n: int) -> MemoryBank[int]:
    b: MemoryBank[int] = MemoryBank()
    b.reset(0)
    for i in range(1, n + 1):
        b.push(i)
    return b


def test_only_prompted_entry_fills_every_slot():
    b = _bank(0)
    assert b.mem_slots() == [0] * 7 and b.ptr_slots() == [0] * 16


def test_short_history_pads_with_first_entry():
    b = _bank(3)
    assert b.mem_slots() == [0, 3, 2, 1, 0, 0, 0]
    assert b.ptr_slots() == [0, 3, 2, 1] + [0] * 12


def test_long_history_keeps_recent_slots_and_evicts():
    b = _bank(20)
    assert b.mem_slots() == [0, 20, 19, 18, 17, 16, 15]
    assert b.ptr_slots() == [0] + list(range(20, 5, -1))
    assert b.size == 15


def test_reset_starts_over():
    b = _bank(5)
    b.reset(9)
    assert b.mem_slots() == [9] * 7 and b.size == 0
