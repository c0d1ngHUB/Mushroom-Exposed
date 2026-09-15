package com.example.mushroomexposed

import android.content.res.AssetManager

enum class LookalikeKind { GEFAEHRLICH, ACHTUNG }

data class Lookalike(
    val kind: LookalikeKind,
    val key: String,
    val name: String,
    val evidence: String,
)

/**
 * Reads `lookalikes.txt`, written by `src/extract_lookalikes.py` in the training repo.
 *
 * Format: one line per species, `speciesKey|kind:target[:name[:evidence]]|...`.
 * A `|` inside a field is escaped as `\|`; `#` starts a comment line.
 */
object LookalikeData {

    fun load(assets: AssetManager, namesByKey: Map<String, String>): Map<String, List<Lookalike>> =
        parse(assets.open(ASSET).bufferedReader().readLines().asSequence(), namesByKey)

    fun parse(lines: Sequence<String>, namesByKey: Map<String, String>): Map<String, List<Lookalike>> {
        val result = LinkedHashMap<String, MutableList<Lookalike>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val species = line.substringBefore('|').trim()
            if (species.isEmpty() || !line.contains('|')) continue

            val found = LinkedHashMap<String, Lookalike>()
            for (segment in fields(line.substringAfter('|'))) {
                val parts = segment.split(":")
                if (parts.size < 2) continue
                val kind = kindOf(parts[0]) ?: continue
                val key = parts[1].trim()
                if (key.isEmpty() || key == species) continue
                val name = parts.getOrNull(2)?.trim().orEmpty().ifEmpty {
                    namesByKey[key] ?: key.replace('_', ' ')
                }
                val evidence = parts.drop(3).joinToString(":").trim()
                val previous = found[key]
                if (previous != null && previous.kind == LookalikeKind.GEFAEHRLICH) continue
                found[key] = Lookalike(kind = kind, key = key, name = name, evidence = evidence)
            }
            if (found.isNotEmpty()) result[species] = found.values.toMutableList()
        }
        return result
    }

    /** Splits a line on unescaped `|` and unescapes `\|` inside the fields. */
    private fun fields(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    current.append(text[i + 1]); i += 2
                }
                c == '|' -> {
                    out.add(current.toString()); current.setLength(0); i++
                }
                else -> {
                    current.append(c); i++
                }
            }
        }
        out.add(current.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun kindOf(token: String): LookalikeKind? = when (token.trim().lowercase()) {
        "gefaehrlich" -> LookalikeKind.GEFAEHRLICH
        "achtung" -> LookalikeKind.ACHTUNG
        else -> null
    }

    private const val ASSET = "lookalikes.txt"
}
