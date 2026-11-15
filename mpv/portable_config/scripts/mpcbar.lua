-- mpcbar.lua: Media Player Classic (klasik) görünümlü alt kontrol çubuğu.
-- mpv'nin dahili OSC'sinin yerine geçer (mpv.conf: osc=no). ASS overlay ile çizilir.
--   Üst şerit : arama (seek) çubuğu - koyu oluk, mavi dolgu, dikdörtgen sürgü, fare üstünde zaman ipucu
--   Alt şerit : Oynat/Duraklat, Durdur, Önceki, Geri 10 sn, İleri 10 sn, Sonraki | Hız -/+ | CC
--               sağda: zaman, sessiz, ses sürgüsü, tam ekran
-- Sol tıkı YALNIZ bir denetimin üstündeyken (ya da sürüklerken) yakalar; başka yerde track.lua
-- ve mpv davranışı bozulmaz (vlcbar.lua ile aynı yöntem).
local HIDE_S = 2.5     -- yalnız tam ekranda: hareketsizlikte gizleme süresi (sn)
local SEEK_STEP = 10   -- Geri/İleri düğmeleri (sn)

local ov = mp.create_osd_overlay("ass-events")
local st = { vis = true, hover = nil, els = {}, drag = nil, bound = false, mx = 0, my = 0, lastq = -1 }

-- Klasik renkler (ASS: BBGGRR)
local C = {
  panel   = "1E1E1E", panel2 = "2A2A2A", line   = "555555", line2 = "0A0A0A",
  track   = "101010", trackb = "5A5A5A", fill   = "D59B5B",  -- mavi (RGB 5B9BD5)
  thumb   = "D8D8D8", thumbb = "FFFFFF", icon   = "E6E6E6",  dim   = "7A7A7A",
  hov     = "464646", hovb   = "8C8C8C", accent = "FFB060",  text  = "E6E6E6",
  tipbg   = "F0FFFF", tiptx  = "000000",
}
local FONT = "Tahoma"

local function dims()
  local w = mp.get_property_number("osd-width", 0)
  local h = mp.get_property_number("osd-height", 0)
  if w <= 0 or h <= 0 then
    local d = mp.get_property_native("osd-dimensions") or {}
    w, h = d.w or 0, d.h or 0
  end
  return w, h
end

local function fmt(t)
  t = math.max(0, math.floor((t or 0) + 0.5))
  local h, m, s = math.floor(t / 3600), math.floor(t % 3600 / 60), t % 60
  return string.format("%02d:%02d:%02d", h, m, s)
end

local function clamp(v, a, b) return math.max(a, math.min(b, v)) end
local function round(v) return math.floor(v + 0.5) end

-- ---- ASS çizim yardımcıları -------------------------------------------------
local function rectp(w, h) return string.format("m 0 0 l %d 0 %d %d 0 %d", w, w, h, h) end

