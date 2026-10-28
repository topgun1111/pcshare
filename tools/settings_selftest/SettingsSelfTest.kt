// JVM self-test of core/settings/SettingsSchema.kt (export format, compatibility gate). No Android classes needed, only org.json.
// Run (CI job settings-selftest, or by hand):
//   javac -d out/json JSON-java/src/main/java/org/json/*.java        (any org.json build works)
//   kotlinc app/src/main/java/com/lanshare/app/core/settings/SettingsSchema.kt tools/settings_selftest/SettingsSelfTest.kt -cp out/json -include-runtime -d st.jar
//   java -cp st.jar:out/json SettingsSelfTestKt          -> prints "... ok, 0 FAIL"
import com.lanshare.app.core.settings.SettingsSchema
import com.lanshare.app.core.settings.SettingsSchema.Result
import org.json.JSONArray
import org.json.JSONObject

var ok = 0; var fail = 0
fun t(name: String, cond: Boolean) { if (cond) ok++ else { fail++; println("FAIL $name") } }

fun sample(): Map<String, Any?> {
    val m = HashMap<String, Any?>()
    m["cfg/name"] = "Pixel 8"; m["cfg/name_custom"] = true; m["cfg/us_on"] = true; m["cfg/us_c_max"] = 7; m["cfg/us_ai_th"] = 0.3
    m["cfg/pins"] = JSONArray().put("100.64.0.5").put("192.168.1.9")
    m["cfg/smb"] = JSONArray().put(JSONObject().put("id", "smb:abc123").put("host", "pc").put("user", "u").put("password", "pwé\"x"))
    m["pref/editor/size"] = 16.0; m["pref/editor/mono"] = false; m["pref/ls_ui/iface"] = 110; m["pref/office_view/mode"] = "exact"
    m["pref/lanshare_pe/port"] = 2121; m["web/ls_g"] = "{\"view\":\"grid\",\"cols\":3}"; m["web/ls_theme"] = "dark"
    return m
}
fun build(m: Map<String, Any?> = sample(), secrets: Boolean = false) = SettingsSchema.build(m, "2.31", 12345L, "Pixel 8", 1_700_000_000_000L, secrets)
fun reseal(o: JSONObject): String { o.put("checksum", SettingsSchema.checksum(o.getJSONObject("entries"))); return o.toString() }
fun refused(r: Result, kind: String) = r is Result.Refused && r.kind == kind

