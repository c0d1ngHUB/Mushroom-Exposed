package com.example.mushroomexposed

import java.io.File

data class HistoryEntry(
    val timestamp: String,
    val scientific: String,
    val german: String,
    val confidence: Float,
    val verdict: String,
    val lookalike: Boolean,
)

private fun escape(value: String): String {
    val sb = StringBuilder(value.length + 8)
    for (c in value) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.toString()
}

private fun unescape(value: String): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '\\' || i + 1 >= value.length) {
            sb.append(c)
            i++
            continue
        }
        when (val n = value[i + 1]) {
            '"', '\\', '/' -> { sb.append(n); i += 2 }
            'n' -> { sb.append('\n'); i += 2 }
            'r' -> { sb.append('\r'); i += 2 }
            't' -> { sb.append('\t'); i += 2 }
            'u' -> {
                if (i + 6 > value.length) { sb.append(c); i++; continue }
                sb.append(value.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

/** One JSON object per line, fixed key order: ts, sci, de, conf, verdict, lookalike. */
fun encode(entry: HistoryEntry): String = buildString {
    append("{\"ts\":\"").append(escape(entry.timestamp)).append('"')
    append(",\"sci\":\"").append(escape(entry.scientific)).append('"')
    append(",\"de\":\"").append(escape(entry.german)).append('"')
    append(",\"conf\":").append(entry.confidence)
    append(",\"verdict\":\"").append(escape(entry.verdict)).append('"')
    append(",\"lookalike\":").append(entry.lookalike)
    append('}')
}

private val FIELD = Regex(
    "\"(ts|sci|de|conf|verdict|lookalike)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?[0-9.]+)"
)

private fun unquote(value: String): String =
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1)
    else value

fun decode(line: String): HistoryEntry? {
    val trimmed = line.trim()
    if (trimmed.length < 2 || !trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
    val found = FIELD.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
    val ts = found["ts"] ?: return null
    val sci = found["sci"] ?: return null
    return try {
        HistoryEntry(
            timestamp = unescape(unquote(ts)),
            scientific = unescape(unquote(sci)),
            german = unescape(unquote(found["de"] ?: "\"\"")),
            confidence = (found["conf"] ?: return null).toFloat(),
            verdict = unescape(unquote(found["verdict"] ?: "\"\"")),
            lookalike = found["lookalike"] == "true",
        )
    } catch (e: NumberFormatException) {
        null
    }
}

/** Append-only JSONL store, capped so the file cannot grow without bound. */
class HistoryStore(private val directory: File, private val cap: Int = DEFAULT_CAP) {

    private val file get() = File(directory, FILE_NAME)

    @Synchronized
    fun append(entry: HistoryEntry) {
        directory.mkdirs()
        val existing = file.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
        val lines = (existing + encode(entry)).takeLast(cap)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun readNewestFirst(): List<HistoryEntry> = try {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull { decode(it) }.asReversed()
    } catch (e: java.io.IOException) {
        emptyList()
    }

    @Synchronized
    fun clear() {
        if (file.isFile) file.delete()
    }

    companion object {
        const val FILE_NAME = "history.jsonl"
        const val DEFAULT_CAP = 200
    }
}
