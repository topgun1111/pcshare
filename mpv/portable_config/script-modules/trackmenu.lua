-- Takipli Oynat: üst menü çubuğu motoru (M1). SAF mantık: `mp`'ye dokunmaz, texlua ile test edilir.
-- track.lua `ctx = { run = function(cmd), state = function(cmd) -> {enabled, checked, label} }` verir;
-- motor yerleşim, fare/klavye gezinmesi ve ASS çizimini yapar. todo=true: henüz yok → gri, tıklanmaz.
local M = {}

-- Bildirimsel menü tablosu. cmd: track.lua'daki eylem adı; key: yalnız kısayol ipucu (metin).
M.MENUS = {
  { id = "file", label = "Dosya", items = {
    { cmd = "open", label = "Video aç…", key = "Ctrl+O" },
    { cmd = "recent", label = "Son açılanlar…" },
    { cmd = "open_folder", label = "Klasör aç…" },
    { cmd = "open_url", label = "Adres (URL) aç…" },
    { sep = true },
    { cmd = "save_view", label = "Görünümü kaydet" },
    { cmd = "load_view", label = "Görünümü yükle" },
    { cmd = "close", label = "Videoyu kapat" },
    { sep = true },
    { cmd = "open_target", label = "Hedefle video aç + tara…" },
    { cmd = "export", label = "Takip kamerası dışa aktar…" },
    { cmd = "rescan", label = "Yeniden tara (önbelleği yok say)" },
    { sep = true },
    { cmd = "quit", label = "Çıkış", key = "Q" },
  } },
  { id = "view", label = "Görünüm", items = {
    { cmd = "zoom_in", label = "Yakınlaştır", key = "+" },
    { cmd = "zoom_out", label = "Uzaklaştır", key = "-" },
    { cmd = "reset", label = "Görünümü sıfırla", key = "R" },
    { cmd = "rotate", label = "Videoyu döndür (90°)", key = "K" },
    { sep = true },
    { cmd = "hud", label = "Durum etiketi", key = "Ctrl+H" },
    { cmd = "panel", label = "Düğme paneli sabit", key = "Ctrl+B" },
    { cmd = "fullscreen", label = "Tam ekran", key = "F" },
  } },
  { id = "track", label = "Takip", items = {
    { cmd = "toggle", label = "Takip", key = "T" },
    { cmd = "release", label = "Hedefi bırak", key = "X" },
    { sep = true },
    { cmd = "offline", label = "Offline oynatma", key = "O" },
    { sep = true },
    { cmd = "src_auto", label = "Kaynak: Otomatik" },
    { cmd = "src_live", label = "Kaynak: Canlı" },
    { cmd = "src_scanned", label = "Kaynak: Taranmış iz" },
    { sep = true },
    { cmd = "autoscan", label = "Otomatik tarama", todo = true },
    { cmd = "bodypart", label = "Vücut parçası (değiştir)", key = "G" },
    { sep = true },
    { cmd = "target_add", label = "Hedef ekle (sağ tık ekler)", key = "A" },
    { cmd = "target_remove", label = "Son hedefi çıkar" },
  } },
  -- B3: vücut parçası seçimi (Takip menüsünün alt menüsü yerine ayrı başlık: liste uzun, küçük
  -- pencerede Takip listesine sığmaz). Sıra/adlar tracker/parts.py CYCLE ile aynı (testle denetlenir).
  { id = "part", label = "Parça", items = {
    { cmd = "part_auto", label = "Serbest (tıklanan eklem)" },
    { cmd = "part_person", label = "Tüm kişi" },
    { sep = true },
    { cmd = "part_head", label = "Baş" },
    { cmd = "part_torso", label = "Gövde" },
    { cmd = "part_left_arm", label = "Sol kol" },
    { cmd = "part_right_arm", label = "Sağ kol" },
    { cmd = "part_left_leg", label = "Sol bacak" },
    { cmd = "part_right_leg", label = "Sağ bacak" },
    { cmd = "part_legs", label = "Bacaklar" },
    { cmd = "part_left_foot", label = "Sol ayak" },
    { cmd = "part_right_foot", label = "Sağ ayak" },
    { cmd = "part_feet", label = "Ayaklar" },
    { cmd = "part_hands", label = "Eller" },
    { sep = true },
    { cmd = "part_next", label = "Sonraki parça", key = "G" },
  } },
  { id = "tools", label = "Araçlar", items = {
    { cmd = "scan", label = "Offline taramayı başlat", todo = true },
    { cmd = "scan_cancel", label = "Taramayı durdur", todo = true },
    { cmd = "bench", label = "Kıyaslama", todo = true },
    { cmd = "models", label = "Modeller klasörü", todo = true },
  } },
  { id = "settings", label = "Ayarlar", items = {
    { cmd = "speed_up", label = "Tepki hızı +", key = "Ctrl+→" },
    { cmd = "speed_down", label = "Tepki hızı -", key = "Ctrl+←" },
    { cmd = "lead_up", label = "Öngörü +", key = "Ctrl+PgUp" },
    { cmd = "lead_down", label = "Öngörü -", key = "Ctrl+PgDn" },
    { cmd = "fill_up", label = "Doluluk +", key = "Ctrl+↑" },
    { cmd = "fill_down", label = "Doluluk -", key = "Ctrl+↓" },
    { sep = true },
    { cmd = "save", label = "Ayarları kaydet", key = "Ctrl+S" },
    { cmd = "defaults", label = "Varsayılanlara dön", key = "Ctrl+0" },
    { cmd = "dialog", label = "Ayar penceresi…", todo = true },
  } },
  { id = "help", label = "Yardım", items = {
    { cmd = "update", label = "Güncellemeleri denetle…" },
    { sep = true },
    { cmd = "status", label = "Durum özeti", key = "H" },
    { cmd = "shortcuts", label = "Kısayollar", todo = true },
    { cmd = "about", label = "Hakkında", todo = true },
  } },
}

