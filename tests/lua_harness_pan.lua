-- Sol tuşla kaydırma (pan): Ortam: TRACK_LUA, VIDEO, DATA_LUA.
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
dofile(os.getenv("TRACK_LUA"))
local function out(...) print(table.concat({ ... }, "\t")) end
local W, H = 1280, 720
reg.props["osd-dimensions"] = { w = W, h = H, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.props["video-params"] = { w = 1280, h = 720, dw = 1280, dh = 720 }
local function move(x, y)
  reg.props["mouse-pos"] = { x = x, y = y, hover = true }
  reg.obs["mouse-pos"]("mouse-pos", { x = x, y = y, hover = true })
end
local function r(v) return string.format("%.4f", v or 0) end

-- zoom 1x: kaydırma yok
move(400, 300)
reg.bind["pan"]({ event = "down" })
move(300, 300)
reg.bind["pan"]({ event = "up" })
out("NOZOOM", tostring(reg.set["video-pan-x"]))

-- zoom x2 (log2 = 1): 100 px sola sürükle → pan-x = -100/2560
reg.props["video-zoom"] = 1
move(400, 300)
reg.bind["pan"]({ event = "down" })
move(300, 340)
out("DRAG", r(reg.set["video-pan-x"]), r(reg.set["video-pan-y"]))
move(200, 340)
out("DRAG2", r(reg.set["video-pan-x"]))
reg.bind["pan"]({ event = "up" })
move(100, 340) -- bırakıldı: artık kaymaz
out("RELEASED", r(reg.set["video-pan-x"]))

-- sınır: x2'de en fazla ±0.25 (pencere boş kalmasın)
reg.bind["pan"]({ event = "down" })
move(1200, 340)
move(0, 340)
move(1200, 340)
reg.bind["pan"]({ event = "up" })
out("CLAMP", r(reg.set["video-pan-x"]))

-- düğme üstündeyken başlamaz
reg.set = {}
reg.props["video-pan-x"] = 0
local by
for y = 220, H, 2 do move(W - 40, y); if track_over() then by = y; break end end
move(W - 40, by)
local was_over = track_over()
reg.bind["pan"]({ event = "down" })
move(W - 140, by)
reg.bind["pan"]({ event = "up" })
out("OVERBTN", tostring(was_over), tostring(reg.set["video-pan-x"]))

-- hedefe kilitliyken (Python pan'ı sürer) kaydırma yok, OSD ipucu verir
reg.bind["toggle"]() -- takip AÇIK
reg.msgs["track-hud"]("x", "1", "")
reg.props["video-pan-x"] = 0
reg.set, reg.osd = {}, {}
move(500, 300)
reg.bind["pan"]({ event = "down" })
move(400, 300)
reg.bind["pan"]({ event = "up" })
out("LOCKED", tostring(reg.set["video-pan-x"]), tostring(#reg.osd > 0))
