package com.example.myapplication

import android.content.SharedPreferences
import java.io.InputStream
import kotlin.random.Random

class MyDictionary(inputStream: InputStream, val sharedPreferences: SharedPreferences) {
    val csvData: List<Vocab> = readCsv(inputStream)

    var candidates: Candidates? = null

    init {
        genCandidates(false)
    }

    fun getActiveDataSize(): Int {
        return csvData.filter { it.nTimesViewed > 0 }.size

    }

    fun readCsv(inputStream: InputStream): List<Vocab> {
        val reader = inputStream.bufferedReader()
        return reader.lineSequence()
            .map {
                val (french, english, importance, hash, frenchLong) = it.split(
                    '\t', ignoreCase = false, limit = 5
                )
                Vocab(
                    french.trim(),
                    english.trim(),
                    importance.trim().toInt(),
                    hash.trim(),
                    frenchLong.trim(),
                    sharedPreferences
                )
            }.toList()
    }

    fun updateCandidatesIfEmpty() {
        if (candidates == null) {
            println("genCandidates")
            genCandidates(false)
        } else if (candidates!!.isEmpty() || candidates!!.size() < 2) {
            println("genCandidates")
            genCandidates(false)
        }
    }

    fun updateCandidates(onlyInactive: Boolean) {
        val sizeBefore: Int
        if (candidates == null) {
            sizeBefore = 0
        } else {
            sizeBefore = candidates!!.size()
        }
        genCandidates(onlyInactive)
        while (true) {
            if (candidates != null) {
                if (sizeBefore != candidates!!.size() && candidates!!.size() == 20) {
                    break
                }
            }
        }
    }

    private fun getRandomActiveData(sortedActiveData: List<Vocab>, size: Int): MutableList<Vocab> {
        val selectedElements = mutableListOf<Vocab>()
        val forbiddenIdx = mutableListOf<Int>()  // to not allow repetition
        val totalChance = sortedActiveData.sumOf { it.sortValue() }

        val randomValues = List(size) { Random.nextDouble(0.0, totalChance) }

        var idxSel: Int = selectedElements.size - 1
        var _iHelp: Int
        var currentSum: Double

        for (randomValue in randomValues) {
            currentSum = 0.0
            for (i in 0 until sortedActiveData.size) {
                currentSum += sortedActiveData[i].sortValue()
                if (currentSum >= randomValue) { // set idxSel and break
                    _iHelp = i
                    do {
                        idxSel = Random.nextInt(from = _iHelp - 1, until = sortedActiveData.size)
                        if (_iHelp > 0) {
                            _iHelp =
                                _iHelp - 1 // Increase random range, in case selection is to narrow and all forbidden
                        }
                    } while (idxSel in forbiddenIdx)
                    break
                }
            }
            selectedElements.add(sortedActiveData[idxSel])
            forbiddenIdx.add(idxSel)

        }
        return selectedElements
    }


    fun genCandidates(onlyInactive: Boolean) {
        Thread {
            synchronized(this) {
                val size = 20

                var activeDataSorted = csvData.filter {
                    it.nTimesViewed > 0 && !it.ignore && it.viewedMiutesAgo() > 1
                }.sortedBy { it.sortValue() }
                if (activeDataSorted.isEmpty()) { // redo filter without "viewedMiutesAgo"
                    activeDataSorted = csvData.filter {
                        it.nTimesViewed > 0 && !it.ignore
                    }.sortedBy { it.sortValue() }
                }

                val inactiveData = csvData.filter { it.nTimesViewed == 0 }
                    .shuffled() // todo remove when list is sorted

                if (activeDataSorted.isEmpty() or onlyInactive) {
                    println("Candidates Updated: Took only new candidates")
                    this.candidates = Candidates(inactiveData.take(size).toMutableList())
                } else if (activeDataSorted.size >= size) {
                    println("Candidates Updated: Took only active candidates")
                    this.candidates = Candidates(
                        //activeDataSorted.takeLast(size).toMutableList()
                        getRandomActiveData(activeDataSorted, 20)
                    )
                } else {
                    val remainder = size - activeDataSorted.size
                    println("Candidates Updated: Took ${remainder} new candidates")
                    val ret =
                        //activeDataSorted.takeLast(activeDataSorted.size)
                        activeDataSorted.toMutableList()
                    ret.addAll(inactiveData.take(remainder))
                    this.candidates = Candidates(ret)
                }
            }
        }.start()

    }

    fun getNextVocab(): Vocab {
        updateCandidatesIfEmpty()
        while (candidates!!.isEmpty()) {
            Thread.sleep(10)
        }
        return candidates!!.getVocab()
    }


    fun getActiveVocabWeightened(): Vocab? {
        val activeData = csvData.filter { it.nTimesViewed != 0 }
        if (activeData.isEmpty()) {
            return null // Ensure your return type supports null
        }
        val totalChance = activeData.sumOf { it.failureProbability().toDouble() }
        val randomValue = Random.nextDouble(0.0, totalChance)
        var sum = 0.0
        var idx = 0
        for (i in 0 until activeData.size) {
            sum += activeData[i].failureProbability()
            idx = i
            if (sum >= randomValue) {
                break
            }
        }
        return activeData.get(idx)
    }

}