local function nchars(s)
  local n = 0
  for _ in s:gmatch("[^\128-\191]") do n = n + 1 end -- UTF-8 karakter sayısı
  return n
end
M.nchars = nchars

local function inside(r, x, y)
  return r and x >= r.x and x < r.x + r.w and y >= r.y and y < r.y + r.h
end

-- Saf yerleşim: çubuk başlıkları. W,H pencere pikseli. -> rects, bh (çubuk yüksekliği), fs.
-- minh: en küçük çubuk yüksekliği (px). Küçük pencerede (H<700) çubuk küçülmez, biraz büyür; `hd` ekran ölçeği.
function M.bar_layout(W, H, menus, hd)
  hd = math.max(1, math.min(hd or 1, 3))
  local b = H / 700
  local boost = (b >= 1) and 1 or math.min(1 + (1 - b) * 0.5, 1.35)
  local function build(bh)
    local fs = math.floor(bh * 0.5)
    local rects, x = {}, 0
    for i, m in ipairs(menus) do
      local w = math.floor(nchars(m.label) * fs * 0.62 + bh * 0.8)
      rects[i] = { x = x, y = 0, w = w, h = bh }
      x = x + w
    end
    return rects, fs, x
  end
  local bh = math.max(math.floor(32 * boost * hd), math.floor(H * 0.05))
  local rects, fs, total = build(bh)
  if total > W and W > 0 then -- başlıklar sığmıyorsa orantılı küçült (en az 24 px)
    bh = math.max(24, math.floor(bh * W / total))
    rects, fs = build(bh)
  end
  return rects, bh, fs
end

-- Saf yerleşim: açılır liste (başlığın altı, pencereye sığacak şekilde kıstırılır).
function M.drop_layout(W, bh, fs, items, hx, labels, keys)
  local wide = 0
  for i = 1, #items do
    local n = nchars(labels[i] or "") + (keys[i] ~= "" and nchars(keys[i]) + 4 or 0)
    if n > wide then wide = n end
  end
  local w = math.floor(wide * fs * 0.62 + bh * 0.9)
  local x = math.max(0, math.min(hx, W - w))
  local rects, y = {}, bh
  for i, it in ipairs(items) do
    local h = it.sep and math.floor(bh * 0.3) or bh
    rects[i] = { x = x, y = y, w = w, h = h }
    y = y + h
  end
  return rects, { x = x, y = bh, w = w, h = y - bh }
