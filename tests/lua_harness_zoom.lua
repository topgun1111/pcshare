-- Sağ tık hedef seçimi + ekran üstü zoom kontrolü (Ortam: TRACK_LUA, VIDEO, DATA_LUA).
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
dofile(os.getenv("TRACK_LUA"))
local function out(...) print(table.concat({ ... }, "\t")) end
local W, H = 1280, 720
reg.props["osd-dimensions"] = { w = W, h = H, ml = 0, mr = 0, mt = 0, mb = 0 }
local function move(x, y)
  reg.props["mouse-pos"] = { x = x, y = y, hover = true }
  reg.obs["mouse-pos"]("mouse-pos", { x = x, y = y, hover = true })
end
local function over() return track_over() end
local function Z() return reg.ov[2] or "<yok>" end
local function has(s, pat) return s:find(pat, 1, true) ~= nil end
local function zoom_is(z) -- video-zoom değeri (takipte Python yazar) → özellik + gözlemci
  reg.props["video-zoom"] = z
  reg.obs["video-zoom"]("video-zoom", z)
end

out("BEFORE", tostring(reg.ov[2] == nil)) -- fare hareketi yok: kontrol çizilmedi
move(10, H - 10) -- panelin ve zoom düğmelerinin dışı
out("SHOWN", tostring(has(Z(), "x1.0")), tostring(has(Z(), "+")), tostring(over()))

-- zoom düğmelerini dikey tarama ile bul (sağ kenar)
local x = W - 40
local rows, start = {}, nil
for y = 220, H, 2 do -- panel (üst satırlar) dışında
  move(x, y)
  if over() and not start then start = y elseif not over() and start then
    rows[#rows + 1] = math.floor((start + y - 2) / 2); start = nil
  end
end
out("BUTTONS", tostring(#rows))
local plus, minus = { x, rows[1] }, { x, rows[2] }
local function click(b)
  move(b[1], b[2])
  reg.bind["track-click-bind"]()
end

-- takip KAPALI: doğrudan video-zoom (log2), 0.25 adım, alt sınır 0
reg.cmds, reg.set = {}, {}
click(plus)
out("PLUS", tostring(reg.set["video-zoom"]), tostring(reg.cmds[1]), tostring(has(Z(), "x1.2")))
click(minus)
out("MINUS", tostring(reg.set["video-zoom"]), tostring(has(Z(), "x1.0")))
click(minus)
out("FLOOR", tostring(reg.set["video-zoom"]))
for _ = 1, 20 do click(plus) end
out("CEIL", tostring(reg.set["video-zoom"]), tostring(has(Z(), "x16.0")))

-- tuşlar (+ / -) aynı işi yapar
reg.set = {}
zoom_is(1)
reg.bind["zoom-out"]()
out("KEY_OUT", tostring(reg.set["video-zoom"]))
reg.bind["zoom-in"]()
out("KEY_IN", tostring(reg.set["video-zoom"]))

-- zoom yapılmışken panel gizlense de kontrol kalır; zoom 1x'e dönünce kaybolur
zoom_is(1.0)
out("OBS", tostring(has(Z(), "x2.0")))
move(10, H - 10)
reg.timers[#reg.timers]() -- panel otomatik gizlenir
out("STAYS", tostring(has(Z(), "x2.0")), tostring(over()))
zoom_is(0)
out("GONE", tostring(Z() == ""))

-- takip AÇIK ama hedef kilitli değil: yine video-zoom (track-setting yok)
reg.bind.toggle()
reg.cmds, reg.set = {}, {}
move(10, H - 10)
reg.bind["zoom-in"]()
out("ON_FREE", tostring(reg.set["video-zoom"]), tostring(#reg.cmds))
-- hedef kilitli: hedefin doluluğu (Python zoom'u sürer)
reg.msgs["track-hud"]("Takip: ID 3", "1")
reg.cmds, reg.set = {}, {}
reg.bind["zoom-in"](); reg.bind["zoom-out"]()
out("ON_LOCKED", reg.cmds[1], reg.cmds[2], tostring(reg.set["video-zoom"]))
-- hedef bırakılınca (kilit 0) tekrar video-zoom
reg.msgs["track-hud"]("Takip: hedef seç (tıkla)", "0")
reg.cmds, reg.set = {}, {}
reg.bind["zoom-in"]()
out("UNLOCKED", tostring(reg.set["video-zoom"]), tostring(#reg.cmds))
reg.bind.toggle() -- takibi kapat

-- SAĞ TIK: takip kapalıyken açar ve noktayı gönderir
reg.props["video-params"] = { w = 1920, h = 1080 }
reg.props["video-zoom"] = 0 -- bu bölüm zoom'suz: kenar boşluklarına göre eşleme
reg.props["time-pos"] = 12.5
move(640, 360)
reg.cmds = {}
reg.bind.pick()
out("PICK1", reg.cmds[1] or "-", reg.cmds[2] or "-")
-- takip zaten açıkken yalnız tıklama gönderilir
move(320, 180)
reg.cmds = {}
reg.bind.pick()
out("PICK2", reg.cmds[1] or "-", tostring(#reg.cmds))
-- zoom düğmesinin üstünde sağ tık hedef seçmez
move(plus[1], plus[2])
reg.cmds = {}
reg.bind.pick()
out("PICK_BTN", tostring(#reg.cmds))
-- video dışına (siyah kenar) sağ tık: komut yok
reg.props["osd-dimensions"] = { w = W, h = H, ml = 100, mr = 100, mt = 0, mb = 0 }
move(50, 300)
reg.cmds = {}
reg.bind.pick()
out("PICK_OUT", tostring(#reg.cmds))
reg.props["osd-dimensions"] = { w = W, h = H, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.bind.toggle() -- kapat
