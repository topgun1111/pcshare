-- Takipli Oynat: tıklama ile hedef seçimi (Parça 2), hedef bırakma (Parça 6).
-- SAĞ TIK (track/pick): tıklanan noktadaki HERHANGİ bir nesneyi (oyuncu olmak zorunda değil) takip
-- eder; takip kapalıysa kendiliğinden açar. Sol tık (takip açıkken) oyuncu kimliğiyle seçer.
-- Ekran üstü ZOOM kontrolü (sağ kenar): + / - düğmeleri ve anlık zoom değeri; + / - tuşları da aynı işi yapar.
-- Tıklama -> video-normalize koordinat -> `script-message track-point` (sağ tık, nesne) ya da
-- `track-click` (sol tık, oyuncu) (Python IPC olarak alır).
-- Offline oynatma (Parça 10): `o` ile <video>.views.json'daki anahtar karelerden zoom/pan uygular
-- (Python gerekmez). Lua 5.1/LuaJIT uyumlu yazıldı (// ve goto yok).
local mp = require 'mp'
local utils = require 'mp.utils'

local enabled = false
local locked = false -- Python: bir hedef kilitli mi (track-hud'un 2. argümanı); zoom +/- ne yapacağını buna göre seçer
-- Ekran üstü zoom kontrolü durumu (çizim/yerleşim aşağıda). z = video-zoom (log2).
local zoomc = { z = 0, hover = nil, rects = {}, ov = nil, shown = "" }
local render_zoom -- ileri bildirim
local set_offline -- ileri bildirim: canlı mod açılınca offline kapatılır
local off -- ileri bildirim (offline durumu; HUD okur)
local sync_click_binding -- ileri bildirim: sol tık bağı (ekran üstü panel + hedef seçimi)
local refresh_panel -- ileri bildirim: ekran üstü panel yeniden çizimi
local scan_start -- ileri bildirim (A1): hedefle aç + arka plan taraması (Dosya menüsü bloğunda atanır)
-- Üst menü çubuğu (M1): motor script-modules/trackmenu.lua (saf mantık); çizim/bağlama aşağıda.
-- TRACK_SHOW_MENU=false ile kapanır (testler); modül yoksa menü yok, geri kalan çalışır.
local menu, menu_hot, render_menu = nil, false, nil
-- Zoom çapası: son seçilen dikdörtgenin/noktanın merkezi (video-normalize). Takip kilitli değilken
-- (+/- zoom) görüntü video merkezi yerine bu noktaya yakınlaşır.
local anchor = nil
local show_provisional -- ileri bildirim: tıklanır tıklanmaz geçici işaret (aşağıda)
local dot = { nx = nil, ny = nil, ov = nil, prov = false } -- Python'un yolladığı canlı takip noktası (aşağıda çizilir)

-- Sol üst durum/bilgi bloğu (Parça 13, genişletildi): Python `script-message-to track track-hud
-- <durum> <kilitli> [<bilgi>]` gönderir. <bilgi> çok satırlıdır (model, cihaz, kare hızı, hedef...);
-- Lua kendi bildiği zoom/döndürme satırını ekler. Küçük harfle çizilir. `track/hud` (Ctrl+H) gizler/gösterir.
-- Offline açıkken "Takip: offline ID n" gösterilir.
local hud = { text = "", info = "", visible = true, ov = nil, shown = nil }
local HUD_FS = 14 -- yazı boyutu (overlay varsayılan 720 yükseklikli koordinat: pencereyle ölçeklenir)

local function hud_clean(line) return (line:gsub("[{}\\\r]", " ")) end -- ASS kaçışlarını etkisiz kıl

local function render_hud()
  if not hud.ov then hud.ov = mp.create_osd_overlay("ass-events") end
  local head = hud.text
  if off and off.on then
    head = "Takip: offline ID " .. tostring(off.target and off.target.id or "?")
  end
  local lines = {}
  if head ~= "" then lines[#lines + 1] = (head:gsub("\n", " ")) end
  if hud.info ~= "" then
    for l in (hud.info .. "\n"):gmatch("(.-)\n") do
      if l ~= "" then lines[#lines + 1] = l end
    end
    local z = mp.get_property_number("video-zoom", 0) or 0
    local zl = string.format("Zoom: x%.2f", 2 ^ z)
    local r = mp.get_property_number("video-rotate", 0) or 0
    if r ~= 0 then zl = zl .. string.format("  Döndürme: %d°", r) end
    lines[#lines + 1] = zl
  end
  local data = ""
  if hud.visible and #lines > 0 then
    for i = 1, #lines do lines[i] = hud_clean(lines[i]) end
    data = string.format("{\\an7\\pos(10,6)\\fs%d\\bord1.5\\shad0\\1c&HFFFFFF&\\3c&H000000&}%s",
      HUD_FS, table.concat(lines, "\\N"))
  end
  if data == hud.shown then return end -- değişmediyse yeniden çizme (zoom sürekli güncellenir)
  hud.shown = data
  hud.ov.data = data
  hud.ov:update()
end

mp.register_script_message("track-hud", function(text, lock, info)
  hud.text = text or ""
  hud.info = info or ""
  locked = (lock == "1")
  render_hud()
  render_zoom() -- + / - davranışı değişmiş olabilir; değer etiketi aynı kalır
end)

-- Pencere (OSD) boyutu: döndürülmüş videoda (90°/270°) `osd-dimensions` w/h bazı mpv sürümlerinde
-- video eksenine göre yer değiştirir → ekran üstü düğmeler yamulur, fare isabeti kayar. Pencere
-- boyutu için bağımsız `osd-width/osd-height` kullanılır; yoksa/geçersizse osd-dimensions'a düşer.
-- Kenar boşlukları (ml,mr,mt,mb) olduğu gibi kalır.
local function osd_dims()
  local d = mp.get_property_native("osd-dimensions")
  if not d then return d end
  local w = mp.get_property_number("osd-width", 0) or 0
  local h = mp.get_property_number("osd-height", 0) or 0
  if w > 0 and h > 0 and (w ~= d.w or h ~= d.h) then
    local c = {}
    for k, v in pairs(d) do c[k] = v end
    c.w, c.h = w, h
    return c
  end
  return d
end

local function osd(msg, sec) mp.osd_message("Takipli Oynat: " .. msg, sec or 2) end

-- Videonun pencere içindeki dikdörtgeni (piksel): sol, üst, genişlik, yükseklik.
-- `osd-dimensions` kenar boşlukları (ml,mr,mt,mb) zoom/pan'da güvenilmez: mpv bunları 0'a
-- kıstırıyor, yani yakınlaşınca "video pencereyi tam kaplıyor" gibi görünür → nokta/artı videoya
-- değil pencereye yapışır, tıklanan nokta da yanlış hesaplanır. Bu yüzden zoom/pan varken dikdörtgen
-- doğrudan video-zoom/video-pan/video-align ile hesaplanır. Döndürme, panscan ya da keepaspect=no
-- varsa (hesap farklı) eski davranış: kenar boşlukları.
local function video_box(d)
  local vw, vh = d.w - d.ml - d.mr, d.h - d.mt - d.mb
  local zoom = mp.get_property_number("video-zoom", 0) or 0
  local px = mp.get_property_number("video-pan-x", 0) or 0
  local py = mp.get_property_number("video-pan-y", 0) or 0
  if zoom == 0 and px == 0 and py == 0 then return d.ml, d.mt, vw, vh end
  local vp = mp.get_property_native("video-params")
  local dw = vp and (vp.dw or vp.w)
  local dh = vp and (vp.dh or vp.h)
  if not (dw and dh and dw > 0 and dh > 0 and d.w > 0 and d.h > 0) then return d.ml, d.mt, vw, vh end
  if (mp.get_property_number("video-rotate", 0) or 0) % 360 ~= 0 then return d.ml, d.mt, vw, vh end
  if (mp.get_property_number("panscan", 0) or 0) ~= 0 then return d.ml, d.mt, vw, vh end
  if mp.get_property("keepaspect") == "no" then return d.ml, d.mt, vw, vh end
  local s = math.min(d.w / dw, d.h / dh) * 2 ^ zoom
  local W, H = dw * s, dh * s
  local ax = mp.get_property_number("video-align-x", 0) or 0
  local ay = mp.get_property_number("video-align-y", 0) or 0
  return (d.w - W) * (1 + ax) / 2 + px * W, (d.h - H) * (1 + ay) / 2 + py * H, W, H
end

-- Pencere pikselini video-normalize (0..1) koordinata çevirir; video dışındaysa nil.
-- tracker/geometry.py::screen_to_video ile aynı formül (kenar boşlukları video_box'tan gelir).
-- d: osd-dimensions (w,h pencere boyutu).
local function screen_to_video(mx, my, d)
  local x0, y0, vw, vh = video_box(d)
  if vw <= 0 or vh <= 0 then return nil end
  local nx = (mx - x0) / vw
  local ny = (my - y0) / vh
  if nx < 0 or nx > 1 or ny < 0 or ny > 1 then return nil end
  return nx, ny
end

-- Fare konumu: `mouse-pos` özelliği (mpv >= 0.30); yoksa eski mp.get_mouse_pos.
local function mouse_xy()
  local p = mp.get_property_native("mouse-pos")
  if p and p.x then return p end
  if mp.get_mouse_pos then return mp.get_mouse_pos() end
  return nil
end

-- msg: "track-point" (sağ tık: herhangi bir nesne) | "track-click" (sol tık: oyuncu kimliği)
local function on_click(msg)
  local d = osd_dims()
  local params = mp.get_property_native("video-params")
  local pos = mouse_xy()
  if not (d and params and params.w and params.h and pos) then
    osd("video henüz hazır değil")
    return
  end
  local nx, ny = screen_to_video(pos.x, pos.y, d)
  if not nx then
    osd("tıklama video dışında", 1)
    return
  end
  local t = mp.get_property_number("time-pos", 0)
  anchor = { nx, ny }
  show_provisional(nx, ny)
  if msg == "track-point" and scan_start then scan_start("point", t, nx, ny) end
  mp.commandv("script-message", msg or "track-click",
    string.format("%.6f", nx), string.format("%.6f", ny),
    string.format("%d", math.floor(params.w)), string.format("%d", math.floor(params.h)),
    string.format("%.3f", t))
  osd(string.format("hedef (%d, %d) px", math.floor(nx * params.w), math.floor(ny * params.h)), 1) -- Lua 5.3+: %d tamsayı ister
end

local function set_enabled(on)
  if on == enabled then return end
  enabled = on
  if enabled then
    set_offline(false) -- canlı ve offline aynı anda görünümü sürmesin
  else
    hud.text, locked = "", false
    render_hud()
  end
  -- Takip açıkken sol tık da hedef seçer (sağ tık her zaman seçer)
  sync_click_binding()
  mp.commandv("script-message", "track-toggle", enabled and "on" or "off")
  osd(enabled and "AÇIK - nesneye sağ tıkla" or "KAPALI")
  if refresh_panel then refresh_panel() end
end

mp.add_key_binding(nil, "toggle", function() set_enabled(not enabled) end)

local function do_reset()
  set_offline(false)
  mp.set_property_number("video-zoom", 0)
  mp.set_property_number("video-pan-x", 0)
  mp.set_property_number("video-pan-y", 0)
  mp.commandv("script-message", "track-reset")
  zoomc.z, locked, anchor = 0, false, nil
  render_zoom()
  osd("sıfırlandı")
end
mp.add_key_binding(nil, "reset", do_reset)

-- Hedefi bırak (takip açık kalır; yeni tıklama yeni hedef seçer; hedefe tekrar tıklamak da bırakır).
local function do_release() anchor = nil; mp.commandv("script-message", "track-release") end
mp.add_key_binding(nil, "release", do_release)

-- ---------------------------------------------------------------------------------------------
-- Offline oynatma
off = { on = false, n = 0, T = {}, Z = {}, PX = {}, PY = {}, last = nil, target = nil }

local function read_file(path)
  local f = io.open(path, "rb")
  if not f then return nil end
  local s = f:read("*a")
  f:close()
  return s
end

local function video_path()
  local p = mp.get_property("path")
  if not p or p:match("^%a[%w+.-]*://") then return nil end -- URL/akış: yan dosya yok
  return p
end

-- Anahtar kareler [[t, zoom, pan_x, pan_y], ...] → paralel diziler. Geçersizse nil, sebep.
local function load_views(vpath)
  local reason = "bulunamadı: " .. vpath .. ".views.json (offline ... --target-id)"
  for _, suffix in ipairs({ ".views.json", ".track.json" }) do
    local text = read_file(vpath .. suffix)
    if text then
      local doc, err = utils.parse_json(text)
      if not doc then
        reason = suffix .. " okunamadı: " .. tostring(err)
      elseif doc.version ~= 1 and doc.version ~= 2 then
        reason = suffix .. ": desteklenmeyen sürüm " .. tostring(doc.version)
      elseif type(doc.views) ~= "table" or #doc.views == 0 then
        reason = suffix .. ": hedef seçilmemiş (offline-view --target-id N)"
      else
        local o = { n = #doc.views, T = {}, Z = {}, PX = {}, PY = {}, target = doc.target }
        for i, k in ipairs(doc.views) do
          o.T[i], o.Z[i], o.PX[i], o.PY[i] = k[1], k[2], k[3], k[4]
        end
        return o
      end
    end
  end
  return nil, reason
end

-- tracker/offline.py::view_at ile aynı: anahtar kareler arası doğrusal, uçlarda sabit.
local function view_at(o, t)
  local n, T = o.n, o.T
  if t <= T[1] then return o.Z[1], o.PX[1], o.PY[1] end
  if t >= T[n] then return o.Z[n], o.PX[n], o.PY[n] end
  local lo, hi = 1, n
  while hi - lo > 1 do
    local mid = math.floor((lo + hi) / 2)
    if T[mid] <= t then lo = mid else hi = mid end
  end
  local f = 0
  if T[hi] > T[lo] then f = (t - T[lo]) / (T[hi] - T[lo]) end
  return o.Z[lo] + (o.Z[hi] - o.Z[lo]) * f,
    o.PX[lo] + (o.PX[hi] - o.PX[lo]) * f,
    o.PY[lo] + (o.PY[hi] - o.PY[lo]) * f
end

-- A2: canlıdan taranmışa geçişte mevcut görünümden hedefe yumuşak geçiş (off.ease)
local function eased(z, px, py)
  local e = off.ease
  if not e then return z, px, py end
  local f = (mp.get_time() - e.t0) / e.dur
  if f >= 1 then off.ease = nil; return z, px, py end
  f = f * f * (3 - 2 * f)
  return e.z + (z - e.z) * f, e.px + (px - e.px) * f, e.py + (py - e.py) * f
end

local function apply_view(z, px, py)
  local l = off.last
  if l and math.abs(l[1] - z) < 1e-4 and math.abs(l[2] - px) < 1e-4
      and math.abs(l[3] - py) < 1e-4 then
    return
  end
  off.last = { z, px, py }
  mp.set_property_number("video-zoom", z)
  mp.set_property_number("video-pan-x", px)
  mp.set_property_number("video-pan-y", py)
end

set_offline = function(on, ease, reason)
  if on == off.on then return end
  if on then
    local vpath = video_path()
    if not vpath then osd("offline: yerel video dosyası yok"); return end
    local data, why = load_views(vpath)
    if not data then osd("offline " .. why, 4); return end
    local was_live = enabled
    if was_live then set_enabled(false) end -- canlı modu kapat
    off.n, off.T, off.Z, off.PX, off.PY = data.n, data.T, data.Z, data.PX, data.PY
    off.target, off.last, off.on = data.target, nil, true
    if ease then
      off.ease = { z = mp.get_property_number("video-zoom", 0), px = mp.get_property_number("video-pan-x", 0),
        py = mp.get_property_number("video-pan-y", 0), t0 = mp.get_time(), dur = 0.6 }
    end
    local function reapply()
      local t = mp.get_property_number("time-pos")
      if off.on and t then off.last = nil; apply_view(eased(view_at(off, t))) end
    end
    reapply()
    if ease then mp.add_timeout(0.7, reapply) end -- duraklatılmışsa geçiş yarım kalmasın
    -- Canlı mod kapanırken Python'un gönderdiği 1x sıfırlaması gecikmeli gelebilir: yeniden uygula
    if was_live then mp.add_timeout(0.5, reapply) end
    render_hud()
    if refresh_panel then refresh_panel() end
    if ease then
      osd("taranmış iz kullanılıyor (" .. tostring(reason or "canlı zayıf") .. ")", 4)
    else
      osd(string.format("offline AÇIK (hedef ID %s, %d anahtar kare)",
        tostring(off.target and off.target.id or "?"), off.n))
    end
  else
    off.on, off.last, off.ease = false, nil, nil
    render_hud()
    if refresh_panel then refresh_panel() end
    mp.set_property_number("video-zoom", 0)
    mp.set_property_number("video-pan-x", 0)
    mp.set_property_number("video-pan-y", 0)
    osd("offline KAPALI")
  end
end

-- time-pos oynatmada ve duraklatılmışken atlamada da gelir
mp.observe_property("time-pos", "number", function(_, t)
  if off.on and t then apply_view(eased(view_at(off, t))) end
end)

mp.add_key_binding(nil, "offline", function() set_offline(not off.on) end)

-- A2: görünüm kaynağı. auto = canlı zayıflarsa Python `track-use-scanned` yollar; live/scanned elle.
local source = "auto"
local SOURCE_LABEL = { auto = "otomatik", live = "canlı", scanned = "taranmış iz" }
local function set_source(m)
  source = m
  mp.commandv("script-message", "track-source", m)
  if m == "scanned" then set_offline(true)
  elseif m == "live" and off.on then set_offline(false) end
  if m ~= "scanned" or not off.on then osd("kaynak: " .. SOURCE_LABEL[m], 2) end
end
-- B3: vücut parçası. Sıra tracker/parts.py CYCLE ile aynı; Python `track-part <ad|next|prev>` alır.
local PARTS = { "auto", "person", "head", "torso", "left_arm", "right_arm", "left_leg",
  "right_leg", "legs", "left_foot", "right_foot", "feet", "hands" }
local PART_LABEL = { auto = "Serbest", person = "Tüm kişi", head = "Baş", torso = "Gövde",
  left_arm = "Sol kol", right_arm = "Sağ kol", left_leg = "Sol bacak", right_leg = "Sağ bacak",
  legs = "Bacaklar", left_foot = "Sol ayak", right_foot = "Sağ ayak", feet = "Ayaklar",
  hands = "Eller" }
local part = "auto"
local function set_part(p)
  part = p
  if not enabled then set_enabled(true) end -- parça seçmek takibi açar (sağ tık gibi)
  mp.commandv("script-message", "track-part", p)
end
local function next_part()
  local i = 1
  for k, v in ipairs(PARTS) do if v == part then i = k end end
  set_part(PARTS[i % #PARTS + 1])
end
mp.add_key_binding(nil, "part", next_part)

-- T1: hedef ekleme kipi. Açıkken sağ tık mevcut hedefe EKLER (birleşik kadraj); üyeye sağ tık çıkarır.
local add_mode = false
local function toggle_add()
  add_mode = not add_mode
  if add_mode and not enabled then set_enabled(true) end
  mp.commandv("script-message", "track-addmode", add_mode and "on" or "off")
end
mp.add_key_binding(nil, "add", toggle_add)

mp.register_script_message("track-use-scanned", function(reason)
  if source ~= "auto" or off.on or not video_path() then return end
  set_offline(true, true, reason)
end)

mp.register_event("end-file", function()
  if off.on then -- veri eski videoya aitti
    off.on, off.last = false, nil
    render_hud()
  end
end)

-- Son döndürme GENEL olarak saklanır (videoya özel değil): her videoda ve her açılışta uygulanır.
-- Dosya: %APPDATA%\\TakipliOynat\\rotate.txt (her zaman yazılabilir; Program Files'ta portable_config
-- yazılamayabilir). Yedek/eski konum: portable_config/rotate.txt. Döndürme nasıl değişirse değişsin
-- (K, menü, görünüm yükle) `video-rotate` izlenip kaydedilir.
local saved_rotate_apply, save_rotate
local rotate_ready, last_rot = false, nil
local function rotate_paths()
  local list = {}
  local ad = os.getenv("APPDATA")
  if ad and ad ~= "" then
    list[#list + 1] = { dir = ad .. "\\TakipliOynat", file = ad .. "\\TakipliOynat\\rotate.txt" }
  end
  local p = mp.command_native and mp.command_native({ "expand-path", "~~/rotate.txt" })
  if type(p) == "string" and p ~= "" then list[#list + 1] = { file = p } end
  return list
end
local function write_rotate(path, r)
  local f = io.open(path, "wb")
  if not f then return false end
  f:write(tostring(r)); f:close()
  return true
end
save_rotate = function(r)
  if r == last_rot then return true end
  for _, e in ipairs(rotate_paths()) do
    local ok = write_rotate(e.file, r)
    if not ok and e.dir then -- klasör yoksa oluştur, bir kez daha dene
      mp.command_native({ name = "subprocess", playback_only = false, args = { "cmd", "/c", "mkdir", e.dir } })
      ok = write_rotate(e.file, r)
    end
    if ok then last_rot = r; return true end
  end
  osd("döndürme kaydedilemedi", 3)
  return false
end
saved_rotate_apply = function()
  for _, e in ipairs(rotate_paths()) do
    local f = io.open(e.file, "rb")
    if f then
      local t = f:read("*a"); f:close()
      local r = tonumber(t)
      if r and r % 90 == 0 and r >= 0 and r < 360 then
        last_rot = r
        if r ~= (mp.get_property_number("video-rotate", 0) or 0) then
          mp.set_property_number("video-rotate", r)
        end
        return
      end
    end
  end
end
saved_rotate_apply()
rotate_ready = true
mp.observe_property("video-rotate", "number", function(_, r)
  if rotate_ready and r and r % 90 == 0 and r >= 0 and r < 360 then save_rotate(r) end
end)

local hold_pause = false -- "Hedefle video aç" videoyu bilerek duraklatır; o durumda otomatik oynatma yok
mp.register_event("file-loaded", function()
  if hold_pause then hold_pause = false else mp.set_property("pause", "no") end -- yeni video otomatik başlar
  if saved_rotate_apply then saved_rotate_apply() end
  local vpath = video_path()
  if vpath and not off.on and read_file(vpath .. ".views.json") then
    osd("offline takip verisi var - o tuşuyla aç", 3)
  end
end)

-- ---------------------------------------------------------------------------------------------
-- Canlı ayar tuşları (Parça 13): Python `track-setting` mesajını işler (canlı mod çalışıyorsa).
local function setting(key, dir)
  return function() mp.commandv("script-message", "track-setting", key, tostring(dir)) end
end
mp.add_key_binding(nil, "fill-up", setting("fill", 1))
mp.add_key_binding(nil, "fill-down", setting("fill", -1))
mp.add_key_binding(nil, "speed-up", setting("speed", 1))
mp.add_key_binding(nil, "speed-down", setting("speed", -1))
mp.add_key_binding(nil, "lead-up", setting("lead", 1))
mp.add_key_binding(nil, "lead-down", setting("lead", -1))
mp.add_key_binding(nil, "settings-reset", setting("all", 0))
mp.add_key_binding(nil, "settings-save", function() mp.commandv("script-message", "track-save") end)
mp.add_key_binding(nil, "status", function() mp.commandv("script-message", "track-status") end)
mp.add_key_binding(nil, "hud", function()
  hud.visible = not hud.visible
  render_hud()
  osd(hud.visible and "durum etiketi AÇIK" or "durum etiketi KAPALI", 1)
end)

-- ---------------------------------------------------------------------------------------------
-- Ekran üstü düğme paneli (Parça 16): fare/dokunma hareketinde sol üstte görünür, 3 sn sonra gizlenir
-- (sabitlenebilir). Düğmeler tuşlarla aynı işi yapar. Panel yalnız imleç bir düğmenin üstündeyken sol
-- tıkı yakalar; böylece mpv'nin kendi OSC'si/tıklaması bozulmaz. Zoom kontrolü (sağ kenar) için de aynı.
local PANEL_HIDE_S = 3
local panel = { visible = false, pinned = false, hover = nil, rects = {}, ov = nil, timer = nil }
local ZOOM_Z, PANEL_Z = 2, 1 -- overlay katmanları: HUD 0, panel 1, zoom kontrolü 2

-- Zoom + / - (ekran düğmeleri ve + / - tuşları). Hedef kilitliyken hedefin kareyi doldurma oranı
-- (Python zoom'u buna göre sürer); aksi halde doğrudan video-zoom (log2) ±0.25 (0..4).
local ZOOM_STEP, ZOOM_MAX = 0.25, 4
local function zoom_step(dir)
  if off.on then
    osd("offline açıkken zoom sabit; önce offline'ı kapat", 2)
  elseif enabled and locked then
    mp.commandv("script-message", "track-setting", "fill", tostring(dir))
  else
    local z = (mp.get_property_number("video-zoom", 0) or 0) + dir * ZOOM_STEP
    if z < 0 then z = 0 elseif z > ZOOM_MAX then z = ZOOM_MAX end
    mp.set_property_number("video-zoom", z)
    -- Seçili alan/nokta varsa onu ortada tut (pan = 0.5 - merkez; kenar boşluğu çıkmasın diye kıstır)
    local a = (enabled and dot.nx) and { dot.nx, dot.ny } or anchor
    local px, py = 0, 0
    if a and z > 0 then
      local lo = 0.5 / (2 ^ z)
      px = 0.5 - math.min(math.max(a[1], lo), 1 - lo)
      py = 0.5 - math.min(math.max(a[2], lo), 1 - lo)
    end
    mp.set_property_number("video-pan-x", px)
    mp.set_property_number("video-pan-y", py)
    zoomc.z = z
    render_zoom()
    osd(string.format("zoom x%.2f", 2 ^ z), 1)
  end
end
mp.add_key_binding(nil, "zoom-in", function() zoom_step(1) end, { repeatable = true })
mp.add_key_binding(nil, "zoom-out", function() zoom_step(-1) end, { repeatable = true })

local function setting_btn(key, dir)
  return function() mp.commandv("script-message", "track-setting", key, tostring(dir)) end
end

local buttons = {
  { label = function() return "Takip: " .. (enabled and "AÇIK" or "KAPALI") end,
    active = function() return enabled end, act = function() set_enabled(not enabled) end },
  { label = "Bırak", act = do_release },
  { label = "Sıfırla", act = do_reset },
  { label = function() return "Offline: " .. (off.on and "AÇIK" or "KAPALI") end,
    active = function() return off.on end, act = function() set_offline(not off.on) end },
  { label = "Tepki -", act = setting_btn("speed", -1) },
  { label = "Tepki +", act = setting_btn("speed", 1) },
  { label = "Öngörü -", act = setting_btn("lead", -1) },
  { label = "Öngörü +", act = setting_btn("lead", 1) },
  { label = "Kaydet", act = function() mp.commandv("script-message", "track-save") end },
  { label = "Durum", act = function() mp.commandv("script-message", "track-status") end },
  { label = function() return "Sabit: " .. (panel.pinned and "AÇIK" or "KAPALI") end,
    active = function() return panel.pinned end,
    act = function() panel.pinned = not panel.pinned end },
}

local function label_of(b) if type(b.label) == "function" then return b.label() end return b.label end

-- Küçük pencerede (H<700) düğme alt sınırı büyür (en çok x1.35), ekran ölçeği (hidpi) ile çarpılır.
local function ui_floor(H)
  local ok, hd = pcall(function() return mp.get_property_number("display-hidpi-scale", 1) end)
  hd = math.max(1, math.min((ok and tonumber(hd)) or 1, 3))
  local b = H / 700
  return ((b >= 1) and 1 or math.min(1 + (1 - b) * 0.5, 1.35)) * hd
end

-- Saf yerleşim: W,H pencere pikseli; labels -> rects {x,y,w,h} (satıra sarar), yazı boyutu.
local function panel_layout(W, H, labels)
  local bh = math.max(math.floor(30 * ui_floor(H)), math.floor(H * 0.06))
  local fs = math.floor(bh * 0.5)
  local gap = math.floor(bh * 0.2)
  local rects, x, y = {}, gap, gap
  for i, label in ipairs(labels) do
    local n = 0
    for _ in label:gmatch("[^\128-\191]") do n = n + 1 end -- UTF-8 karakter sayısı
    local w = math.floor(n * fs * 0.62 + bh * 0.8)
    if x > gap and x + w > W - gap then x, y = gap, y + bh + gap end
    rects[i] = { x = x, y = y, w = w, h = bh }
    x = x + w + gap
  end
  return rects, fs
end

local function hit(x, y)
  for i, r in ipairs(panel.rects) do
    if x >= r.x and x <= r.x + r.w and y >= r.y and y <= r.y + r.h then return i end
  end
  return nil
end

local function render_panel()
  if not panel.ov then
    panel.ov = mp.create_osd_overlay("ass-events")
    panel.ov.z = PANEL_Z
  end
  local d = osd_dims()
  -- Düğme paneli kapalı (yalnız sol sürükleme + ekrandaki +/− zoom); TRACK_SHOW_PANEL=true ile açılır
  if not TRACK_SHOW_PANEL or not panel.visible or not (d and d.w and d.w > 0 and d.h and d.h > 0) then
    panel.rects = {}
    panel.ov.data = ""
    panel.ov:update()
    return
  end
  local labels = {}
  for i, b in ipairs(buttons) do labels[i] = label_of(b) end
  local rects, fs = panel_layout(d.w, d.h, labels)
  panel.rects = rects
  panel.ov.res_x, panel.ov.res_y = d.w, d.h
  local ev = {}
  for i, r in ipairs(rects) do
    local on = buttons[i].active and buttons[i].active()
    local col = on and "3C8C2E" or "303030" -- BGR
    if panel.hover == i then col = on and "50B040" or "707070" end
    ev[#ev + 1] = string.format(
      "{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H%s&\\1a&H30&\\p1}m 0 0 l %d 0 l %d %d l 0 %d{\\p0}",
      r.x, r.y, col, r.w, r.w, r.h, r.h)
    ev[#ev + 1] = string.format("{\\an5\\pos(%d,%d)\\fs%d\\bord0\\shad0\\1c&HFFFFFF&}%s",
      r.x + math.floor(r.w / 2), r.y + math.floor(r.h / 2), fs, labels[i])
  end
  panel.ov.data = table.concat(ev, "\n")
  panel.ov:update()
end

-- Ekran üstü zoom kontrolü: sağ kenarda ortada dikey [ + ] / x1.5 / [ - ]. Takip açıkken, zoom
-- yapılmışken (>1x) ya da fare/dokunma hareketinden sonra görünür. Saf yerleşim `zoom_layout`.
local function zoom_layout(W, H)
  local bh = math.max(math.floor(36 * ui_floor(H)), math.floor(H * 0.09))
  local gap = math.floor(bh * 0.15)
  local x = W - bh - gap
  local rh = math.floor(bh * 0.7) -- değer kutusu yüksekliği
  local total = bh + gap + rh + gap + bh
  local y0 = math.floor((H - total) / 2)
  local plus = { x = x, y = y0, w = bh, h = bh }
  local val = { x = x, y = y0 + bh + gap, w = bh, h = rh }
  local minus = { x = x, y = y0 + bh + gap + rh + gap, w = bh, h = bh }
  return { plus, minus }, val, math.floor(bh * 0.6), math.floor(bh * 0.34)
end

local function zoom_wanted()
  return panel.visible or enabled or off.on or zoomc.z > 0.01
end

local function zoom_text() return string.format("x%.1f", 2 ^ zoomc.z) end

local function zoom_hit(x, y)
  for i, r in ipairs(zoomc.rects) do
    if x >= r.x and x <= r.x + r.w and y >= r.y and y <= r.y + r.h then return i end
  end
  return nil
end

render_zoom = function()
  if not zoomc.ov then
    zoomc.ov = mp.create_osd_overlay("ass-events")
    zoomc.ov.z = ZOOM_Z
  end
  local d = osd_dims()
  local ok = zoom_wanted() and d and d.w and d.w > 0 and d.h and d.h > 0
  if not ok then
    zoomc.rects, zoomc.hover = {}, nil
    if zoomc.shown ~= "" then zoomc.shown = ""; zoomc.ov.data = ""; zoomc.ov:update() end
    return
  end
  local rects, val, fs_btn, fs_val = zoom_layout(d.w, d.h)
  zoomc.rects = rects
  zoomc.ov.res_x, zoomc.ov.res_y = d.w, d.h
  local ev = {}
  for i, r in ipairs(rects) do
    local col = (zoomc.hover == i) and "707070" or "303030" -- BGR
    ev[#ev + 1] = string.format(
      "{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H%s&\\1a&H30&\\p1}m 0 0 l %d 0 l %d %d l 0 %d{\\p0}",
      r.x, r.y, col, r.w, r.w, r.h, r.h)
    ev[#ev + 1] = string.format("{\\an5\\pos(%d,%d)\\fs%d\\b1\\bord0\\shad0\\1c&HFFFFFF&}%s",
      r.x + math.floor(r.w / 2), r.y + math.floor(r.h / 2), fs_btn, i == 1 and "+" or "-")
  end
  ev[#ev + 1] = string.format(
    "{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H000000&\\1a&H60&\\p1}m 0 0 l %d 0 l %d %d l 0 %d{\\p0}",
    val.x, val.y, val.w, val.w, val.h, val.h)
  ev[#ev + 1] = string.format("{\\an5\\pos(%d,%d)\\fs%d\\b1\\bord0\\shad0\\1c&HFFFFFF&}%s",
    val.x + math.floor(val.w / 2), val.y + math.floor(val.h / 2), fs_val, zoom_text())
  local data = table.concat(ev, "\n")
  if data ~= zoomc.shown then -- değişmediyse yeniden çizme (takipte zoom sürekli güncellenir)
    zoomc.shown = data
    zoomc.ov.data = data
    zoomc.ov:update()
  end
end

-- Takip noktası: Python `track-dot <nx> <ny>` (video-normalize merkez) yollar, "off" gizler.
-- Beyaz nokta, mpv'nin şu anki zoom/pan'ına göre ekrana çevrilir (osd-dimensions kenar boşlukları
-- zoom/pan dahildir) → takip çalışıyorsa nesnenin üstünde durur.
local DOT_Z = 3

local function render_dot()
  if not dot.ov then
    dot.ov = mp.create_osd_overlay("ass-events")
    dot.ov.z = DOT_Z
  end
  local d = osd_dims()
  if not (dot.nx and enabled and not (off and off.on) and d and d.w and d.w > 0 and d.h and d.h > 0) then
    dot.ov.data = ""
    dot.ov:update()
    return
  end
  local bx0, by0, bw, bh = video_box(d)
  local x = bx0 + dot.nx * bw
  local y = by0 + dot.ny * bh
  if x < 0 or x > d.w or y < 0 or y > d.h then -- ekran dışı: çizme
    dot.ov.data = ""
    dot.ov:update()
    return
  end
  -- Küçük artı (nişangâh): ortası boş, 4 kol; beyaz, siyah kenarlı. Python henüz doğrulamadıysa
  -- (dot.prov) sarı: tıklama alındı ama takip programından yanıt yok.
  local L = math.max(math.floor(d.h * 0.018), 9) -- kol uzunluğu (merkezden)
  local g = math.max(math.floor(L * 0.35), 3)    -- ortadaki boşluk
  local ht = math.max(math.floor(d.h * 0.0015), 1) -- yarım çizgi kalınlığı
  local function rect(x0, y0, x1, y1) return string.format("m %d %d l %d %d l %d %d l %d %d", x0, y0, x1, y0, x1, y1, x0, y1) end
  local path = table.concat({
    rect(0, L - ht, L - g, L + ht), rect(L + g, L - ht, 2 * L, L + ht), -- sol, sağ
    rect(L - ht, 0, L + ht, L - g), rect(L - ht, L + g, L + ht, 2 * L), -- üst, alt
  }, " ")
  dot.ov.res_x, dot.ov.res_y = d.w, d.h
  dot.ov.data = string.format(
    "{\\an7\\pos(%d,%d)\\bord1\\shad0\\1c&H%s&\\3c&H000000&\\p1}%s{\\p0}",
    math.floor(x) - L, math.floor(y) - L, dot.prov and "00E0FF" or "FFFFFF", path)
  dot.ov:update()
end

-- Python açılışta modelleri yüklerken (birkaç sn–dakika) `track-loading 1`, bitince `0` yollar;
-- bu sürede sağ tık kuyruğa alınır, "yanıt vermiyor" uyarısı çıkmaz.
local loading = false
mp.register_script_message("track-loading", function(v)
  loading = (v == "1")
  if loading then osd("modeller yükleniyor… (tıklamalar kuyruğa alınır)", 4) end
end)

mp.register_script_message("track-dot", function(nx, ny)
  dot.prov = false -- takip programı yanıt verdi
  dot.nx, dot.ny = tonumber(nx), tonumber(ny) -- "off" → nil
  render_dot()
end)

-- Tıklanır tıklanmaz geçici (sarı) işaret: Python yanıt vermese de tıklamanın alındığı görünür.
-- 2 sn içinde `track-dot` gelmezse takip programının çalışmadığı OSD'de söylenir.
local prov_seq = 0
show_provisional = function(nx, ny)
  dot.nx, dot.ny, dot.prov = nx, ny, true
  render_dot()
  prov_seq = prov_seq + 1
  local my = prov_seq
  mp.add_timeout(2, function()
    if dot.prov and my == prov_seq and loading then
      osd("modeller hâlâ yükleniyor; tıklaman hazır olunca uygulanacak", 3)
    elseif dot.prov and my == prov_seq then
      osd("takip programı yanıt vermiyor (Python çalışıyor mu? mpv'yi yeniden aç)", 4)
    end
  end)
end
mp.observe_property("video-pan-x", "number", function() render_dot() end)
mp.observe_property("video-pan-y", "number", function() render_dot() end)
mp.observe_property("osd-dimensions", "native", function() render_dot() end)

-- video-zoom (takipte Python, elle zoomda biz) değişince değer etiketi güncellenir
mp.observe_property("video-zoom", "number", function(_, z)
  zoomc.z = z or 0
  render_zoom()
  render_dot()
  if hud.info ~= "" then render_hud() end -- bilgi bloğundaki zoom satırı
end)
mp.observe_property("video-rotate", "number", function() if hud.info ~= "" then render_hud() end end)

local zoom_buttons = { function() zoom_step(1) end, function() zoom_step(-1) end }

-- Sağ tık: takip noktası. Takip kapalıysa açılır; tıklanan noktadaki nesne (ne olursa olsun) hedef
-- olur (hedefin üstüne tekrar sağ tık hedefi bırakır). Sağ tuşla sürükleme dikdörtgen seçer (aşağıda).
local function pick_target()
  if panel.hover or zoomc.hover or menu_hot then return end
  if not enabled then set_enabled(true) end
  on_click("track-point")
end

-- Sol tuş: tıkla (oyuncu seç) ya da basılı tutup sürükle (dikdörtgen = takip noktası; kamera
-- hareketi telafi edilir, hedef kare dışına çıksa da konumu tahmin edilir). Sürüklerken çerçeve çizilir.
local drag = { x0 = nil, y0 = nil, ov = nil, btn = "left" }
local DRAG_MIN = 6 -- px: bundan kısa sürükleme tıklama sayılır

local function render_drag(x1, y1)
  if not drag.ov then
    drag.ov = mp.create_osd_overlay("ass-events")
    drag.ov.z = 4
  end
  if not x1 then
    drag.ov.data = ""
    drag.ov:update()
    return
  end
  local d = osd_dims()
  if not (d and d.w and d.w > 0) then return end
  local x0, y0 = math.floor(math.min(drag.x0, x1)), math.floor(math.min(drag.y0, y1))
  local x2, y2 = math.floor(math.max(drag.x0, x1)), math.floor(math.max(drag.y0, y1))
  local th = math.max(2, math.floor(d.h * 0.004)) -- çerçeve kalınlığı
  drag.ov.res_x, drag.ov.res_y = d.w, d.h
  -- Dolgulu şeritlerle çiz (kontur/şeffaf dolgu libass'ta güvenilir görünmüyordu) + hafif dolgu
  local function box(xa, ya, xb, yb, alpha)
    return string.format(
      "{\\an7\\pos(0,0)\\bord0\\shad0\\1c&H0000FF&\\1a&H%s&\\p1}m %d %d l %d %d l %d %d l %d %d{\\p0}",
      alpha, xa, ya, xb, ya, xb, yb, xa, yb)
  end
  drag.ov.data = table.concat({
    box(x0, y0, x2, y2, "C0"),
    box(x0, y0, x2, y0 + th, "00"),
    box(x0, y2 - th, x2, y2, "00"),
    box(x0, y0, x0 + th, y2, "00"),
    box(x2 - th, y0, x2, y2, "00"),
  }, "\n")
  drag.ov:update()
end

local function finish_drag(pos)
  local x0, y0 = drag.x0, drag.y0
  drag.x0, drag.y0 = nil, nil
  render_drag(nil)
  if not pos then return end
  if math.abs(pos.x - x0) < DRAG_MIN and math.abs(pos.y - y0) < DRAG_MIN then
    if drag.btn == "right" then pick_target() elseif enabled then on_click() end
    return
  end
  local d = osd_dims()
  local params = mp.get_property_native("video-params")
  if not (d and params and params.w and params.h) then return end
  local function clampn(v) return math.min(math.max(v, 0), 1) end
  local vx, vy, vw, vh = video_box(d)
  if vw <= 0 or vh <= 0 then return end
  local ax, ay = clampn((x0 - vx) / vw), clampn((y0 - vy) / vh)
  local bx, by = clampn((pos.x - vx) / vw), clampn((pos.y - vy) / vh)
  if not enabled then set_enabled(true) end
  anchor = { (ax + bx) / 2, (ay + by) / 2 }
  show_provisional(anchor[1], anchor[2])
  if scan_start then
    scan_start("rect", mp.get_property_number("time-pos", 0), ax, ay, bx, by)
  end
  mp.commandv("script-message", "track-rect",
    string.format("%.6f", ax), string.format("%.6f", ay),
    string.format("%.6f", bx), string.format("%.6f", by),
    string.format("%d", math.floor(params.w)), string.format("%d", math.floor(params.h)),
    string.format("%.3f", mp.get_property_number("time-pos", 0)))
  osd("alan seçildi - takip ediliyor", 1)
end

-- Sağ tuş (input.conf: track/pick bağı): kısa tık = nesne, basılı tutup sürükle =
-- dikdörtgen. ev yoksa/"press" ise (eski tek-olay çağrısı) doğrudan nesne seçer.
mp.add_key_binding(nil, "pick", function(ev)
  local pos = mouse_xy()
  local e = ev and ev.event or "press"
  if e == "press" then
    pick_target()
  elseif e == "down" then
    if not (panel.hover or zoomc.hover or menu_hot) and pos then
      drag.x0, drag.y0, drag.btn = pos.x, pos.y, "right"
    end
  elseif e == "up" and drag.x0 and drag.btn == "right" then
    finish_drag(pos)
  end
end, { complex = true })

-- Sol tuş: zoom açıkken (video-zoom > 0) basılı tutup sürükleyerek videoyu serbestçe kaydır (pan).
-- Ekran üstü düğme/menü üstündeyken başlamaz (orada sol tık düğmeye gider). Canlı takip hedefe
-- kilitliyken (Python pan'ı sürer) ya da offline açıkken kaydırma kapalıdır; sürükleme başlayınca bir
-- kez OSD ile söylenir. Pan değerleri video genişliği/yüksekliği kesri (video-pan-x/y); sınır: görüntü
-- pencereyi boş bırakmayacak kadar (video pencereden küçükse o eksende kaydırma yok).
local pan = { on = false, x = 0, y = 0, hinted = false }
local PAN_MIN_ZOOM = 0.01

local function pan_blocked_reason()
  if off.on then return "offline açıkken görüntü kaydırılamaz; önce offline'ı kapat" end
  if enabled and locked then return "takip hedefi izliyor; kaydırmak için önce hedefi bırak (X)" end
  return nil
end

local function pan_apply(x, y)
  local d = osd_dims()
  if not (d and d.w and d.w > 0 and d.h and d.h > 0) then return end
  local z = mp.get_property_number("video-zoom", 0) or 0
  local _, _, W, H = video_box(d)
  if W <= d.w + 0.5 and H <= d.h + 0.5 then -- kenar boşluğu/döndürme yolu: boyutu zoom'dan kestir
    W, H = d.w * 2 ^ z, d.h * 2 ^ z
  end
  local function limit(size, win) return size > win and (size - win) / (2 * size) or 0 end
  local lx, ly = limit(W, d.w), limit(H, d.h)
  local function clampn(v, l) return math.min(math.max(v, -l), l) end
  local px = clampn((mp.get_property_number("video-pan-x", 0) or 0) + (x - pan.x) / W, lx)
  local py = clampn((mp.get_property_number("video-pan-y", 0) or 0) + (y - pan.y) / H, ly)
  pan.x, pan.y = x, y
  mp.set_property_number("video-pan-x", px)
  mp.set_property_number("video-pan-y", py)
end

local function pan_move(pos)
  if not (pan.on and pos and pos.x) then return end
  if (mp.get_property_number("video-zoom", 0) or 0) <= PAN_MIN_ZOOM then return end
  local why = pan_blocked_reason()
  if why then
    pan.x, pan.y = pos.x, pos.y
    if not pan.hinted and (pos.x ~= pan.x0 or pos.y ~= pan.y0) then pan.hinted = true; osd(why, 2) end
    return
  end
  pan_apply(pos.x, pos.y)
end

-- input.conf: MBTN_LEFT script-binding track/pan (zorunlu bağ olmadığından ekran düğmelerinin
-- "track-click-bind" bağı ve mpcbar bağları önceliklidir).
mp.add_key_binding(nil, "pan", function(ev)
  local e = ev and ev.event or "press"
  if e == "down" then
    local pos = mouse_xy()
    if pos and pos.x and not (panel.hover or zoomc.hover or menu_hot)
        and (mp.get_property_number("video-zoom", 0) or 0) > PAN_MIN_ZOOM then
      pan.on, pan.x, pan.y, pan.x0, pan.y0, pan.hinted = true, pos.x, pos.y, pos.x, pos.y, false
    end
  elseif e == "up" or e == "press" then
    pan.on = false
  end
end, { complex = true })

-- Sol tuş: YALNIZ imleç ekran üstü bir düğmenin (zoom +/−, panel) üstündeyken yakalanır; başka her
-- yerde bağ kaldırılır, böylece mpv'nin kendi sol tık davranışı (OSC vb.) bozulmaz.
-- Ekran üstü düğme/menü üstündeki sol tık eylemi (tek tık ve çift tıkın 2. tıkı aynı işi görür:
-- hızlı arka arkaya zoom +/- tıklaması çift tık sayılıp kaybolmasın).
local function left_press()
  local mp_ = mouse_xy()
  if menu_hot and mp_ and menu then
    menu:click(mp_.x, mp_.y)
    menu_hot = menu:over(mp_.x, mp_.y) or menu:is_open()
    render_menu()
  elseif panel.hover then
    buttons[panel.hover].act()
    render_panel()
  elseif zoomc.hover then
    zoom_buttons[zoomc.hover]()
    render_zoom()
  end
end

sync_click_binding = function()
  local want = (panel.hover ~= nil) or (zoomc.hover ~= nil) or menu_hot
  if want and not panel.bound then
    mp.add_forced_key_binding("MBTN_LEFT", "track-click-bind", function(ev)
      ev = ev or { event = "press" }
      if ev.event ~= "press" and ev.event ~= "down" then return end
      left_press()
    end, { complex = true })
    panel.bound = true
  elseif not want and panel.bound then
    mp.remove_key_binding("track-click-bind")
    panel.bound = false
  end
end

-- Çift sol tık (input.conf: MBTN_LEFT_DBL → track/dblclick): videoyu oynat/duraklat.
-- İmleç ekran üstü düğme/menü üstündeyse pause yapmaz, o öğeye tıklanmış sayılır.
mp.add_key_binding(nil, "dblclick", function()
  if panel.hover or zoomc.hover or menu_hot then left_press(); return end
  mp.commandv("cycle", "pause")
end)

-- ---- Üst menü çubuğu bağlama (M1) -----------------------------------------------------------
local menu_ov, menu_keys_on = nil, false
local MENU_KEYS = { LEFT = "left", RIGHT = "right", UP = "up", DOWN = "down", ENTER = "enter",
  KP_ENTER = "enter", ESC = "esc" }
local MENU_Z = 4
local function menu_prepare()
  local d = osd_dims()
  if menu and d and d.w and d.w > 0 and d.h and d.h > 0 then
    local hd = math.max(1, math.min(mp.get_property_number("display-hidpi-scale", 1) or 1, 3))
    if menu.hd ~= hd then menu.hd = hd; menu.W = 0 end -- ekran ölçeği değişti: yerleşimi yenile
    menu:resize(d.w, d.h)
  end
  return d
end
local function sync_menu_keys()
  local want = menu ~= nil and menu:is_open()
  if want == menu_keys_on then return end
  menu_keys_on = want
  for k, name in pairs(MENU_KEYS) do
    if want then
      mp.add_forced_key_binding(k, "trackmenu-" .. k, function()
        menu:key(name)
        menu_hot = menu:is_open() or menu_hot
        render_menu()
      end, { repeatable = (name == "up" or name == "down") })
    else
      mp.remove_key_binding("trackmenu-" .. k)
    end
  end
end
render_menu = function()
  if not menu then return end
  if not menu_ov then menu_ov = mp.create_osd_overlay("ass-events"); menu_ov.z = MENU_Z end
  local d = menu_prepare()
  if d and d.w and d.w > 0 then menu_ov.res_x, menu_ov.res_y = d.w, d.h end
  if menu:is_open() then menu:relayout() end
  menu_ov.data = menu:render()
  menu_ov:update()
  sync_menu_keys()
  sync_click_binding()
end

refresh_panel = function()
  if panel.visible then render_panel() end
  render_zoom()
end

local function hide_panel()
  if menu and not menu:is_open() then menu:hide(); menu_hot = false; render_menu() end
  if panel.pinned then return end
  if panel.hover or zoomc.hover then panel.timer:resume() return end
  panel.visible, panel.hover = false, nil
  render_panel()
  render_zoom() -- zoom yapılmıyor ve takip kapalıysa kontrol de gizlenir
  sync_click_binding()
end

mp.observe_property("mouse-pos", "native", function(_, pos)
  if drag.x0 and pos and pos.x then render_drag(pos.x, pos.y) end -- sürükleme çerçevesi
  if pan.on and pos and pos.hover == false then pan.on = false end -- fare pencereden çıktı: bırak
  if pan.on and pos and pos.x then pan_move(pos) end -- sol tuşla kaydırma
  if pos and pos.hover == false and menu and not menu:is_open() then
    menu:hide(); menu_hot = false; render_menu() -- fare pencereden çıktı
  end
  if not pos or pos.hover == false then return end
  if menu then
    menu_prepare()
    if menu:mouse(pos.x or -1, pos.y or -1) then render_menu() end
    menu_hot = menu:over(pos.x or -1, pos.y or -1) or menu:is_open()
  end
  local was_visible, was_hover = panel.visible, panel.hover
  panel.visible = true
  if not panel.rects[1] then render_panel() end -- ilk çizimde yerleşim lazım
  panel.hover = hit(pos.x or -1, pos.y or -1)
  if not was_visible or was_hover ~= panel.hover then render_panel() end
  local was_zoom_hover = zoomc.hover
  if not zoomc.rects[1] then render_zoom() end -- ilk çizimde yerleşim lazım
  zoomc.hover = zoom_hit(pos.x or -1, pos.y or -1)
  if was_zoom_hover ~= zoomc.hover or not was_visible then render_zoom() end
  sync_click_binding()
  if not panel.timer then
    panel.timer = mp.add_timeout(PANEL_HIDE_S, hide_panel)
  else
    panel.timer:kill()
    panel.timer:resume()
  end
end)

mp.add_key_binding(nil, "panel", function() -- panelin sabitlenmesini aç/kapa
  panel.pinned = not panel.pinned
  panel.visible = true
  render_panel()
  osd(panel.pinned and "panel sabit" or "panel otomatik gizlenir", 1)
end)

-- Menü eylemleri (mevcut düğme/tuş işlevleri) ve durumları. Tabloda olmayan cmd gri kalır.
-- K: videoyu 90° döndürür (0 → 90 → 180 → 270 → 0). Not: canlı takip döndürülmüş videoda
-- desteklenmez (tıklama koordinatı döndürmeyi bilmez); takip için önce 0°'ye döndür.
local function rotate_video()
  local r = ((mp.get_property_number("video-rotate", 0) or 0) + 90) % 360
  mp.set_property_number("video-rotate", r)
  save_rotate(r)
  osd(string.format("döndürme %d°", r), 1)
end
mp.add_key_binding(nil, "rotate", rotate_video)

-- Ctrl+D: tanı (OSD + mpv günlüğü): pencere/OSD boyutları, kenar boşlukları, fare, döndürme, video
mp.add_key_binding(nil, "diag", function()
  local d = mp.get_property_native("osd-dimensions") or {}
  local p = mp.get_property_native("mouse-pos") or {}
  local vp = mp.get_property_native("video-params") or {}
  local msg = string.format(
    "osd-dim %sx%s ml=%s mr=%s mt=%s mb=%s | osd-wh %sx%s | fare %s,%s | rot %s zoom %s pan %s,%s | video %sx%s dw=%s dh=%s",
    tostring(d.w), tostring(d.h), tostring(d.ml), tostring(d.mr), tostring(d.mt), tostring(d.mb),
    tostring(mp.get_property_number("osd-width")), tostring(mp.get_property_number("osd-height")),
    tostring(p.x), tostring(p.y), tostring(mp.get_property_number("video-rotate")),
    tostring(mp.get_property_number("video-zoom")), tostring(mp.get_property_number("video-pan-x")),
    tostring(mp.get_property_number("video-pan-y")), tostring(vp.w), tostring(vp.h),
    tostring(vp.dw), tostring(vp.dh))
  mp.msg.info("TANI " .. msg)
  mp.osd_message(msg, 8)
end)

if TRACK_SHOW_MENU ~= false then
  local okm, TM = pcall(require, "trackmenu")
  if not okm then -- script-modules yolu mpv'de tanımlı değilse doğrudan dosyadan yükle
    local p = mp.command_native and mp.command_native({ "expand-path", "~~/script-modules/trackmenu.lua" })
    local f = type(p) == "string" and loadfile(p)
    if f then okm, TM = pcall(f) end
  end
  if not (okm and type(TM) == "table") then
    mp.msg.warn("trackmenu.lua yüklenemedi: " .. tostring(TM))
    osd("menü çubuğu yüklenemedi (mpv/portable_config/script-modules/trackmenu.lua yok mu?)", 6)
  end
  if okm and type(TM) == "table" then
    -- Dosya menüsü (M2): diyaloglar PowerShell ile (asenkron, mpv donmaz); son açılanlar/son klasör
    -- %APPDATA%\\TakipliOynat\\recent.json'da tutulur (klasör yoksa yalnız bellekte).
    local okf, TF = pcall(require, "trackfiles")
    if not okf then
      local p = mp.command_native and mp.command_native({ "expand-path", "~~/script-modules/trackfiles.lua" })
      local f = type(p) == "string" and loadfile(p)
      if f then okf, TF = pcall(f) end
    end
    local FILES = {}
    if okf and type(TF) == "table" then
      local WIN = TRACK_FORCE_WIN or package.config:sub(1, 1) == "\\"
      local sdir = os.getenv("TRACK_STATE_DIR") or os.getenv("APPDATA")
      local spath = sdir and (sdir .. "/TakipliOynat/recent.json") or nil
      local fstate = TF.sanitize(nil)
      local text = spath and read_file(spath)
      if text then fstate = TF.sanitize(utils.parse_json(text)) end
      local function save_fstate()
        local f = spath and io.open(spath, "wb")
        if f then f:write(utils.format_json({ dir = fstate.dir, recent = fstate.recent })); f:close() end
      end
      local busy = false
      local function dialog(script, cb)
        if not WIN then osd("dosya diyaloğu yalnız Windows'ta var", 3); return end
        if busy then return end
        busy = true
        mp.command_native_async({ name = "subprocess", playback_only = false, capture_stdout = true,
          args = TF.ps_args(script) }, function(ok, res)
          busy = false
          if not (ok and res and res.status == 0) then osd("diyalog açılamadı (PowerShell?)", 4); return end
          local out = TF.clean(res.stdout)
          if out then cb(out) end -- iptal = boş çıktı
        end)
      end
      local function open_target(p, is_dir)
        mp.commandv("loadfile", p, "replace")
        fstate = TF.add_recent(fstate, p, is_dir)
        save_fstate()
      end
      FILES.open = function() dialog(TF.open_script(fstate.dir), function(p) open_target(p, false) end) end
      FILES.recent = function()
        if #fstate.recent == 0 then osd("son açılan video yok", 2); return end
        dialog(TF.recent_script(fstate.recent), function(p) open_target(p, false) end)
      end
      -- A1: "Hedefle video aç + tara": video açılır ve duraklatılır; ilk hedef seçimi (sağ tık = nokta,
      -- sağ tuşla sürükle = alan) canlı takibi hemen başlatır ve arka planda
      -- `tracker offline VIDEO --select-point|--select-rect ...` taramasını başlatır. İlerleme
      -- `<video>.track.json.scan.json`'dan 2 sn'de bir okunur; bitince `o` tuşu offline takibi açar.
      local scan = { pending = false, running = false, prog = nil, slow = nil, last = nil }
      -- A3: oynatma açıkken tarama canlı takibe yer açsın: `<çıktı>.scan.slow` varlığı = yavaş tarama
      local function slow_flag(on)
        if not scan.slow then return end
        if on then
          local f = io.open(scan.slow, "wb")
          if f then f:write("1"); f:close() end
        else
          os.remove(scan.slow)
        end
      end
      mp.observe_property("pause", "bool", function(_, paused)
        if scan.running then slow_flag(not paused) end
      end)
      local function python_exe()
        local env = os.getenv("TRACK_PYTHON")
        if env then return env end
        local exe = mp.command_native and mp.command_native({ "expand-path", "~~exe_dir/" })
        if type(exe) ~= "string" or exe == "" then return nil end
        local root = exe:gsub("[/\\]+$", ""):gsub("[/\\][^/\\]*$", "")
        for _, name in ipairs({ "pythonw.exe", "python.exe" }) do
          local p = root .. "/python/" .. name
          if utils.file_info(p) then return p end
        end
      end
      local function poll_scan()
        if not scan.running then return end
        local text = read_file(scan.prog)
        local p = text and utils.parse_json(text)
        if type(p) == "table" then
          if p.state == "done" then
            scan.running = false
            slow_flag(false)
            if p.error and not p.target then osd("tarama bitti ama " .. p.error, 5)
            else osd("tarama bitti - offline takip için o tuşu", 5) end
            return
          elseif p.state == "cancelled" then
            scan.running = false
            slow_flag(false)
            osd("tarama durduruldu", 3)
            return
          end
          local tgt = p.target and (" · hedef ID " .. tostring(p.target.id)) or ""
          osd(string.format("tarama %%%s%s", tostring(p.pct or "?"), tgt), 1.5)
        end
        mp.add_timeout(2, poll_scan)
      end
      local function launch_scan(kind, t, a, b, c, d, rescan)
        local vp = video_path()
        if not vp then osd("hedefli tarama için yerel video gerek", 3); return end
        local py = python_exe()
        if not py then osd("tarama başlatılamadı: python/ bulunamadı", 4); return end
        local function f(v) return string.format("%.6f", v) end
        local args = { py, "-m", "tracker", "--config",
          (os.getenv("APPDATA") or ".") .. "/TakipliOynat/config.json", "offline", vp }
        if kind == "point" then
          for _, v in ipairs({ "--select-point", string.format("%.3f", t), f(a), f(b) }) do args[#args + 1] = v end
        else
          for _, v in ipairs({ "--select-rect", string.format("%.3f", t), f(a), f(b), f(c), f(d) }) do
            args[#args + 1] = v
          end
        end
        if rescan then args[#args + 1] = "--rescan" end
        mp.command_native({ name = "subprocess", playback_only = false, detached = true,
          capture_stdout = false, capture_stderr = false, args = args })
        scan.pending, scan.running, scan.prog = false, true, vp .. ".track.json.scan.json"
        scan.slow = vp .. ".track.json.scan.slow"
        scan.last = { kind, t, a, b, c, d }
        mp.set_property("pause", "no")
        slow_flag(true)
        osd(rescan and "yeniden taranıyor (önbellek yok sayıldı)" or "hedef seçildi - arka planda taranıyor", 3)
        mp.add_timeout(2, poll_scan)
      end
      scan_start = function(kind, t, a, b, c, d)
        if not scan.pending or scan.running then return end
        launch_scan(kind, t, a, b, c, d, false)
      end
      -- A3: Dosya → Yeniden tara: son hedefle, önbelleği yok sayarak (`--rescan`) baştan tara
      FILES.rescan = function()
        if scan.running then osd("tarama sürüyor", 2); return end
        if not scan.last then osd("önce «Hedefle video aç + tara…» ile hedef seç", 4); return end
        local l = scan.last
        launch_scan(l[1], l[2], l[3], l[4], l[5], l[6], true)
      end
      FILES.open_target = function()
        dialog(TF.open_script(fstate.dir), function(p)
          hold_pause = true
          open_target(p, false)
          scan.pending = true
          mp.set_property("pause", "yes")
          osd("hedefi sağ tıkla (nokta) ya da sağ tuşla sürükle (alan)", 6)
        end)
      end
      -- E1: Dosya → Takip kamerası dışa aktar: `<video>.views.json` varsa detached
      -- `tracker export VIDEO --out <video>.takipli.mp4`; ilerleme `<çıktı>.export.json`'dan okunur.
      local exp = { running = false, prog = nil }
      local function poll_export()
        if not exp.running then return end
        local text = read_file(exp.prog)
        local p = text and utils.parse_json(text)
        if type(p) == "table" then
          if p.state == "done" then
            exp.running = false
            osd("dışa aktarma bitti: " .. exp.out, 8)
            return
          elseif p.state == "error" or p.state == "cancelled" then
            exp.running = false
            osd(p.state == "error" and ("dışa aktarma hatası: " .. tostring(p.error)) or "dışa aktarma iptal", 6)
            return
          end
          osd(string.format("dışa aktarılıyor %%%s", tostring(p.pct or "?")), 1.5)
        end
        mp.add_timeout(2, poll_export)
      end
      FILES.export = function()
        if exp.running then osd("dışa aktarma sürüyor", 2); return end
        local vp = video_path()
        if not vp then osd("dışa aktarmak için yerel video aç", 3); return end
        if not read_file(vp .. ".views.json") then
          osd("görünüm dosyası yok: önce hedef seç ve tara (Hedefle video aç)", 5); return
        end
        local py = python_exe()
        if not py then osd("dışa aktarma başlatılamadı: python/ bulunamadı", 4); return end
        local out = vp:gsub("%.[^./\\]+$", "") .. ".takipli.mp4"
        mp.command_native({ name = "subprocess", playback_only = false, detached = true,
          capture_stdout = false, capture_stderr = false,
          args = { py, "-m", "tracker", "--config",
            (os.getenv("APPDATA") or ".") .. "/TakipliOynat/config.json", "export", vp, "--out", out } })
        exp.running, exp.prog, exp.out = true, out .. ".export.json", out
        osd("dışa aktarma başladı: " .. out, 4)
        mp.add_timeout(2, poll_export)
      end
      -- Yardım → Güncellemeleri denetle (VLC tarzı): önce yeni sürüm var mı bakılır (`tracker update
      -- check` → JSON); varsa Evet/Hayır sorulur; Evet → `tracker update install` (indir + doğrula +
      -- sessiz kurulum) ayrı süreçte çalışır, ilerleme `update.json`'dan okunur, kurulum başlayınca
      -- mpv kapanır (Inno Setup zaten açık mpv/python'u kapatır).
      local upd = { running = false }
      local function cfg_path() return (os.getenv("APPDATA") or ".") .. "/TakipliOynat/config.json" end
      local function console_python() -- stdout okunacağı için pythonw değil python.exe
        local p = python_exe()
        if not p then return nil end
        local c = p:gsub("pythonw%.exe$", "python.exe")
        if utils.file_info(c) then return c end
        return p
      end
      local function poll_update()
        local text = read_file(upd.prog)
        local p = text and utils.parse_json(text)
        if type(p) == "table" then
          if p.state == "launched" then
            osd("kurulum başlıyor; program kapanacak, bitince yeniden aç", 6)
            mp.add_timeout(2, function() mp.commandv("quit") end)
            return
          elseif p.state == "error" then
            upd.running = false
            osd("güncelleme başarısız: " .. tostring(p.error), 8)
            return
          end
          osd(string.format("güncelleme indiriliyor %%%s", tostring(p.pct or 0)), 1.5)
        end
        mp.add_timeout(1, poll_update)
      end
      local function start_install(info)
        local py = python_exe()
        if not py then osd("güncelleme başlatılamadı: python/ bulunamadı", 4); return end
        local dir = (os.getenv("APPDATA") or ".") .. "/TakipliOynat"
        upd.prog = dir .. "/update.json"
        os.remove(upd.prog)
        mp.command_native({ name = "subprocess", playback_only = false, detached = true,
          capture_stdout = false, capture_stderr = false,
          args = { py, "-m", "tracker", "--config", cfg_path(), "update", "install", "--progress", upd.prog } })
        osd("güncelleme indiriliyor…", 4)
        mp.add_timeout(1, poll_update)
      end
      FILES.update = function()
        if upd.running then osd("güncelleme sürüyor", 2); return end
        local py = console_python()
        if not py then osd("güncelleme denetlenemedi: python/ bulunamadı", 4); return end
        upd.running = true
        osd("güncellemeler denetleniyor…", 10)
        mp.command_native_async({ name = "subprocess", playback_only = false, capture_stdout = true,
          args = { py, "-m", "tracker", "--config", cfg_path(), "update", "check" } }, function(ok, res)
          local info = ok and res and res.status == 0 and utils.parse_json(TF.clean_all(res.stdout) or "")
          if type(info) ~= "table" then
            upd.running = false
            osd("güncelleme denetlenemedi", 5)
            return
          end
          if info.state == "uptodate" then
            upd.running = false
            osd("Takipli Oynat güncel (" .. tostring(info.current) .. ")", 5)
          elseif info.state == "available" then
            local mb = (tonumber(info.size) or 0) / 1048576
            local text = string.format("Yeni sürüm var: %s (şu an %s)\nİndirme: %.0f MB\n\n%s\n\nİndirip kurulsun mu? Kurulum sırasında program kapanır.",
              tostring(info.latest), tostring(info.current), mb, tostring(info.notes or ""):sub(1, 400))
            upd.running = false
            mp.osd_message("", 0)
            dialog(TF.confirm_script("Güncelleme", text), function(a)
              if a == "yes" then upd.running = true; start_install(info) end
            end)
          else
            upd.running = false
            osd(tostring(info.message or "güncelleme denetlenemedi"), 7)
          end
        end)
      end
      FILES.open_folder = function() dialog(TF.folder_script(fstate.dir), function(p) open_target(p, true) end) end
      FILES.open_url = function()
        dialog(TF.url_script(), function(u)
          if TF.is_url(u) then open_target(u, false) else osd("geçersiz adres: " .. u, 4) end
        end)
      end
      FILES.close = function() mp.commandv("stop") end
      FILES.save_view = function()
        local vp = video_path()
        if not vp then osd("kaydetmek için yerel video aç", 3); return end
        local snap = TF.snapshot(mp.get_property_number("video-zoom", 0), mp.get_property_number("video-pan-x", 0),
          mp.get_property_number("video-pan-y", 0), mp.get_property_number("video-rotate", 0))
        local f = io.open(vp .. ".view.json", "wb")
        if not f then osd("yazılamadı: " .. vp .. ".view.json", 4); return end
        f:write(utils.format_json(snap)); f:close()
        osd("görünüm kaydedildi (.view.json)")
      end
      FILES.load_view = function()
        if enabled or off.on then osd("görünümü yüklemek için önce takibi/offline'ı kapat", 3); return end
        local vp = video_path()
        local txt = vp and read_file(vp .. ".view.json")
        if not txt then osd("kayıtlı görünüm yok", 3); return end
        local z, px, py, r = TF.parse_snapshot(utils.parse_json(txt))
        if not z then osd("görünüm okunamadı: " .. tostring(px), 4); return end
        mp.set_property_number("video-zoom", z)
        mp.set_property_number("video-pan-x", px)
        mp.set_property_number("video-pan-y", py)
        mp.set_property_number("video-rotate", r)
        osd("görünüm yüklendi")
      end
    else
      mp.msg.warn("trackfiles.lua yüklenemedi: " .. tostring(TF))
    end
    local ACT = {
      quit = function() mp.commandv("quit") end,
      fullscreen = function() mp.commandv("cycle", "fullscreen") end,
      zoom_in = function() zoom_step(1) end, zoom_out = function() zoom_step(-1) end,
      reset = do_reset, rotate = rotate_video,
      hud = function() hud.visible = not hud.visible; render_hud() end,
      panel = function() panel.pinned = not panel.pinned; panel.visible = true; render_panel() end,
      toggle = function() set_enabled(not enabled) end, release = do_release,
      offline = function() set_offline(not off.on) end,
      src_auto = function() set_source("auto") end, src_live = function() set_source("live") end,
      src_scanned = function() set_source("scanned") end,
      bodypart = next_part, part_next = next_part,
      target_add = toggle_add,
      target_remove = function() mp.commandv("script-message", "track-remove") end,
      speed_up = setting("speed", 1), speed_down = setting("speed", -1),
      lead_up = setting("lead", 1), lead_down = setting("lead", -1),
      fill_up = setting("fill", 1), fill_down = setting("fill", -1),
      save = function() mp.commandv("script-message", "track-save") end,
      defaults = setting("all", 0),
      status = function() mp.commandv("script-message", "track-status") end,
    }
    for _, p in ipairs(PARTS) do ACT["part_" .. p] = function() set_part(p) end end
    for k, f in pairs(FILES) do ACT[k] = f end
    local NEED_VIDEO = { close = true, save_view = true, load_view = true } -- video yokken gri
    local LIVE = { release = true, target_remove = true, speed_up = true, speed_down = true, lead_up = true,
      lead_down = true, fill_up = true, fill_down = true }
    local CHECK = { src_auto = function() return source == "auto" end,
      src_live = function() return source == "live" end,
      src_scanned = function() return source == "scanned" end,
      toggle = function() return enabled end, target_add = function() return add_mode end,
      offline = function() return off.on end,
      hud = function() return hud.visible end, fullscreen = function() return mp.get_property_native("fullscreen") == true end, panel = function() return panel.pinned end }
    menu = TM.new({
      run = function(cmd)
        local f = ACT[cmd]
        if f then local ok, err = pcall(f); if not ok then mp.msg.warn("menü: " .. tostring(err)) end end
      end,
      state = function(cmd)
        if not ACT[cmd] then return { enabled = false } end
        local s = { enabled = true, checked = CHECK[cmd] and CHECK[cmd]() or false }
        if LIVE[cmd] and not enabled then s.enabled = false end
        if cmd:sub(1, 5) == "part_" and cmd ~= "part_next" then s.checked = (part == cmd:sub(6)) end
        if cmd == "bodypart" then s.label = "Vücut parçası: " .. PART_LABEL[part] end
        if cmd == "panel" and not TRACK_SHOW_PANEL then s.enabled = false end
        if NEED_VIDEO[cmd] and not mp.get_property("path") then s.enabled = false end
        return s
      end,
    })
    -- Çubuk pencereli modda hep açık; tam ekranda fare hareketinde görünür, zamanlayıcıyla gizlenir.
    menu:set_fullscreen(mp.get_property_native("fullscreen") == true)
    mp.observe_property("fullscreen", "bool", function(_, fs)
      menu:set_fullscreen(fs == true)
      menu_hot = false
      render_menu()
    end)
    mp.observe_property("osd-dimensions", "native", function(_, d) -- pencere boyutu değişince çubuğu yeniden yerleştir
      if d and d.w and (d.w ~= menu.W or d.h ~= menu.H) then render_menu() end
    end)
    render_menu()
    mp.add_key_binding(nil, "open", function() -- Ctrl+O: video aç
      if ACT.open then ACT.open() else osd("dosya menüsü yüklenemedi", 3) end
    end)
    mp.add_key_binding(nil, "menu", function() -- F10: menüyü klavyeyle aç/kapat
      menu_prepare()
      menu:key("f10")
      menu_hot = menu:is_open()
      render_menu()
    end)
  end
end

mp.msg.info("track.lua yüklendi")

-- Test kancası: imleç bir düğmenin üstünde mi?
function track_over() return panel.hover ~= nil or zoomc.hover ~= nil or menu_hot end

-- Otomatik başlatma: mpv açılınca Python takip programı da (gizli, ayrı süreç) başlar; mpv kapanınca
-- kendiliğinden biter. Kurulumdan sonra bat dosyası gerekmez: yalnız mpv.exe açılır.
do
  local utils = require("mp.utils")
  local exe = mp.command_native and mp.command_native({ "expand-path", "~~exe_dir/" })
  if type(exe) == "string" and exe ~= "" then
    local root = exe:gsub("[/\\]+$", ""):gsub("[/\\][^/\\]*$", "") -- mpv klasörünün üstü
    local py = nil
    for _, name in ipairs({ "pythonw.exe", "python.exe" }) do
      local p = root .. "/python/" .. name
      if utils.file_info(p) then py = p break end
    end
    if py then
      local appdata = os.getenv("APPDATA") or root
      local cfg = appdata .. "/TakipliOynat/config.json"
      mp.command_native({ name = "subprocess", playback_only = false, detached = true,
        capture_stdout = false, capture_stderr = false,
        args = { py, "-m", "tracker", "--config", cfg, "live", "--wait", "30" } })
      mp.msg.info("takip programı başlatıldı: " .. py)
    else
      mp.msg.warn("python/ bulunamadı; takip programı otomatik başlatılmadı")
    end
  end
end