fun main() {
    // registry
    t("ids unique", SettingsSchema.BY_ID.size == SettingsSchema.ALL.size)
    t("web keys listed", SettingsSchema.WEB_KEYS.size == SettingsSchema.ALL.count { it.src == SettingsSchema.Src.WEB } && SettingsSchema.WEB_KEYS.isNotEmpty())
    t("registry has main + small settings", listOf("cfg/smb", "cfg/us_on", "pref/editor/awake", "pref/ls_ui/iface", "web/ls_theme").all { it in SettingsSchema.BY_ID })

    // round trip
    val txt = build()
    val r = SettingsSchema.parse(txt)
    t("round trip ok", r is Result.Ok)
    if (r is Result.Ok) {
        val v = r.parsed.values
        t("string", v["cfg/name"] == "Pixel 8"); t("bool", v["cfg/name_custom"] == true && v["pref/editor/mono"] == false)
        t("int", (v["cfg/us_c_max"] as Number).toInt() == 7 && (v["pref/ls_ui/iface"] as Number).toInt() == 110)
        t("integral double survives (16.0 -> 16)", (v["pref/editor/size"] as Number).toDouble() == 16.0)
        t("fraction", (v["cfg/us_ai_th"] as Number).toDouble() == 0.3)
        t("list", (v["cfg/pins"] as JSONArray).length() == 2 && (v["cfg/pins"] as JSONArray).getString(0) == "100.64.0.5")
        t("password text kept", ((v["cfg/smb"] as JSONArray).getJSONObject(0).getString("password")) == "pwé\"x")
        t("web raw json string", v["web/ls_g"] == "{\"view\":\"grid\",\"cols\":3}")
        t("unset = null", v["cfg/root_on"] == null && v["pref/editor/awake"] == null)
        t("meta", r.parsed.meta.versionName == "2.31" && r.parsed.meta.device == "Pixel 8" && !r.parsed.meta.secrets && r.parsed.meta.schema == SettingsSchema.SCHEMA)
        t("all ids present", v.size == SettingsSchema.ALL.size)
    }
    t("secrets flag", (SettingsSchema.parse(build(secrets = true)) as Result.Ok).parsed.meta.secrets)

    // not a settings file
    t("garbage", refused(SettingsSchema.parse("hello"), "NOT_SETTINGS"))
    t("other json", refused(SettingsSchema.parse("{\"a\":1}"), "NOT_SETTINGS"))
    t("empty", refused(SettingsSchema.parse(""), "NOT_SETTINGS"))
    t("other format", refused(SettingsSchema.parse(JSONObject(txt).put("format", "x").toString()), "NOT_SETTINGS"))

    // damaged / hand edited
    t("tampered value", refused(SettingsSchema.parse(JSONObject(txt).apply { getJSONObject("entries").put("cfg/us_on", false) }.toString()), "DAMAGED"))
    t("truncated", refused(SettingsSchema.parse(txt.dropLast(40)), "NOT_SETTINGS"))

    // NEWER file (made by a newer app): schema higher and/or unknown settings
    val newer = JSONObject(txt).put("schema", SettingsSchema.SCHEMA + 1)
    val rn = SettingsSchema.parse(newer.toString())
    t("newer schema refused", refused(rn, "NEWER") && (rn as Result.Refused).reasons.any { "newer" in it })
    val extra = JSONObject(txt); extra.getJSONObject("entries").put("cfg/future_setting", true)
    val re = SettingsSchema.parse(reseal(extra))
    t("unknown setting refused", refused(re, "NEWER") && (re as Result.Refused).reasons.any { "future_setting" in it })

    // OLDER file imported by a NEWER app: settings added later are missing -> warning + refusal (the owner's rule)
    val old = JSONObject(txt); old.getJSONObject("entries").remove("pref/editor/awake"); old.getJSONObject("entries").remove("web/ls_dual")
    val ro = SettingsSchema.parse(reseal(old))
    t("older (missing settings) refused", refused(ro, "OLDER"))
    t("older warning names them", ro is Result.Refused && ro.reasons.any { "Text editor: keep screen on" in it && "Two-pane mode" in it && "2 settings" in it })
    val oldSchema = JSONObject(txt).put("schema", SettingsSchema.MIN_COMPAT - 1)
    t("below MIN_COMPAT refused", refused(SettingsSchema.parse(oldSchema.toString()), "OLDER"))
    t("nothing returned when refused", ro !is Result.Ok)

    // value validation
    fun withVal(id: String, v: Any?): Result { val o = JSONObject(txt); o.getJSONObject("entries").put(id, v ?: JSONObject.NULL); return SettingsSchema.parse(reseal(o)) }
    t("bool wrong type", refused(withVal("cfg/us_on", "yes"), "INVALID"))
    t("int wrong type", refused(withVal("cfg/us_c_max", "7"), "INVALID"))
    t("int fraction", refused(withVal("cfg/us_c_max", 7.5), "INVALID"))
    t("int out of range", refused(withVal("pref/ls_ui/iface", 500), "INVALID"))
    t("port out of range", refused(withVal("pref/lanshare_pe/port", 80), "INVALID"))
    t("float out of range", refused(withVal("cfg/us_ai_th", 2.0), "INVALID"))
    t("enum bad", refused(withVal("pref/office_view/mode", "weird"), "INVALID"))
    t("list wrong", refused(withVal("cfg/pins", "1.2.3.4"), "INVALID"))
    t("list element wrong", refused(withVal("cfg/pins", JSONArray().put(5)), "INVALID"))
    t("objlist element wrong", refused(withVal("cfg/smb", JSONArray().put("x")), "INVALID"))
    t("null allowed", withVal("cfg/us_on", null) is Result.Ok)
    t("int 100.0 allowed", withVal("pref/ls_ui/iface", 100.0) is Result.Ok)

    // checksum is independent of key order
    val shuffled = JSONObject(); val ks = SettingsSchema.ALL.map { it.id }.reversed(); val src = JSONObject(txt).getJSONObject("entries")
    for (k in ks) shuffled.put(k, src.get(k))
    t("checksum order independent", SettingsSchema.checksum(shuffled) == SettingsSchema.checksum(src))

    println("settings selftest: $ok ok, $fail FAIL")
    if (fail > 0) System.exit(1)
}
