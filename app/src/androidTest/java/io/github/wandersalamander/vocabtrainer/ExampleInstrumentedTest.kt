package io.github.wandersalamander.vocabtrainer

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // packageName mirrors applicationId, which we deliberately keep as
        // com.example.myapplication so existing installs don't lose data.
        // The Kotlin namespace is io.github.wandersalamander.vocabtrainer.
        assertEquals("com.example.myapplication", appContext.packageName)
    }
}