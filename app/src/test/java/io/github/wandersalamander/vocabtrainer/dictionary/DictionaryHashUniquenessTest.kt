package io.github.wandersalamander.vocabtrainer.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the load-bearing invariant behind the hash-only persistence key: every
 * vocab hash (CSV column 4) must be unique across the ENTIRE bundled corpus —
 * within a language AND across languages.
 *
 * Why this matters: [io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity]
 * and the whole [io.github.wandersalamander.vocabtrainer.data.RoomVocabPersistence]
 * cache key per-vocab progress on `(hash, skill)` with NO language column. If
 * two entries — same language or different — share a hash, they share one
 * progress row: studying one bleeds into the other, and the per-language
 * progress reset in SettingsActivity (`DELETE FROM vocab_stats WHERE hash IN …`)
 * cross-deletes the other language's data. That regression actually shipped —
 * Italian and Portuguese shared 130 hashes (plus one French/Italian pair) — and
 * this test pins the re-keying fix so a future asset edit or new language can't
 * silently reintroduce it.
 *
 * Reads the asset CSVs straight off disk (plain JVM unit test, no Android
 * Context), locating them by walking up from the test working directory.
 */
class DictionaryHashUniquenessTest {

    // Same naming pattern MyDictionary.openDictionaryStream filters on.
    private val fileRegex = Regex("""dictionary_sorted_.+_\d{2}\.csv""")

    @Test fun everyHashIsGloballyUnique() {
        val assetsRoot = locateAssetsRoot()
        // hash -> list of "language/file:line" it was found at.
        val owners = HashMap<String, MutableList<String>>()
        var csvCount = 0
        assetsRoot.listFiles()?.filter { it.isDirectory }?.forEach { langDir ->
            langDir.listFiles()?.filter { fileRegex.matches(it.name) }?.forEach { csv ->
                csvCount++
                csv.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        // Mirror parseVocabHash / Vocab.fromCsvLine exactly: a
                        // real entry has 6 tab columns and the hash is column 4.
                        val parts = line.split('\t', limit = 6)
                        if (parts.size >= 6) {
                            val hash = parts[3].trim()
                            if (hash.isNotEmpty()) {
                                owners.getOrPut(hash) { mutableListOf() }
                                    .add("${langDir.name}/${csv.name}:${idx + 1}")
                            }
                        }
                    }
                }
            }
        }
        assertTrue(
            "found no dictionary CSVs under $assetsRoot — test wiring is broken, not the data",
            csvCount > 0,
        )

        val dups = owners.filterValues { it.size > 1 }
        val report = dups.entries.take(20).joinToString("\n") { (h, locs) ->
            "  $h -> ${locs.joinToString(", ")}"
        }
        assertTrue(
            "vocab hash(es) appear in more than one place; the (hash, skill) persistence " +
                "key requires global uniqueness or per-language progress cross-talks (see " +
                "test kdoc). ${dups.size} duplicate hash(es):\n$report",
            dups.isEmpty(),
        )
    }

    /** Walk up from the unit-test working dir to find the bundled assets folder. */
    private fun locateAssetsRoot(): File {
        val candidates = listOf("src/main/assets", "app/src/main/assets")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (c in candidates) {
                val f = File(dir, c)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "could not locate assets dir from working dir ${System.getProperty("user.dir")}",
        )
    }
}
