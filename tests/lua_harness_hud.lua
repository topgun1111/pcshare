-- Parça 13: HUD etiketi ve ayar tuşları (Ortam: TRACK_LUA, VIDEO, DATA_LUA).
local dir = (arg and arg[0] or ""):match("^(.*)[/\\]") or "."
local reg = dofile(dir .. "/lua_mp_stub.lua")
dofile(os.getenv("TRACK_LUA"))
local function out(...) print(table.concat({ ... }, "\t")) end
local function last_hud() return reg.ov[0] or "<yok>" end -- HUD katmanı (0)

reg.msgs["track-hud"]("Takip: ID 5")
out("HUD", last_hud())
reg.msgs["track-hud"]("a{b}\\c")
out("ESC", last_hud())
reg.msgs["track-hud"]("")
out("EMPTY", last_hud())
reg.msgs["track-hud"]("Takip: ID 5")
reg.bind.hud()
out("HIDDEN", last_hud())
reg.bind.hud()
out("SHOWN", last_hud())
-- tuşlar → script-message
for _, k in ipairs({ "fill-up", "fill-down", "speed-up", "speed-down", "lead-up", "lead-down",
  "settings-reset", "settings-save", "status" }) do
  reg.cmds = {}
  reg.bind[k]()
  out("KEY", k, reg.cmds[1])
end
-- canlı mod kapanınca etiket silinir
reg.bind.toggle()
reg.msgs["track-hud"]("Takip: ID 5")
reg.bind.toggle()
out("TOGGLE_OFF", last_hud())
-- offline açıkken etiket offline ID gösterir
reg.bind.offline()
out("OFFLINE", last_hud())

-- Sol üst bilgi bloğu: 3. argüman çok satırlı bilgi; zoom satırı Lua'dan eklenir
reg.bind.toggle() -- takibi tekrar aç (OFFLINE öncesi toggle kapattı)
reg.bind.offline() -- offline'ı kapat
reg.msgs["track-hud"]("Takip: ID 5", "1", "Model: yolox_s.onnx · DirectML\nHız: 9.8 kare/sn")
out("INFO", last_hud())
reg.props["video-zoom"] = 1
reg.obs["video-zoom"]("video-zoom", 1)
out("INFOZOOM", last_hud())
reg.bind.hud()
out("INFOHIDDEN", last_hud())
reg.bind.hud()
-- Çift sol tık: düğme üstünde değilse oynat/duraklat
reg.cmds = {}
reg.bind.dblclick()
out("DBL", reg.cmds[1] or "-")
