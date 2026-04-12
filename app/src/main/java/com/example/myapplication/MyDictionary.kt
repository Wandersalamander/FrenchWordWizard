package com.example.myapplication

import android.content.SharedPreferences
import java.io.InputStream
import kotlin.random.Random

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences) {
    val csvData: List<Vocab> = readCsv(inputStream)


    fun reloadPreferences() {
        csvData.forEach { it.loadPreferences() }
    }

    fun getActiveDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 }.size
    }

    fun getFinishedDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 && it.failureProbability() < 0.1f }.size
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
        } else {
            return inactiveCsvData.get(Random.nextInt(inactiveCsvData.size))
        }
    }

    fun getActiveVocabWeightened(): Vocab {
        val activeData = csvData.filter { (it.nTimesViewed != 0) && !it.ignore && (it.sortValue() > 0.0) }
        if (activeData.size < 20) {
            val available = csvData.filter { !it.ignore }
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
