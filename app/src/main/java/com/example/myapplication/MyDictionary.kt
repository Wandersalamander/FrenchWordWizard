package com.example.myapplication

import android.content.SharedPreferences
import java.io.InputStream
import kotlin.random.Random

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences) {
    val csvData: List<Vocab> = readCsv(inputStream)


    fun getActiveDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 }.size

    }

    fun debugDictionary() {
        val activeData = csvData.filter { it.nTimesViewed != 0 }.iterator()
        while (activeData.hasNext()) {
            val vocab = activeData.next()
            println(vocab.debugSortValue())
        }
    }

    fun readCsv(inputStream: InputStream): List<Vocab> {
        val reader = inputStream.bufferedReader()
        return reader.lineSequence()
            .map { line ->
                val parts = line.split('\t', ignoreCase = false, limit = 6)
                val french = parts[0]
                val english = parts[1]
                val importance = parts[2]
                val hash = parts[3]
                val frenchLong = parts[4]
                val frenchLong2 = parts[5]
                Vocab(
                    french.trim(),
                    english.trim(),
                    importance.trim().toInt(),
                    hash.trim(),
                    frenchLong.trim(),
                    frenchLong2.trim(),
                    sharedPreferences
                )
            }
            .filterNotNull() // Filter out lines where processing failed or skipped
            .toList()

    }

    fun getInactiveVocab(): Vocab {
        val inactiveCsvData = csvData.filter { it.nTimesViewed == 0 }
        if (inactiveCsvData.isEmpty()) {
            return getActiveVocabWeightened()
        } else {
            return inactiveCsvData.get(Random.nextInt(inactiveCsvData.size))
        }
    }

    fun getActiveVocabWeightened(): Vocab {
        val activeData = csvData.filter { it.nTimesViewed != 0 }
        if (activeData.size < 20) { // There should be at least 20 vocabs to learn
            return csvData.get(Random.nextInt(csvData.size))
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
        return activeData.get(idx)
    }

}
