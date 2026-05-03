package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlin.random.Random

fun openDictionaryStream(context: Context, language: String): InputStream {
    return when (language) {
        "de" -> context.resources.openRawResource(R.raw.dictionary_sorted_german)
        "it" -> openItalianDictionary(context)
        else -> context.resources.openRawResource(R.raw.dictionary_sorted_2)
    }
}

private fun openItalianDictionary(context: Context): InputStream {
    val streams = mutableListOf<InputStream>()
    var i = 1
    while (true) {
        val name = "dictionary_sorted_italian_%02d".format(i)
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) break
        streams.add(context.resources.openRawResource(resId))
        i++
    }
    return SequenceInputStream(Collections.enumeration(streams))
}

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences) {
    val csvData: List<Vocab> = readCsv(inputStream)


    fun reloadPreferences() {
        csvData.forEach { it.loadPreferences() }
    }

    fun getActiveDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 }.size
    }

    fun getFinishedDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 && (it.ignore || it.failureProbability() < 0.1f) }.size
    }

    fun getIgnoredDataSize(): Int {
        return csvData.filter { it.ignore }.size
    }

    fun debugDictionary() {
        val activeData = csvData.filter { it.nTimesViewed != 0 }.iterator()
        while (activeData.hasNext()) {
            val vocab = activeData.next()
            println(vocab.debugSortValue())
        }
    }

    fun readCsv(inputStream: InputStream): List<Vocab> {
        return inputStream.bufferedReader().use { reader ->
            reader.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\t', ignoreCase = false, limit = 6)
                    if (parts.size < 6) return@mapNotNull null
                    try {
                        Vocab(
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim().toInt(),
                            parts[3].trim(),
                            parts[4].trim(),
                            parts[5].trim(),
                            sharedPreferences
                        )
                    } catch (e: NumberFormatException) {
                        null
                    }
                }
                .toList()
        }
    }

    fun getInactiveVocab(): Vocab {
        val inactiveCsvData = csvData.filter { it.nTimesViewed == 0 && !it.ignore }
        if (inactiveCsvData.isEmpty()) {
            return getActiveVocabWeightened()
        }
        val minImportance = inactiveCsvData.minOf { it.importance }
        val candidates = inactiveCsvData.filter { it.importance == minImportance }
        return candidates[Random.nextInt(candidates.size)]
    }

    fun getActiveVocabWeightened(): Vocab {
        val activeData = csvData.filter { (it.nTimesViewed != 0) && !it.ignore && (it.sortValue() > 0.0) }
        if (activeData.size < 20) {
            val inactive = csvData.filter { it.nTimesViewed == 0 && !it.ignore }
            if (inactive.isNotEmpty()) {
                val minImportance = inactive.minOf { it.importance }
                val candidates = inactive.filter { it.importance == minImportance }
                return candidates[Random.nextInt(candidates.size)]
            }
            val available = csvData.filter { !it.ignore && !(it.nTimesViewed > 0 && it.failureProbability() < 0.1f) }
            if (available.isEmpty()) return csvData[Random.nextInt(csvData.size)]
            return available[Random.nextInt(available.size)]
        }
        val totalChance = activeData.sumOf { it.sortValue() }
        val randomValue = Random.nextDouble(0.0, totalChance)
        var sum = 0.0
        var idx = 0
        for (i in 0 until activeData.size) {
            sum += activeData[i].sortValue()
            idx = i
            if (sum >= randomValue) {
                break
            }
        }
        return activeData[idx]
    }

}
