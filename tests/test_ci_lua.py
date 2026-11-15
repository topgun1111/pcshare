"""CI'da (REQUIRE_LUA=1) Lua yorumlayıcısı yoksa Lua testleri sessizce atlanmasın."""
import os
import shutil

import pytest


@pytest.mark.skipif(os.environ.get("REQUIRE_LUA") != "1", reason="REQUIRE_LUA yok")
def test_lua_available_in_ci():
    assert shutil.which("texlua") or shutil.which("lua") or shutil.which("luajit")
