"""Reference for the static memory-slot layout of the EdgeTAM graphs (Kotlin port: core/MemoryBank.kt).

`sam-memattn.onnx` takes `mem_feats[K]` and `ptrs[P]` in a fixed slot order (see scripts/export_sam2.py):
slot 0 = the prompted (first) entry, slot d = the entry pushed d steps ago. A slot with no history yet is filled with the
first entry, so the shapes never change. Only entries of tracked frames are pushed (a lost frame adds nothing).
"""
from __future__ import annotations

from collections import deque
from typing import Generic, TypeVar

T = TypeVar("T")


class MemoryBank(Generic[T]):
    def __init__(self, num_mem: int = 7, num_ptr: int = 16) -> None:
        self.num_mem, self.num_ptr = num_mem, num_ptr
        self._cond: T | None = None
        self._hist: deque[T] = deque()

    def reset(self, cond: T) -> None:
        self._cond = cond
        self._hist.clear()

    def push(self, entry: T) -> None:
        self._hist.append(entry)
        while len(self._hist) > max(self.num_mem, self.num_ptr) - 1:
            self._hist.popleft()

    @property
    def size(self) -> int:
        return len(self._hist)

    def slots(self, count: int) -> list[T]:
        if self._cond is None:
            raise RuntimeError("bank not initialised")
        n = len(self._hist)
        return [self._cond if s == 0 or s > n else self._hist[n - s] for s in range(count)]

    def mem_slots(self) -> list[T]:
        return self.slots(self.num_mem)

    def ptr_slots(self) -> list[T]:
        return self.slots(self.num_ptr)