end

function M.new(ctx)
  local e = { visible = true, fullscreen = false, open = nil, sel = nil, hover = nil, W = 0, H = 0,
              hrects = {}, drects = {}, dbox = nil, bh = 0, fs = 0, labels = {}, keys = {}, hd = 1 }

  local function st(it)
    local s = (not it.todo and ctx.state and ctx.state(it.cmd)) or {}
    local en = (not it.todo) and (s.enabled ~= false)
    return en, s.checked and true or false, s.label or it.label
  end

  function e:resize(W, H)
    if W == self.W and H == self.H and self.hrects[1] then return end
    self.W, self.H = W, H
    self.hrects, self.bh, self.fs = M.bar_layout(W, H, M.MENUS, self.hd)
    self:relayout()
  end

  function e:relayout() -- açık menünün listesi (etiketler durumla değişebilir)
    self.drects, self.dbox = {}, nil
    if not self.open then return end
    local m = M.MENUS[self.open]
    local labels, keys = {}, {}
    for i, it in ipairs(m.items) do
      if not it.sep then
        local _, _, lab = st(it)
        labels[i], keys[i] = "    " .. lab, it.key or ""
      else labels[i], keys[i] = "", "" end
    end
    self.drects, self.dbox = M.drop_layout(self.W, self.bh, self.fs, m.items,
      self.hrects[self.open].x, labels, keys)
  end

  local function selectable(it) return not it.sep and (st(it)) end
  local function step(self, dir) -- seçimi atlayarak (ayraç/gri) ilerlet
    local items = M.MENUS[self.open].items
    local n = #items
    local i = self.sel or (dir > 0 and 0 or n + 1)
    for _ = 1, n do
      i = i + dir
      if i < 1 then i = n elseif i > n then i = 1 end
      if selectable(items[i]) then self.sel = i return end
    end
  end

  function e:is_open() return self.open ~= nil end
  function e:over(x, y) -- imleç çubuğun/açık listenin üstünde mi (sol tık bağı için)
    if not self.visible then return false end
    if inside({ x = 0, y = 0, w = self.W, h = self.bh }, x, y) then return true end
    return self.dbox ~= nil and inside(self.dbox, x, y)
  end

  function e:close() local was = self.open ~= nil; self.open, self.sel = nil, nil; self:relayout(); return was end
  -- Çubuk pencereli modda HEP açık; yalnız tam ekranda fare hareketinde görünür, zamanlayıcıyla gizlenir.
  function e:hide()
    if self.fullscreen and not self.open then self.visible, self.hover = false, nil end
  end
  function e:set_fullscreen(fs)
    self.fullscreen = fs and true or false
    if self.fullscreen then
      if not self.open then self.visible, self.hover = false, nil end
    else
      self.visible = true
    end
  end

  local function open_menu(self, i)
    self.open, self.sel, self.visible = i, nil, true
    self:relayout()
  end

  -- Fare hareketi (tam ekranda çubuğu gösterir). Döner: çizim değişti mi.
  function e:mouse(x, y)
    local ov, oh, os_ = self.visible, self.hover, self.sel
    self.visible = true -- her fare/dokunma hareketinde çubuk görünür; tam ekranda zamanlayıcı gizler
    if not self.visible then return false end
    local hh
    for i, r in ipairs(self.hrects) do if inside(r, x, y) then hh = i end end
    self.hover = hh
    if hh and self.open and self.open ~= hh then open_menu(self, hh) end -- açıkken başlıklar arası geçiş
    if self.open and self.dbox and inside(self.dbox, x, y) then
      for i, r in ipairs(self.drects) do
        if inside(r, x, y) and selectable(M.MENUS[self.open].items[i]) then self.sel = i end
      end
    end
    return ov ~= self.visible or oh ~= self.hover or os_ ~= self.sel
  end

  local function activate(self)
    local it = self.open and self.sel and M.MENUS[self.open].items[self.sel]
    if not it or not selectable(it) then return false end
    self:close()
    ctx.run(it.cmd)
    return true
  end

  -- Sol tık. Döner: tık motor tarafından tüketildi mi.
  function e:click(x, y)
    if not self.visible then return false end
    for i, r in ipairs(self.hrects) do
      if inside(r, x, y) then
        if self.open == i then self:close() else open_menu(self, i) end
        return true
      end
    end
    if self.open then
      if self.dbox and inside(self.dbox, x, y) then
        for i, r in ipairs(self.drects) do
          if inside(r, x, y) then self.sel = i; activate(self) break end -- ayraç/gri: yalnız kapanmaz
        end
        return true
      end
      self:close() -- dışarı tık: kapat
      return inside({ x = 0, y = 0, w = self.W, h = self.bh }, x, y)
    end
    return false
  end

  -- Klavye: "f10", "left", "right", "up", "down", "enter", "esc". Döner: tüketildi mi.
  function e:key(k)
    if k == "f10" then
      if self.open then self:close() else open_menu(self, 1); step(self, 1) end
      return true
    end
    if not self.open then return false end
    local n = #M.MENUS
    if k == "esc" then self:close() return true end
    if k == "left" or k == "right" then
      open_menu(self, (self.open - 1 + (k == "right" and 1 or -1)) % n + 1)
      step(self, 1)
      return true
    end
    if k == "down" then step(self, 1) return true end
    if k == "up" then step(self, -1) return true end
    if k == "enter" then return activate(self) or true end
    return false
  end

  -- ASS olayları (res = pencere pikseli). Görünmüyorsa "".
  function e:render()
    if not self.visible or self.bh == 0 then return "" end
    local ev, fs, bh = {}, self.fs, self.bh
    local function box(r, col, a)
      ev[#ev + 1] = string.format(
        "{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H%s&\\1a&H%s&\\p1}m 0 0 l %d 0 l %d %d l 0 %d{\\p0}",
        r.x, r.y, col, a, r.w, r.w, r.h, r.h)
    end
    local function text(x, y, an, col, s)
      ev[#ev + 1] = string.format("{\\an%d\\pos(%d,%d)\\fnTahoma\\fs%d\\bord0\\shad0\\1c&H%s&}%s", an, x, y, fs, col, s)
    end
    -- MPC-HC klasik koyu palet (ASS: BBGGRR); alt çubukla (mpcbar.lua) aynı: 262626 zemin, mavi seçili
    box({ x = 0, y = 0, w = self.W, h = bh }, "262626", "00")
    box({ x = 0, y = bh - 1, w = self.W, h = 1 }, "555555", "00") -- ince alt çizgi
    for i, m in ipairs(M.MENUS) do
      local r = self.hrects[i]
      if self.open == i then box(r, "D59B5B", "00") elseif self.hover == i then box(r, "464646", "00") end
      text(r.x + math.floor(r.w / 2), math.floor(bh / 2), 5, "E6E6E6", m.label)
    end
    if self.open and self.dbox then
      local b = self.dbox
      box({ x = b.x + 3, y = b.y + 3, w = b.w, h = b.h }, "000000", "A0") -- gölge
      box({ x = b.x - 1, y = b.y - 1, w = b.w + 2, h = b.h + 2 }, "555555", "00") -- çerçeve
      box(b, "262626", "00")
      for i, it in ipairs(M.MENUS[self.open].items) do
        local r = self.drects[i]
        if it.sep then
          box({ x = r.x + 8, y = r.y + math.floor(r.h / 2), w = r.w - 16, h = 1 }, "555555", "00")
        else
          local en, chk, lab = st(it)
          if self.sel == i and en then box(r, "D59B5B", "00") end
          local col = en and "E6E6E6" or "7A7A7A" -- pasif: gri
          if chk then text(r.x + math.floor(bh * 0.2), r.y + math.floor(r.h / 2), 4, col, "✓") end
          text(r.x + math.floor(bh * 0.6), r.y + math.floor(r.h / 2), 4, col, lab)
          if it.key then text(r.x + r.w - math.floor(bh * 0.3), r.y + math.floor(r.h / 2), 6, en and "B0B0B0" or "606060", it.key) end
        end
      end
    end
    return table.concat(ev, "\n")
  end

  return e
end

return M
