package com.example.myapplication

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun pronounceableFr_isCorrect() {
        val v = Vocab("M. qn. is a cool Dr. (yolo)", "yolo", 1,"1","null")
        println(v.pronounceableFr())
        assertEquals("Monsieur quelqu'un is a cool Docteur yolo", v.pronounceableFr())
    }
}