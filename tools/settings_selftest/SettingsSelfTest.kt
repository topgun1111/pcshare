// JVM self-test of core/settings/SettingsSchema.kt (export format, per-field compatible import). No Android classes needed, only org.json.
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

    // PER-FIELD IMPORT (owner, 2026-10-28): compatible fields are taken, incompatible ones are skipped and reported, nothing else is refused
    fun ok(r: Result) = (r as? Result.Ok)?.parsed
    val newer = ok(SettingsSchema.parse(JSONObject(txt).put("schema", SettingsSchema.SCHEMA + 1).toString()))
    t("newer schema still imports compatible fields", newer != null && newer.values.size == SettingsSchema.ALL.size)
    val extra = JSONObject(txt); extra.getJSONObject("entries").put("cfg/future_setting", true); extra.getJSONObject("entries").put("web/future_page_key", "x")
    val pe = ok(SettingsSchema.parse(reseal(extra)))
    t("unknown settings skipped + reported", pe != null && pe.skipped.size == 2 && pe.skipped.any { it.label == "future_setting" } && "cfg/future_setting" !in pe.values)
    t("known settings of that file still imported", pe != null && pe.values["cfg/name"] == "Pixel 8" && pe.values.size == SettingsSchema.ALL.size)

    // OLDER file, imported by a newer app: settings that did not exist are simply absent -> left untouched, the rest is imported
    val old = JSONObject(txt); old.getJSONObject("entries").remove("pref/editor/awake"); old.getJSONObject("entries").remove("web/ls_dual")
    val po = ok(SettingsSchema.parse(reseal(old)))
    t("older file imports", po != null)
    t("missing ones not applied", po != null && "pref/editor/awake" !in po.values && "web/ls_dual" !in po.values && po.values.size == SettingsSchema.ALL.size - 2)
    t("missing ones listed as unchanged", po != null && po.untouched.sorted() == listOf("Text editor: keep screen on", "Two-pane mode").sorted())
    t("nothing skipped for a clean older file", po != null && po.skipped.isEmpty())
    t("schema 0 is not a settings file", refused(SettingsSchema.parse(JSONObject(txt).put("schema", 0).toString()), "NOT_SETTINGS"))

    // value validation: a bad value skips ONLY that field
    fun withVal(id: String, v: Any?): SettingsSchema.Parsed? { val o = JSONObject(txt); o.getJSONObject("entries").put(id, v ?: JSONObject.NULL); return ok(SettingsSchema.parse(reseal(o))) }
    fun skippedOnly(id: String, v: Any?): Boolean { val p = withVal(id, v) ?: return false; return id !in p.values && p.skipped.size == 1 && p.skipped[0].label == SettingsSchema.BY_ID.getValue(id).label && p.values.size == SettingsSchema.ALL.size - 1 }
    t("bool wrong type skipped", skippedOnly("cfg/us_on", "yes"))
    t("int wrong type skipped", skippedOnly("cfg/us_c_max", "7"))
    t("int fraction skipped", skippedOnly("cfg/us_c_max", 7.5))
    t("int out of range skipped", skippedOnly("pref/ls_ui/iface", 500))
    t("port out of range skipped", skippedOnly("pref/lanshare_pe/port", 80))
    t("float out of range skipped", skippedOnly("cfg/us_ai_th", 2.0))
    t("enum bad skipped", skippedOnly("pref/office_view/mode", "weird"))
    t("list wrong skipped", skippedOnly("cfg/pins", "1.2.3.4"))
    t("list element wrong skipped", skippedOnly("cfg/pins", JSONArray().put(5)))
    t("objlist element wrong skipped", skippedOnly("cfg/smb", JSONArray().put("x")))
    t("null allowed", withVal("cfg/us_on", null)?.values?.containsKey("cfg/us_on") == true)
    t("int 100.0 allowed", withVal("pref/ls_ui/iface", 100.0) != null && withVal("pref/ls_ui/iface", 100.0)!!.skipped.isEmpty())

    // nothing usable -> refused
    val alien = JSONObject(txt); val ae = JSONObject(); ae.put("cfg/x1", 1); ae.put("web/x2", "y"); alien.put("entries", ae)
    t("no compatible field refused", refused(SettingsSchema.parse(reseal(alien)), "NOTHING_COMPATIBLE"))
    val allBad = JSONObject(txt); allBad.put("entries", JSONObject().put("cfg/us_on", "nope"))
    t("only invalid fields refused", refused(SettingsSchema.parse(reseal(allBad)), "NOTHING_COMPATIBLE"))

    // checksum is independent of key order
    val shuffled = JSONObject(); val ks = SettingsSchema.ALL.map { it.id }.reversed(); val src = JSONObject(txt).getJSONObject("entries")
    for (k in ks) shuffled.put(k, src.get(k))
    t("checksum order independent", SettingsSchema.checksum(shuffled) == SettingsSchema.checksum(src))

    println("settings selftest: $ok ok, $fail FAIL")
    if (fail > 0) System.exit(1)
}
