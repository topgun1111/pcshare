package com.lanshare.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Offline translation of the text read from a picture (ML Kit Translate + Language ID, see HANDOVER_OCR.md "Translate").
 * Language is detected on the device; Turkish -> English, anything else -> Turkish, or an explicit pair ([force]).
 * Each language pack (~30 MB) is downloaded once, then it works offline. All callbacks arrive on the main thread.
 */
class OcrTranslate {
    class Out(val src: String, val dst: String, val text: String)

    private val clients = HashMap<String, Translator>()
    private var identC: LanguageIdentifier? = null
    private fun ident() = identC ?: LanguageIdentification.getClient().also { identC = it }

    fun run(text: String, force: Pair<String, String>?, cb: (Result<Out>) -> Unit) {
        fun go(src: String, dst: String) {
            if (src == dst) { cb(Result.failure(Exception("Already in that language"))); return }
            val tr = clients.getOrPut("$src>$dst") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
            }
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    tr.translate(text)
                        .addOnSuccessListener { r -> cb(Result.success(Out(src, dst, r))) }
                        .addOnFailureListener { e -> cb(Result.failure(Exception("Cannot translate: " + (e.message ?: "error")))) }
                }
                .addOnFailureListener { e -> cb(Result.failure(Exception("Language pack not available (internet is needed once): " + (e.message ?: "")))) }
        }
        if (force != null) { go(force.first, force.second); return }
        ident().identifyLanguage(text.take(500))
            .addOnSuccessListener { code ->
                val src = if (code == "und") TranslateLanguage.ENGLISH else TranslateLanguage.fromLanguageTag(code)
                if (src == null) cb(Result.failure(Exception("This language is not supported")))
                else go(src, if (src == TranslateLanguage.TURKISH) TranslateLanguage.ENGLISH else TranslateLanguage.TURKISH)
            }
            .addOnFailureListener { go(TranslateLanguage.ENGLISH, TranslateLanguage.TURKISH) }
    }

    fun close() {
        for (t in clients.values) try { t.close() } catch (_: Exception) {}
        clients.clear()
        try { identC?.close() } catch (_: Exception) {}
        identC = null
    }

    companion object {
        /** OCR lines -> running text: a line break stays only after sentence-ending punctuation, other line wraps become spaces. */
        fun prep(s: String): String {
            val ls = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            for ((i, l) in ls.withIndex()) {
                sb.append(l)
                if (i < ls.size - 1) sb.append(if (l.last() in ".!?:;") '\n' else ' ')
            }
            return sb.toString().take(4000)
        }
    }
}
