package com.example.myapplication

data class Candidates(var vocabs: MutableList<Vocab>) {
    fun getVocab(): Vocab {
        val randomIndex = (0 until vocabs.size).random()
        return vocabs.removeAt(randomIndex)
    }

    fun isEmpty(): Boolean {
        return vocabs.isEmpty()
    }

    fun size(): Int {
        return vocabs.size
    }
}

