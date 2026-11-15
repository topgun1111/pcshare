-- U2: mpcbar.lua alt çubuğu (Ortam: BAR_LUA). 1280x700 → u = 1: seek y640-660, düğmeler y664-696.
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
local mp = require("mp")
mp.get_property_bool = function(n, d) local v = reg.props[n]; if v == nil then return d end return v end
mp.set_property_bool = function(n, v) reg.props[n] = v; reg.set[n] = v end
mp.command = function(c) reg.cmds[#reg.cmds + 1] = c end
local mk = mp.create_osd_overlay
mp.create_osd_overlay = function(...)
  local o = mk(...)
  function o:remove() reg.removed = (reg.removed or 0) + 1; reg.ov[self.z or 0] = "" end
  return o
end
_G.mp = mp
reg.props["osd-width"], reg.props["osd-height"] = 1280, 700
reg.props.duration, reg.props["time-pos"], reg.props["volume-max"] = 100, 0, 130
reg.props.volume, reg.props.speed = 100, 1
dofile(os.getenv("BAR_LUA"))

local function out(...) print(table.concat({ ... }, "\t")) end
local function move(x, y) reg.obs["mouse-pos"](nil, { hover = true, x = x, y = y }) end
local function bound() return reg.bind["mpcbar-MBTN_LEFT"] ~= nil and "1" or "0" end
local function click(x, y)
  move(x, y); reg.cmds = {}
  if reg.bind["mpcbar-MBTN_LEFT"] then reg.bind["mpcbar-MBTN_LEFT"]({ event = "down" }) end
end

out("INIT", bound())
move(640, 300); out("VIDEO", bound())                       -- video üstü: sol tık serbest
move(20, 680); out("ONPLAY", bound(), reg.bind["mpcbar-MBTN_LEFT_DBL"] and "1" or "0")
move(640, 300); out("LEAVE", bound())
click(20, 680); out("PLAY", reg.cmds[1])
click(60, 680); out("STOP", reg.cmds[1] or "-", tostring(reg.set.pause))

-- seek: oran, ipucu, sürükleme, sınır
click(640, 650); out("SEEK", reg.cmds[1])
out("TIP", (reg.ov[0] or ""):find("00:00:50", 1, true) and "1" or "0")
move(5000, 650); out("DRAG_OUT", reg.cmds[#reg.cmds], bound())   -- çubuk dışına sürüklerken bağ kalır
reg.bind["mpcbar-MBTN_LEFT"]({ event = "up" }); out("UP", bound())
move(640, 300); out("AFTER", bound())

-- U3: bölüm adı ipucu + A-B döngü işaretleri
reg.props["chapter-list"] = { { time = 0, title = "Giriş" }, { time = 40, title = "Çözüm" }, { time = 80, title = "Son" } }
move(640, 650)
out("CHAP", (reg.ov[0] or ""):find("00:00:50  Çözüm", 1, true) and "1" or "0")
move(20, 650); out("CHAP0", (reg.ov[0] or ""):find("Giriş", 1, true) and "1" or "0")
reg.props["chapter-list"] = nil; move(640, 650)
out("NOCHAP", (reg.ov[0] or ""):find("Çözüm", 1, true) and "1" or "0")
move(640, 300); local base = #(reg.ov[0] or "")
reg.props["ab-loop-a"] = 25
reg.obs["ab-loop-a"](nil, 25); local one = #(reg.ov[0] or "")
reg.props["ab-loop-b"] = 75
reg.obs["ab-loop-b"](nil, 75); local two = #(reg.ov[0] or "")
out("AB", tostring(one > base), tostring(two > one))
reg.props["ab-loop-a"], reg.props["ab-loop-b"] = nil, nil
reg.obs["ab-loop-a"](nil, nil); out("AB_OFF", tostring(#(reg.ov[0] or "") == base))

-- ses sürgüsü (x 1146..1230, vmax 130): uçlar, orta, dışarı sürükleme, mute açılır
reg.props.mute = true
click(1188, 680); out("VOL_MID", tostring(reg.set.volume), tostring(reg.props.mute))
move(1000, 680); out("VOL_LOW", tostring(reg.set.volume))
move(5000, 680); out("VOL_HIGH", tostring(reg.set.volume))
reg.bind["mpcbar-MBTN_LEFT"]({ event = "up" }); move(640, 300)

-- hız düğmeleri
click(330, 680); out("SPD_UP", tostring(reg.set.speed), reg.osd[#reg.osd] or "-")
click(250, 680); out("SPD_DN", tostring(reg.set.speed))
-- U5: hız düğmesi şerit açar (0.5/1/1.5/2); şerit y≈618; 1x merkez x≈342, 2x ≈448
reg.props.speed = 1.3
click(290, 680); out("STRIP_OPEN", tostring(reg.props.speed), reg.ov[0]:find("1.5x", 1, true) and "1" or "0")
move(342, 618); out("STRIP_HOVER", bound())
click(342, 618); out("SPD_RESET", tostring(reg.set.speed), reg.osd[#reg.osd])
click(290, 680); click(448, 618); out("SPD_2X", tostring(reg.set.speed))
click(290, 680); move(640, 300); out("STRIP_CLOSE", reg.ov[0]:find("1.5x", 1, true) and "1" or "0")
click(290, 680); click(60, 680); out("STRIP_OTHER", reg.ov[0]:find("1.5x", 1, true) and "1" or "0")
move(640, 300)

-- U5: ses yüzdesi ipucu + tekerlek (yalnız çubuk üstünde bağlı)
reg.props.mute = false; reg.props.volume = 80
move(1188, 680); out("VOLTIP", reg.ov[0]:find("80%", 1, true) and "1" or "0")
out("WHEEL_BOUND", reg.bind["mpcbar-WHEEL_UP"] and "1" or "0")
reg.bind["mpcbar-WHEEL_UP"](); out("WHEEL_UP", tostring(reg.set.volume), reg.osd[#reg.osd])
for _ = 1, 40 do reg.bind["mpcbar-WHEEL_DOWN"]() end; out("WHEEL_MIN", tostring(reg.set.volume))
for _ = 1, 40 do reg.bind["mpcbar-WHEEL_UP"]() end; out("WHEEL_MAX", tostring(reg.set.volume))
move(640, 300); out("WHEEL_FREE", reg.bind["mpcbar-WHEEL_UP"] and "1" or "0")

-- tam ekran: fare çubuktayken gizlenmez; ayrılınca zamanlayıcıyla gizlenir, bağ kalkar
reg.props.fullscreen = true
reg.obs.fullscreen(nil, true)
move(20, 680); reg.removed = 0
reg.timers[#reg.timers](); out("FS_HOVER", tostring(reg.removed), bound())
move(640, 300); reg.timers[#reg.timers](); out("FS_HIDE", tostring(reg.removed > 0), bound())
reg.props.fullscreen = false
reg.obs.fullscreen(nil, false); reg.removed = 0
move(640, 300); reg.timers[#reg.timers](); out("WIN_STAYS", tostring(reg.removed))
