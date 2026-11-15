-- Takipli Oynat: Dosya menüsü yardımcıları (M2). SAF mantık: `mp`'ye dokunmaz, texlua ile test edilir.
-- Dosya/klasör/URL diyalogları PowerShell + WinForms ile açılır (Windows'ta her makinede var; gömülü
-- Python'da tkinter YOK). track.lua betiği `subprocess` ile asenkron çalıştırır, stdout = seçilen yol.
local M = {}

M.MAX_RECENT = 10
M.EXTS = { "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp" }

function M.is_url(s) return type(s) == "string" and s:match("^%a[%w+.-]*://%S+") ~= nil end

-- "C:\a\b.mp4" → "C:\a"; "/x/y.mp4" → "/x"; ayraç yoksa nil
function M.parent_dir(p)
  if type(p) ~= "string" or M.is_url(p) then return nil end
  local d = p:match("^(.*)[/\\][^/\\]*$")
  if d == "" then return nil end
  return d
end

-- Durum: { dir = son klasör | nil, recent = { yol, ... } (en yeni başta) }. Yeni tablo döner.
function M.add_recent(st, path, is_dir)
  local out = { dir = st and st.dir or nil, recent = { path } }
  for _, p in ipairs(st and st.recent or {}) do
    if #out.recent >= M.MAX_RECENT then break end
    if p ~= path then out.recent[#out.recent + 1] = p end
  end
  if is_dir then out.dir = path else out.dir = M.parent_dir(path) or out.dir end
  return out
end

-- Diskten okunan (güvenilmeyen) durumu temizle
function M.sanitize(doc)
  local st = { dir = nil, recent = {} }
  if type(doc) ~= "table" then return st end
  if type(doc.dir) == "string" and doc.dir ~= "" then st.dir = doc.dir end
  if type(doc.recent) == "table" then
    for _, p in ipairs(doc.recent) do
      if type(p) == "string" and p ~= "" and #st.recent < M.MAX_RECENT then st.recent[#st.recent + 1] = p end
    end
  end
  return st
end

-- PowerShell tek tırnaklı dize
function M.ps_quote(s) return "'" .. tostring(s):gsub("'", "''") .. "'" end

local HEAD = "$ErrorActionPreference='Stop';"
  .. "[Console]::OutputEncoding=New-Object Text.UTF8Encoding $false;"
  .. "Add-Type -AssemblyName System.Windows.Forms;Add-Type -AssemblyName System.Drawing;"
  .. "$o=New-Object Windows.Forms.Form;$o.TopMost=$true;"

local function emit(var) return "[Console]::Out.Write(" .. var .. ")" end

function M.filter()
  local pats = {}
  for i, e in ipairs(M.EXTS) do pats[i] = "*." .. e end
  return "Video dosyaları|" .. table.concat(pats, ";") .. "|Tüm dosyalar|*.*"
end

function M.open_script(dir)
  local s = HEAD .. "$d=New-Object Windows.Forms.OpenFileDialog;$d.Title='Video aç';$d.Filter="
    .. M.ps_quote(M.filter()) .. ";"
  if dir and dir ~= "" then s = s .. "if(Test-Path -LiteralPath " .. M.ps_quote(dir) .. "){$d.InitialDirectory=" .. M.ps_quote(dir) .. "};" end
  return s .. "if($d.ShowDialog($o) -eq 'OK'){" .. emit("$d.FileName") .. "}"
end

function M.folder_script(dir)
  local s = HEAD .. "$d=New-Object Windows.Forms.FolderBrowserDialog;$d.Description='Video klasörü seç';"
  if dir and dir ~= "" then s = s .. "if(Test-Path -LiteralPath " .. M.ps_quote(dir) .. "){$d.SelectedPath=" .. M.ps_quote(dir) .. "};" end
  return s .. "if($d.ShowDialog($o) -eq 'OK'){" .. emit("$d.SelectedPath") .. "}"
end

function M.url_script()
  return "$ErrorActionPreference='Stop';[Console]::OutputEncoding=New-Object Text.UTF8Encoding $false;"
    .. "Add-Type -AssemblyName Microsoft.VisualBasic;"
    .. "$u=[Microsoft.VisualBasic.Interaction]::InputBox('Video adresi (http/https/rtsp ...):','URL aç','');"
    .. emit("$u")
end

-- Son açılanlar: küçük liste penceresi; çift tık ya da Enter seçer, Esc/kapat = iptal.
function M.recent_script(list)
  local q = {}
  for i, p in ipairs(list) do q[i] = M.ps_quote(p) end
  return HEAD .. "$f=New-Object Windows.Forms.Form;$f.Text='Son açılanlar';$f.StartPosition='CenterScreen';"
    .. "$f.Width=780;$f.Height=420;$f.TopMost=$true;$f.KeyPreview=$true;"
    .. "$l=New-Object Windows.Forms.ListBox;$l.Dock='Fill';$l.Font=New-Object Drawing.Font('Segoe UI',11);"
    .. "@(" .. table.concat(q, ",") .. ")|ForEach-Object{[void]$l.Items.Add($_)};$f.Controls.Add($l);"
    .. "$pick={if($l.SelectedItem){$f.Tag=[string]$l.SelectedItem;$f.Close()}};"
    .. "$l.Add_DoubleClick($pick);"
    .. "$f.Add_KeyDown({if($_.KeyCode -eq 'Return'){& $pick}elseif($_.KeyCode -eq 'Escape'){$f.Close()}});"
    .. "$f.Add_Shown({$f.Activate();if($l.Items.Count){$l.SelectedIndex=0}});"
    .. "[void]$f.ShowDialog();if($f.Tag){" .. emit("$f.Tag") .. "}"
end

-- Evet/Hayır sorusu: "yes" yazdırır (Hayır/kapat = boş çıktı).
function M.confirm_script(title, text)
  return "$ErrorActionPreference='Stop';[Console]::OutputEncoding=New-Object Text.UTF8Encoding $false;"
    .. "Add-Type -AssemblyName System.Windows.Forms;"
    .. "$o=New-Object Windows.Forms.Form;$o.TopMost=$true;$o.Opacity=0;$o.ShowInTaskbar=$false;$o.StartPosition='CenterScreen';$o.Show();"
    .. "$r=[Windows.Forms.MessageBox]::Show($o," .. M.ps_quote(text) .. "," .. M.ps_quote(title)
    .. ",'YesNo','Question');if($r -eq 'Yes'){" .. emit("'yes'") .. "}"
end

function M.ps_args(script)
  return { "powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden",
    "-Command", script }
end

-- Alt sürecin tüm stdout'u (JSON için; BOM atılır, satırlar korunur) ya da nil.
function M.clean_all(out)
  if type(out) ~= "string" then return nil end
  out = out:gsub("^\239\187\191", ""):gsub("^%s+", ""):gsub("%s+$", "")
  if out == "" then return nil end
  return out
end

-- Alt sürecin stdout'u → yol/adres ya da nil (BOM, CR/LF, boşluk atılır; çok satırsa ilk satır)
function M.clean(out)
  if type(out) ~= "string" then return nil end
  out = out:gsub("^\239\187\191", "")
  out = (out:match("^%s*([^\r\n]*)") or ""):gsub("%s+$", "")
  if out == "" then return nil end
  return out
end

-- Görünüm anlık görüntüsü (<video>.view.json): statik zoom/pan/döndürme. `.views.json` (offline
-- anahtar kareler) ile KARIŞMAZ ve onu ezmez.
function M.snapshot(zoom, px, py, rot)
  return { version = 1, zoom = zoom or 0, pan_x = px or 0, pan_y = py or 0, rotate = rot or 0 }
end

local function num(v, lo, hi)
  if type(v) ~= "number" or v ~= v or v < lo or v > hi then return nil end
  return v
end

-- → zoom, pan_x, pan_y, rotate ya da nil, sebep
function M.parse_snapshot(doc)
  if type(doc) ~= "table" then return nil, "geçersiz dosya" end
  if doc.version ~= 1 then return nil, "desteklenmeyen sürüm " .. tostring(doc.version) end
  local z, px, py = num(doc.zoom, -4, 8), num(doc.pan_x, -3, 3), num(doc.pan_y, -3, 3)
  local r = num(doc.rotate or 0, 0, 359)
  if not (z and px and py and r) then return nil, "değerler geçersiz" end
  return z, px, py, math.floor(r / 90 + 0.5) % 4 * 90
end

return M
