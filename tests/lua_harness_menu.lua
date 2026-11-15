-- M1: üst menü çubuğu (Ortam: TRACK_LUA, TRACK_MODULES, VIDEO, DATA_LUA). track.lua gerçekten yüklenir.
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
TRACK_SHOW_MENU = true
dofile(os.getenv("TRACK_LUA"))
local TM = require("trackmenu")
local function out(...) print(table.concat({ ... }, "\t")) end
local W, H = 1280, 720
reg.props["osd-dimensions"] = { w = W, h = H, ml = 0, mr = 0, mt = 0, mb = 0 }
reg.obs["osd-dimensions"]("osd-dimensions", reg.props["osd-dimensions"]) -- pencere açıldı: çubuk çizilir
local function move(x, y)
  reg.props["mouse-pos"] = { x = x, y = y, hover = true }
  reg.obs["mouse-pos"]("mouse-pos", { x = x, y = y, hover = true }) end
local function M() return reg.ov[4] or "" end
local function click(x, y)
  move(x, y); reg.cmds = {}
  if reg.bind["track-click-bind"] then reg.bind["track-click-bind"]({ event = "press" }) end
end
local bar, bh, fs = TM.bar_layout(W, H, TM.MENUS)
local function hx(i) return bar[i].x + math.floor(bar[i].w / 2) end

-- Pencereli modda çubuk HEP açık (fare yokken de); tam ekranda fare hareketine kadar gizli
out("WINDOWED", tostring(M():find("Dosya", 1, true) ~= nil), tostring(M():find("Yardım", 1, true) ~= nil))
reg.props.fullscreen = true
reg.obs.fullscreen("fullscreen", true)
out("HIDDEN", tostring(M() == ""), tostring(track_over()))
move(W / 2, H / 2) -- her fare hareketinde çubuk görünür; ortadaki imleç çubuğun üstünde değil
out("CENTER", tostring(M() == ""), tostring(track_over()))
move(hx(1), 5) -- üst kenar: çubuk görünür, tıklama yakalanır
out("SHOWN", tostring(M():find("Dosya", 1, true) ~= nil), tostring(track_over()), tostring(M():find("Video aç", 1, true) == nil))
local names = {}
for _, m in ipairs(TM.MENUS) do names[#names + 1] = tostring(M():find(m.label, 1, true) ~= nil) end
out("TITLES", table.concat(names, ","))

click(hx(3), 5) -- Takip menüsü
out("OPEN", tostring(M():find("Hedefi bırak", 1, true) ~= nil), tostring(reg.bind["trackmenu-ESC"] ~= nil))
-- Takip kapalıyken canlı eylemler gri, "Takip" tıklanabilir
local drop = (function() local m = TM.MENUS[3]; local labels, keys = {}, {}
  for i, it in ipairs(m.items) do labels[i] = it.sep and "" or "    " .. it.label; keys[i] = it.key or "" end
  return (TM.drop_layout(W, bh, fs, m.items, bar[3].x, labels, keys)) end)()
local function item(i) return bar[3].x + 20, drop[i].y + math.floor(drop[i].h / 2) end
click(item(2)) -- Hedefi bırak: gri → komut yok, menü açık kalır? (gri öğe: yalnız kapanmaz)
out("GREY", reg.cmds[1] or "-", tostring(M():find("Hedefi bırak", 1, true) ~= nil))
click(item(1)) -- Takip: eylem + kapanır
out("TOGGLE", reg.cmds[1] or "-", tostring(M():find("Hedefi bırak", 1, true) == nil))
click(hx(3), 5)
out("CHECKED", tostring(M():find("✓", 1, true) ~= nil)) -- Takip açık: işaretli
-- Klavye: Esc kapatır; F10 açar; sağ ok sonraki menü; aşağı + Enter eylem çalıştırır
reg.bind["trackmenu-ESC"]()
out("ESC", tostring(M():find("Hedefi bırak", 1, true) == nil), tostring(reg.bind["trackmenu-ESC"] == nil))
reg.bind.menu()
out("F10", tostring(M():find("Video aç", 1, true) ~= nil), tostring(reg.bind["trackmenu-DOWN"] ~= nil))
reg.bind["trackmenu-RIGHT"]()
out("RIGHT", tostring(M():find("Yakınlaştır", 1, true) ~= nil))
reg.cmds = {}
reg.bind["trackmenu-DOWN"](); reg.bind["trackmenu-UP"](); reg.bind["trackmenu-ENTER"]()
out("ENTER", tostring(M():find("Yakınlaştır", 1, true) == nil), tostring(reg.set["video-zoom"]))
-- Dışarı tık menüyü kapatır; sonra bağ kalkar
reg.bind.menu(); click(W / 2, H / 2)
out("OUTSIDE", tostring(M():find("Video aç", 1, true) == nil))
-- Otomatik gizleme (zamanlayıcı)
move(hx(2), 5); reg.timers[#reg.timers]()
out("AUTOHIDE", tostring(M() == ""), tostring(track_over()))
-- Tam ekrandan çıkınca çubuk geri gelir ve zamanlayıcı gizlemez
reg.props.fullscreen = false
reg.obs.fullscreen("fullscreen", false)
out("EXITFS", tostring(M():find("Dosya", 1, true) ~= nil))
move(W / 2, H / 2); if reg.timers[#reg.timers] then reg.timers[#reg.timers]() end
out("STAYS", tostring(M():find("Dosya", 1, true) ~= nil))
-- B3: Parça menüsü: Sol kol seç → `track-part left_arm`; işaret + Takip menüsündeki etiket güncellenir
click(hx(4), 5)
out("PARTMENU", tostring(M():find("Sol kol", 1, true) ~= nil), tostring(M():find("Eller", 1, true) ~= nil))
do local m = TM.MENUS[4]; local labels, keys = {}, {}
  for i, it in ipairs(m.items) do labels[i] = it.sep and "" or "    " .. it.label; keys[i] = it.key or "" end
  local d = (TM.drop_layout(W, bh, fs, m.items, bar[4].x, labels, keys))
  click(bar[4].x + 20, d[6].y + math.floor(d[6].h / 2)) end -- 6. öğe: Sol kol
out("PARTSEL", reg.cmds[1] or "-", tostring(M():find("Sol kol", 1, true) == nil))
click(hx(4), 5)
out("PARTCHK", tostring(M():find("✓", 1, true) ~= nil))
reg.bind["trackmenu-ESC"]()
click(hx(3), 5)
out("PARTLBL", tostring(M():find("Vücut parçası: Sol kol", 1, true) ~= nil))
reg.bind["trackmenu-ESC"]()
reg.cmds = {}; reg.bind.part() -- G: sonraki parça (sol kol → sağ kol)
out("PARTKEY", reg.cmds[1] or "-")
-- Yardım menüsü: güncelleme öğesi var ve etkin
move(hx(7), 5); click(hx(7), 5)
out("UPDATE", tostring(M():find("Güncellemeleri denetle", 1, true) ~= nil))
reg.bind["trackmenu-ESC"]()
-- K: döndürme 90 → 180 → 270 → 0
local rots = {}
for _ = 1, 4 do reg.bind.rotate(); rots[#rots + 1] = tostring(reg.set["video-rotate"]) end
out("ROTATE", table.concat(rots, ","))
