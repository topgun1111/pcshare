-- Beyaz takip noktası (Ortam: TRACK_LUA, VIDEO, DATA_LUA).
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
dofile(os.getenv("TRACK_LUA"))
local function out(...) print(table.concat({ ... }, "\t")) end
local function dot() return reg.ov[3] or "<yok>" end -- nokta katmanı (z=3)

-- pencere 1000x500, video zoom'suz tam kaplıyor
reg.props["osd-dimensions"] = { w = 1000, h = 500, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.msgs["track-dot"]("0.5", "0.5")
out("OFF_WHEN_DISABLED", dot()) -- takip kapalıyken çizilmez
reg.bind.toggle() -- takibi aç
reg.msgs["track-dot"]("0.5", "0.5")
out("CENTER", dot())
-- zoom/pan: video pencerenin dışına taşmış (ml negatif) → nokta ekran koordinatına çevrilir
reg.props["osd-dimensions"] = { w = 1000, h = 500, ml = -500, mr = -500, mt = -250, mb = -250 }
reg.obs["osd-dimensions"]()
out("ZOOMED", dot()) -- 0.5,0.5 → x=-500+0.5*2000=500, y=-250+0.5*1000=250: merkez
reg.msgs["track-dot"]("0.9", "0.5")
out("OUT", dot()) -- x=1300: ekran dışı → gizli
reg.msgs["track-dot"]("off")
out("HIDE", dot())
-- Geçici (sarı) işaret: tıklama anında görünür, `track-dot` gelince beyaza döner
reg.props["osd-dimensions"] = { w = 1000, h = 500, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.obs["osd-dimensions"]()
reg.props["video-params"] = { w = 1920, h = 1080 }
reg.props["mouse-pos"] = { x = 250, y = 100, hover = true }
reg.bind.pick() -- sağ tık (takip açık)
out("PROV", dot())
reg.timers[#reg.timers]() -- 2 sn: yanıt gelmedi → OSD uyarısı
out("WARN", tostring(reg.osd[#reg.osd]:find("yanıt vermiyor", 1, true) ~= nil))
reg.msgs["track-dot"]("0.5", "0.5")
out("CONFIRMED", dot())
-- Gerçek mpv: zoom/pan'da osd-dimensions kenar boşlukları 0'a kıstırılır (video pencereyi
-- kaplıyor görünür). Artı yine de video üzerinde durmalı: zoom 2x (video-zoom=1), pan-x 0.25
-- → video sol=0, üst=-250, 2000x1000; (0.25, 0.5) → ekranda (500, 250).
reg.props["osd-dimensions"] = { w = 1000, h = 500, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.props["video-params"] = { w = 1000, h = 500, dw = 1000, dh = 500 }
reg.props["video-zoom"] = 1
reg.props["video-pan-x"] = 0.25
reg.props["video-pan-y"] = 0
reg.msgs["track-dot"]("0.25", "0.5")
out("CLAMPED", dot())
-- Aynı koşulda sağ tık: pencerede (500,250) → video (0.25, 0.5)
reg.props["mouse-pos"] = { x = 500, y = 250, hover = true }
reg.bind.pick()
out("CLICKCMD", reg.cmds[#reg.cmds])
