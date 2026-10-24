package com.lanshare.app.core.usearch

/**
 * File kinds for the kind: filter of universal search (chapter 16.5). Pure JVM, no android imports.
 * The kind of a file comes from its extension only; FOLDER and FILE are decided by the entry itself.
 */
enum class Kind { IMAGE, VIDEO, AUDIO, DOC, TEXT, ARCHIVE, FOLDER, FILE }

object Kinds {
    private fun words(s: String): HashSet<String> = HashSet(s.split(' '))

    private val image = words("jpg jpeg jpe png gif webp bmp heic heif tif tiff svg ico avif dng raw cr2 nef arw")
    private val video = words("mp4 mkv avi mov wmv flv webm 3gp 3g2 m4v mpg mpeg mts m2ts ts vob ogv")
    private val audio = words("mp3 wav flac aac m4a ogg oga opus wma amr mid midi aiff ape")
    private val doc = words("doc docx dot dotx xls xlsx xlsm xlt xltx ppt pptx pps ppsx odt ods odp rtf pdf epub")
    private val text = words("txt md markdown csv tsv json xml html htm xhtml css log ini cfg conf properties yaml yml toml srt vtt ass lrc kt kts java py js jsx tsx php c h cpp hpp cs go rs rb sh bat cmd ps1 sql gradle tex rst")
    private val archive = words("zip rar 7z tar gz tgz bz2 tbz2 xz txz lz zst cab iso jar")

    /** Lower-case extension without the dot; empty when there is none ("a.", "noext"). ".nomedia" gives "nomedia". */
    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun kindOfExt(ext: String): Kind? = when {
        image.contains(ext) -> Kind.IMAGE
        video.contains(ext) -> Kind.VIDEO
        audio.contains(ext) -> Kind.AUDIO
        doc.contains(ext) -> Kind.DOC
        text.contains(ext) -> Kind.TEXT
        archive.contains(ext) -> Kind.ARCHIVE
        else -> null
    }

    fun matches(k: Kind, isDir: Boolean, ext: String): Boolean = when (k) {
        Kind.FOLDER -> isDir
        Kind.FILE -> !isDir
        else -> !isDir && kindOfExt(ext) == k
    }

    /** Name typed after "kind:"; null when unknown. */
    fun parse(s: String): Kind? = when (s.trim().lowercase()) {
        "image", "images", "img", "picture", "pictures", "photo", "photos" -> Kind.IMAGE
        "video", "videos" -> Kind.VIDEO
        "audio", "music", "sound" -> Kind.AUDIO
        "doc", "docs", "document", "documents" -> Kind.DOC
        "text", "code" -> Kind.TEXT
        "archive", "archives" -> Kind.ARCHIVE
        "folder", "folders", "dir", "directory" -> Kind.FOLDER
        "file", "files" -> Kind.FILE
        else -> null
    }
}
