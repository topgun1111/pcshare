-- Parça 16: ekran üstü panel (Ortam: TRACK_LUA, VIDEO, DATA_LUA). Tüm panel düğmelerini tarayıp tıklar.
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
TRACK_SHOW_PANEL = true
dofile(os.getenv("TRACK_LUA"))
local function out(...) print(table.concat({ ... }, "\t")) end
local W, H = 1280, 720
reg.props["osd-dimensions"] = { w = W, h = H, ml = 0, mr = 0, mt = 0, mb = 0 }
local function move(x, y) reg.obs["mouse-pos"]("mouse-pos", { x = x, y = y, hover = true }) end
local function over() return track_over() end

out("BEFORE", tostring(#reg.hud), tostring(over()))
move(W - 5, H - 5) -- panel dışı: görünür ama hiçbir düğmenin üstünde değil
local P = function() return reg.ov[1] end -- panel katmanı
out("SHOWN", tostring(P() ~= "" and P() ~= nil), tostring(over()))
out("LABELS", tostring(P():find("Takip: KAPALI", 1, true) ~= nil),
  tostring(P():find("Offline: KAPALI", 1, true) ~= nil),
  tostring(P():find("Zoom", 1, true) == nil)) -- zoom düğmeleri artık panelde değil

-- satırlar: soldaki ilk düğmenin x'inde dikey tarama
local rows, inrow = {}, nil
for y = 0, 300, 2 do
  move(12, y)
  if over() and not inrow then inrow = y elseif not over() and inrow then rows[#rows + 1] = { inrow, y - 2 }; inrow = nil end
end
out("ROWS", tostring(#rows))
local btns = {}
for _, r in ipairs(rows) do
  local ym = math.floor((r[1] + r[2]) / 2)
  local start
  for x = 0, W, 2 do
    move(x, ym)
    if over() and not start then start = x elseif not over() and start then btns[#btns + 1] = { math.floor((start + x - 2) / 2), ym }; start = nil end
  end
end
out("BTNS", tostring(#btns))
-- ters sırada tıkla (Takip düğmesi sona kalsın: durum zoom düğmelerini etkilemesin)
for i = #btns, 1, -1 do
  reg.cmds, reg.set = {}, {}
  move(btns[i][1], btns[i][2])
  reg.bind["track-click-bind"]()
  out("BTN", tostring(i), reg.cmds[1] or "-", tostring(reg.set["video-zoom"]))
end
-- Takip artık açık (Takip düğmesi en son tıklandı)
move(btns[11][1], btns[11][2]); reg.bind["track-click-bind"]() -- sabitlemeyi kaldır
move(W - 5, H - 5)
out("ENABLED_BOUND", tostring(over())) -- hedef seçimi için bağ açık
reg.cmds = {}
if reg.bind["track-click-bind"] then reg.bind["track-click-bind"]() end -- panel dışında sol bağ yok
out("TARGET_CLICK", reg.cmds[1] or "-")
reg.timers[#reg.timers]() -- otomatik gizleme
out("HIDDEN", tostring(P() == ""))
reg.bind.toggle() -- takip kapanınca sol tık bağı da kalkar
out("UNBOUND", tostring(not over()))
