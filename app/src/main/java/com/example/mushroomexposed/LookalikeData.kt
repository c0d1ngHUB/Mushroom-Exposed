package com.example.mushroomexposed

import android.content.res.AssetManager

enum class LookalikeKind { GEFAEHRLICH, ACHTUNG }

data class Lookalike(
    val kind: LookalikeKind,
    val targetKey: String,
    val targetName: String,
    val evidence: String,
) {
    val isDangerous get() = kind == LookalikeKind.GEFAEHRLICH
}

/**
 * Parses the shipped lookalike asset. Line format:
 * `key|gefaehrlich:ZielKey|achtung:ZielKey|Belegsatz`
 * Comments start with `#`; anything unparseable is skipped, never thrown.
 */
object LookalikeParser {

    private val PAIR = Regex("^(gefaehrlich|achtung):(.+)$", RegexOption.IGNORE_CASE)

    /** A `kind:target` token with any other prefix is data we do not understand, never evidence. */
    private val TOKEN = Regex("^[a-zA-Z]+:\\S+$")

    fun parse(lines: Sequence<String>, namesByKey: Map<String, String>): Map<String, List<Lookalike>> {
        val result = LinkedHashMap<String, MutableList<Lookalike>>()
        val seen = HashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|").map { it.trim() }
            val key = parts.firstOrNull().orEmpty()
            if (key.isEmpty() || parts.size < 2) continue

            val evidence = parts.drop(1).lastOrNull { !TOKEN.matches(it) } ?: ""
            val pairs = parts.drop(1).mapNotNull { part ->
                val match = PAIR.matchEntire(part) ?: return@mapNotNull null
                val kind = if (match.groupValues[1].equals("gefaehrlich", ignoreCase = true)) {
                    LookalikeKind.GEFAEHRLICH
                } else {
                    LookalikeKind.ACHTUNG
                }
                val target = match.groupValues[2].trim()
                if (target.isEmpty()) null else Lookalike(
                    kind = kind,
                    targetKey = target,
                    targetName = namesByKey[target] ?: target.replace('_', ' '),
                    evidence = evidence,
                )
            }
            for (pair in pairs) {
                if (seen.add("$key|${pair.kind}|${pair.targetKey}")) {
                    result.getOrPut(key) { mutableListOf() }.add(pair)
                }
            }
        }
        return result
    }
}

object LookalikeData {
    const val ASSET_NAME = "lookalikes.txt"

    /** Missing or broken asset means "no lookalike warnings", never a crash. */
    fun load(assets: AssetManager, namesByKey: Map<String, String>): Map<String, List<Lookalike>> = try {
        assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            LookalikeParser.parse(lines, namesByKey)
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