local function polyp(pts, s)
  local o = { "m" }
  for i = 1, #pts, 2 do
    o[#o + 1] = string.format("%d %d", round(pts[i] * s), round(pts[i + 1] * s))
    if i == 1 then o[#o + 1] = "l" end
  end
  return table.concat(o, " ")
end

local function draw(x, y, path, c, a, bord, bc)
  return string.format("{\\an7\\pos(%d,%d)\\bord%d\\3c&H%s&\\shad0\\1c&H%s&\\1a&H%02X&\\p1}%s{\\p0}",
    round(x), round(y), bord or 0, bc or c, c, a or 0, path)
end

local function rect(x, y, w, h, c, a, bord, bc)
  return draw(x, y, rectp(round(w), round(h)), c, a, bord, bc)
end

local function label(x, y, s, str, c, an, bold)
  return string.format("{\\an%d\\pos(%d,%d)\\fn%s\\fs%d\\b%d\\bord0\\shad0\\1c&H%s&}%s",
    an or 5, round(x), round(y), FONT, round(s), bold and 1 or 0, c, str)
end

-- Simgeler: 16x16 birim kutuda, (x, y) sol üst, s ölçek
local function ipoly(x, y, pts, s, c) return draw(x, y, polyp(pts, s), c) end
local function irect(x, y, x1, y1, x2, y2, s, c)
  return rect(x + x1 * s, y + y1 * s, (x2 - x1) * s, (y2 - y1) * s, c)
end

local ICON = {
  play  = function(x, y, s, c) return ipoly(x, y, { 3, 1, 14, 8, 3, 15 }, s, c) end,
  pause = function(x, y, s, c) return irect(x, y, 3, 2, 7, 14, s, c) .. irect(x, y, 9, 2, 13, 14, s, c) end,
  stop  = function(x, y, s, c) return irect(x, y, 3, 3, 13, 13, s, c) end,
  prev  = function(x, y, s, c) return irect(x, y, 2, 2, 4, 14, s, c) .. ipoly(x, y, { 14, 2, 5, 8, 14, 14 }, s, c) end,
  next  = function(x, y, s, c) return ipoly(x, y, { 2, 2, 11, 8, 2, 14 }, s, c) .. irect(x, y, 12, 2, 14, 14, s, c) end,
  rew   = function(x, y, s, c) return ipoly(x, y, { 8, 2, 1, 8, 8, 14 }, s, c) .. ipoly(x, y, { 15, 2, 8, 8, 15, 14 }, s, c) end,
  ffwd  = function(x, y, s, c) return ipoly(x, y, { 1, 2, 8, 8, 1, 14 }, s, c) .. ipoly(x, y, { 8, 2, 15, 8, 8, 14 }, s, c) end,
  full  = function(x, y, s, c)
    return ipoly(x, y, { 1, 1, 6, 1, 6, 3, 3, 3, 3, 6, 1, 6 }, s, c)
        .. ipoly(x, y, { 15, 1, 15, 6, 13, 6, 13, 3, 10, 3, 10, 1 }, s, c)
        .. ipoly(x, y, { 1, 15, 1, 10, 3, 10, 3, 13, 6, 13, 6, 15 }, s, c)
        .. ipoly(x, y, { 15, 15, 10, 15, 10, 13, 13, 13, 13, 10, 15, 10 }, s, c)
  end,
  vol   = function(x, y, s, c, muted)
    local o = ipoly(x, y, { 1, 6, 5, 6, 9, 2, 9, 14, 5, 10, 1, 10 }, s, c)
    if muted then
      return o .. ipoly(x, y, { 11, 5, 12.5, 4, 15.5, 11, 14, 12 }, s, "3C3CE0")
          .. ipoly(x, y, { 14, 4, 15.5, 5, 12.5, 12, 11, 11 }, s, "3C3CE0")
    end
    return o .. irect(x, y, 11, 5, 12.5, 11, s, c) .. irect(x, y, 13.5, 3, 15, 13, s, c)
  end,
}

-- ---- yerleşim -----------------------------------------------------------------
local function vmax() return mp.get_property_number("volume-max", 100) or 100 end
local function sub_on() local s = mp.get_property("sid"); return s ~= nil and s ~= "no" end
local function speed() return mp.get_property_number("speed", 1) or 1 end

local function speed_step(d)
  local s = clamp(round((speed() + d) * 10) / 10, 0.1, 4)
  mp.set_property_number("speed", s)
  mp.osd_message(string.format("Hız: %.1fx", s), 1.5)
end

local ACT = {
  play = function() mp.commandv("cycle", "pause") end,
  stop = function() mp.set_property_bool("pause", true); mp.commandv("seek", "0", "absolute") end,
  prev = function() mp.command("playlist-prev") end,
  next = function() mp.command("playlist-next") end,
  rew  = function() mp.commandv("seek", tostring(-SEEK_STEP)) end,
  ffwd = function() mp.commandv("seek", tostring(SEEK_STEP)) end,
  mute = function() mp.commandv("cycle", "mute") end,
  full = function() mp.commandv("cycle", "fullscreen") end,
  cc   = function() mp.command("osd-auto cycle sub") end,
  spdm = function() speed_step(-0.1) end,
  spdp = function() speed_step(0.1) end,
  spd0 = function() st.strip = not st.strip end, -- hızlı seçim şeridini aç/kapa (0.5 / 1 / 1.5 / 2)
}
local SPEEDS = { 0.5, 1, 1.5, 2 }
local VOL_WHEEL = 5

local function volume_step(d)
  local v = clamp(round((mp.get_property_number("volume", 100) or 100) + d), 0, vmax())
  mp.set_property_number("volume", v)
  if mp.get_property_bool("mute", false) then mp.set_property_bool("mute", false) end
  mp.osd_message(string.format("Ses: %d%%", v), 1.5)
end

-- Ölçek: pencere yüksekliğiyle orantılı (H/700). Küçük pencerede (H<700) düğmeler parmakla/fareyle
-- basılabilir kalsın diye küçülmek yerine büyür (en çok x1.35); ekran ölçeği (hidpi) alt sınırı yükseltir.
local function ui_scale(W, H)
  local hd = clamp(mp.get_property_number("display-hidpi-scale", 1) or 1, 1, 3)
  local b = H / 700
  local boost = (b >= 1) and 1 or math.min(1 + (1 - b) * 0.5, 1.35)
  local u = math.max(clamp(b, 0.9, 2.2), boost * hd)
  return math.max(0.7, math.min(u, W / 300)) -- dar pencerede çekirdek düğmeler sığsın
end

local function layout()
  local W, H = dims()
  st.els = {}
  if W <= 0 or H <= 0 then return W, H end
  local u = ui_scale(W, H)
  local seekh, tbh = round(20 * u), round(40 * u)
  local ph = seekh + tbh
  local top, tby = H - ph, H - ph + seekh
  local pad = round(8 * u)
  local bs = tbh - round(8 * u)
  local by = tby + round((tbh - bs) / 2)
  local g = round(2 * u)
  local L = { u = u, W = W, H = H, top = top, seekh = seekh, tbh = tbh, tby = tby, ph = ph, pad = pad, bs = bs }

  local function add(e) st.els[#st.els + 1] = e; return e end

  -- arama çubuğu
  local th = round(8 * u)
  L.tx0, L.tx1 = pad + round(4 * u), W - pad - round(4 * u)
  L.thw, L.thh = round(9 * u), seekh - round(4 * u)
  add({ id = "seek", x = pad, y = top, w = W - 2 * pad, h = seekh })

  -- sol: düğmeler
  local x = pad
  local function btn(id, icon)
    add({ id = id, icon = icon, x = x, y = by, w = bs, h = bs }); x = x + bs + g
  end
  btn("play", "play"); btn("stop", "stop"); x = x + round(6 * u)
  btn("prev", "prev"); btn("rew", "rew"); btn("ffwd", "ffwd"); btn("next", "next")
  local left_end = x

  -- sağ: tam ekran, ses sürgüsü, sessiz, zaman
  local rx = W - pad
  rx = rx - bs; add({ id = "full", icon = "full", x = rx, y = by, w = bs, h = bs })
  rx = rx - round(10 * u)
  local vw = round(84 * u)
  if rx - left_end >= vw + bs + g then -- ses sürgüsü yalnız yer varsa
    rx = rx - vw; add({ id = "vol", x = rx, y = by, w = vw, h = bs })
    rx = rx - bs - g; add({ id = "mute", icon = "vol", x = rx, y = by, w = bs, h = bs })
  else
    rx = rx - bs; add({ id = "mute", icon = "vol", x = rx, y = by, w = bs, h = bs })
  end
  L.fs = round(14 * u)
  local ts = fmt(mp.get_property_number("time-pos", 0)) .. " / " .. fmt(mp.get_property_number("duration", 0))
  local tw = round(#ts * L.fs * 0.56)
  if rx - left_end >= round(12 * u) + tw then -- zaman yazısı yalnız yer varsa
    rx = rx - round(12 * u)
    L.time_x, L.time_y, L.time_s = rx, by + round(bs / 2), ts
    rx = rx - tw
  end

  -- ortada: hız ve CC (yer varsa)
  local need = round(250 * u)
  if rx - left_end > need then
    x = left_end + round(18 * u)
    local sw = round(bs * 0.8)
    local sp0
    add({ id = "spdm", text = "-", x = x, y = by, w = sw, h = bs }); x = x + sw + g
    sp0 = add({ id = "spd0", text = string.format("%.1fx", speed()), on = math.abs(speed() - 1) > 0.01,
          x = x, y = by, w = round(bs * 1.6), h = bs }); x = x + round(bs * 1.6) + g
    add({ id = "spdp", text = "+", x = x, y = by, w = sw, h = bs }); x = x + sw + round(10 * u)
    add({ id = "cc", text = "CC", on = sub_on(), off = not sub_on(), x = x, y = by, w = round(bs * 1.2), h = bs })
    if st.strip then
      local sx, sy, cw = sp0.x, top - bs - round(6 * u), round(bs * 1.6)
      L.strip = { x = sx - g, y = sy - g, w = #SPEEDS * (cw + g) + g, h = bs + 2 * g }
      for i, v in ipairs(SPEEDS) do
        add({ id = "spq", val = v, text = (v == 1 and "1x" or string.format("%.1fx", v)),
              on = math.abs(speed() - v) < 0.01, x = sx + (i - 1) * (cw + g), y = sy, w = cw, h = bs })
      end
    end
  end
  return W, H, L
end

local function hit(x, y)
  for i = #st.els, 1, -1 do
    local e = st.els[i]
    if x >= e.x and x <= e.x + e.w and y >= e.y and y <= e.y + e.h then return i end
  end
  return nil
end

-- ---- çizim ----------------------------------------------------------------------
local function seek_ratio_at(L, x) return clamp((x - L.tx0) / math.max(1, L.tx1 - L.tx0), 0, 1) end

local function nchars(str)
  local n = 0
  for _ in tostring(str):gmatch("[^\128-\191]") do n = n + 1 end -- UTF-8 karakter sayısı
  return n
end

-- t sn'deki bölümün adı (chapter-list: {time,title}, artan sıra); yoksa nil
local function chapter_at(t)
  local list = mp.get_property_native("chapter-list")
  if type(list) ~= "table" or #list == 0 then return nil end
  local name
  for i, c in ipairs(list) do
    if (c.time or 0) <= t then name = c.title or ("Bölüm " .. i) else break end
  end
  return name
end

local function render()
  if not st.vis then ov:remove(); return end
  local W, H, L = layout()
  if W <= 0 or not L then return end
  ov.res_x, ov.res_y = W, H
  local o = {}
  local u = L.u

  -- zemin: gri şerit + kabartma çizgileri
  o[#o + 1] = rect(0, L.top, W, L.ph, C.panel, 0x00)
  o[#o + 1] = rect(0, L.top, W, L.seekh, C.panel2, 0x00)
  o[#o + 1] = rect(0, L.top, W, 1, C.line, 0)
  o[#o + 1] = rect(0, L.tby, W, 1, C.line2, 0)
  o[#o + 1] = rect(0, L.tby + 1, W, 1, "3A3A3A", 0)

  -- arama çubuğu
  local dur = mp.get_property_number("duration", 0) or 0
  local pos = mp.get_property_number("time-pos", 0) or 0
  local ratio = dur > 0 and clamp(pos / dur, 0, 1) or 0
  local th = round(7 * u)
  local ty = L.top + round((L.seekh - th) / 2)
  o[#o + 1] = rect(L.tx0, ty, L.tx1 - L.tx0, th, C.track, 0, 1, C.trackb)
  local fx = L.tx0 + round(ratio * (L.tx1 - L.tx0))
  if fx > L.tx0 then o[#o + 1] = rect(L.tx0, ty, fx - L.tx0, th, C.fill, 0) end
  -- A-B döngü işaretleri (ab-loop-a/b; yoksa "no" → sayı değil)
  local ab_a = mp.get_property_number("ab-loop-a")
  local ab_b = mp.get_property_number("ab-loop-b")
  if dur > 0 then
    local mw = math.max(2, round(2 * u))
    for _, v in ipairs({ ab_a or -1, ab_b or -1 }) do
      if v >= 0 then
        local mx = L.tx0 + round(clamp(v / dur, 0, 1) * (L.tx1 - L.tx0))
        o[#o + 1] = rect(mx - round(mw / 2), L.top + 1, mw, L.seekh - 2, C.accent or "FFC000", 0)
      end
    end
    if ab_a and ab_b and ab_b > ab_a then
      local ax = L.tx0 + round(clamp(ab_a / dur, 0, 1) * (L.tx1 - L.tx0))
      local bx = L.tx0 + round(clamp(ab_b / dur, 0, 1) * (L.tx1 - L.tx0))
      o[#o + 1] = rect(ax, ty + th, bx - ax, math.max(1, round(u)), C.accent or "FFC000", 0)
    end
  end
  local thx = fx - round(L.thw / 2)
  local thy = L.top + round((L.seekh - L.thh) / 2)
  o[#o + 1] = rect(thx, thy, L.thw, L.thh, C.thumb, 0, 1, C.thumbb)

  -- hız şeridi zemini (düğmelerden önce çizilir)
  if L.strip then
    o[#o + 1] = rect(L.strip.x, L.strip.y, L.strip.w, L.strip.h, C.panel, 0, 1, C.hovb)
  end

  -- düğmeler
  local paused = mp.get_property_bool("pause", false)
  local muted = mp.get_property_bool("mute", false)
  local vol = mp.get_property_number("volume", 100) or 100
  for i, e in ipairs(st.els) do
    local on = (i == st.hover)
    if e.id ~= "seek" and e.id ~= "vol" then
      if on then
        o[#o + 1] = rect(e.x, e.y, e.w, e.h, C.hov, 0, 1, C.hovb)
      end
      local col = on and "FFFFFF" or C.icon
      if e.icon then
        local s = (e.h * 0.5) / 16
        local ix, iy = e.x + round((e.w - 16 * s) / 2), e.y + round((e.h - 16 * s) / 2)
        local icon = e.icon
        if e.id == "play" and not paused then icon = "pause" end
        if e.id == "mute" then
          o[#o + 1] = ICON.vol(ix, iy, s, muted and C.dim or col, muted)
        else
          o[#o + 1] = ICON[icon](ix, iy, s, col)
        end
      elseif e.text then
        local tc = on and "FFFFFF" or (e.on and C.accent) or (e.off and C.dim) or C.icon
        o[#o + 1] = label(e.x + e.w / 2, e.y + e.h / 2, e.h * 0.42, e.text, tc, 5, true)
      end
    elseif e.id == "vol" then
      -- ses sürgüsü: ince oluk + dikdörtgen sürgü
      local sh = round(5 * u)
      local sy = e.y + round((e.h - sh) / 2)
      local vr = clamp(vol / vmax(), 0, 1)
      o[#o + 1] = rect(e.x, sy, e.w, sh, C.track, 0, 1, C.trackb)
      local vf = round(vr * e.w)
      if vf > 0 then o[#o + 1] = rect(e.x, sy, vf, sh, muted and C.dim or C.fill, 0) end
      local vw, vh = round(7 * u), round(e.h * 0.62)
      o[#o + 1] = rect(e.x + vf - round(vw / 2), e.y + round((e.h - vh) / 2), vw, vh, C.thumb, 0, 1, C.thumbb)
    end
  end

  -- zaman (sağa dayalı)
  if L.time_s then o[#o + 1] = label(L.time_x, L.time_y, L.fs, L.time_s, C.text, 6, false) end

  -- arama çubuğu ipucu (fare üstünde ya da sürüklerken)
  local he = st.hover and st.els[st.hover]
  if dur > 0 and ((he and he.id == "seek") or st.drag == "seek") then
    local r = seek_ratio_at(L, st.mx)
    local s = fmt(r * dur)
    local ch = chapter_at(r * dur)
    if ch then s = s .. "  " .. ch end
    local tfs = round(13 * u)
    local tw, tbh = round(nchars(s) * tfs * 0.6) + round(12 * u), tfs + round(8 * u)
    local tx = clamp(st.mx - round(tw / 2), 2, W - tw - 2)
    local tyy = L.top - tbh - round(4 * u)
    o[#o + 1] = rect(tx, tyy, tw, tbh, C.tipbg, 0, 1, "000000")
    o[#o + 1] = label(tx + tw / 2, tyy + tbh / 2, tfs, s, C.tiptx, 5, false)
  end

  -- ses yüzdesi ipucu (sürgü üstünde ya da sürüklerken)
  if (he and he.id == "vol") or st.drag == "vol" then
    local ve
    for _, e in ipairs(st.els) do if e.id == "vol" then ve = e end end
    if ve then
      local vs = string.format("%d%%", round(vol))
      local tfs = round(13 * u)
      local tw, tbh = round(#vs * tfs * 0.6) + round(12 * u), tfs + round(8 * u)
      local vx = ve.x + round(clamp(vol / vmax(), 0, 1) * ve.w)
      local tx = clamp(vx - round(tw / 2), 2, W - tw - 2)
      local tyy = L.top - tbh - round(4 * u)
      o[#o + 1] = rect(tx, tyy, tw, tbh, C.tipbg, 0, 1, "000000")
      o[#o + 1] = label(tx + tw / 2, tyy + tbh / 2, tfs, vs, C.tiptx, 5, false)
    end
  end

  ov.data = table.concat(o, "\n")
  ov:update()
end

-- ---- etkileşim ------------------------------------------------------------------
local function apply_drag(x)
  local W, H, L = layout()
  if not L then return end
  if st.drag == "seek" then
    local dur = mp.get_property_number("duration", 0) or 0
    if dur > 0 then mp.commandv("seek", tostring(seek_ratio_at(L, x) * dur), "absolute+exact") end
  elseif st.drag == "vol" then
    for _, e in ipairs(st.els) do
      if e.id == "vol" then
        local r = clamp((x - e.x) / e.w, 0, 1)
        mp.set_property_number("volume", round(r * vmax()))
        if mp.get_property_bool("mute", false) then mp.set_property_bool("mute", false) end
      end
    end
  end
end

local sync -- ileri bildirim (aşağıda tanımlı)

local function press(ev)
  local e = ev and ev.event
  if e == "up" then st.drag = nil; render(); sync(); return end
  if e and e ~= "down" and e ~= "press" then return end
  local el = st.hover and st.els[st.hover]
  if not el then return end
  if st.strip and not el.val and el.id ~= "spd0" then st.strip = false end -- şerit dışı tık: kapat
  if el.val then
    mp.set_property_number("speed", el.val)
    mp.osd_message(string.format("Hız: %.1fx", el.val), 1.5)
    st.strip = false
  elseif el.id == "seek" or el.id == "vol" then
    st.drag = el.id
    apply_drag(st.mx)
  else
    ACT[el.id]()
  end
  layout(); st.hover = hit(st.mx, st.my) -- şerit açılıp kapanınca öğe dizisi değişir
  render()
end

local KEYS = { "MBTN_LEFT", "MBTN_LEFT_DBL" }
local WHEEL = { WHEEL_UP = VOL_WHEEL, WHEEL_DOWN = -VOL_WHEEL } -- yalnız fare çubuk üstündeyken bağlı
sync = function()
  local want = st.vis and (st.hover ~= nil or st.drag ~= nil)
  if want and not st.bound then
    for _, k in ipairs(KEYS) do mp.add_forced_key_binding(k, "mpcbar-" .. k, press, { complex = true }) end
    for k, d in pairs(WHEEL) do
      mp.add_forced_key_binding(k, "mpcbar-" .. k, function() volume_step(d) end)
    end
    st.bound = true
  elseif not want and st.bound then
    for _, k in ipairs(KEYS) do mp.remove_key_binding("mpcbar-" .. k) end
    for k in pairs(WHEEL) do mp.remove_key_binding("mpcbar-" .. k) end
    st.bound = false
  end
end

local timer
timer = mp.add_timeout(HIDE_S, function()
  if st.hover or st.drag then timer:resume(); return end
  if mp.get_property_bool("fullscreen") then st.vis = false; render(); sync() end
end)
timer:kill()

mp.observe_property("mouse-pos", "native", function(_, pos)
  if not pos or not pos.hover then
    st.hover = nil; st.strip = false
    if mp.get_property_bool("fullscreen") and not st.drag then st.vis = false end
  else
    st.vis = true
    st.mx, st.my = pos.x or 0, pos.y or 0
    layout()
    if st.drag then apply_drag(st.mx) end
    st.hover = hit(st.mx, st.my)
    if st.strip then -- şeritten/hız düğmesinden/seek geçişinden başka yere gidilirse kapat
      local h = st.hover and st.els[st.hover]
      if not (h and (h.val or h.id == "spd0" or h.id == "seek")) then
        st.strip = false; layout(); st.hover = hit(st.mx, st.my)
      end
    end
    timer:kill(); timer:resume()
  end
  render(); sync()
end)

-- değişimde yeniden çiz; time-pos çeyrek saniyede bir
mp.observe_property("time-pos", "number", function(_, t)
  if not st.vis then return end
  local q = math.floor((t or 0) * 4)
  if q ~= st.lastq then st.lastq = q; render() end
end)
for _, p in ipairs({ "duration", "pause", "volume", "mute", "speed", "sid", "osd-width", "osd-height", "ab-loop-a", "ab-loop-b" }) do
  mp.observe_property(p, "native", function() if st.vis then render() end end)
end
mp.observe_property("fullscreen", "native", function(_, fs)
  st.vis = true
  if fs then timer:kill(); timer:resume() end
  render()
end)

render()
