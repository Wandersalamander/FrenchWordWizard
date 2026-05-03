package com.example.myapplication

import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("app_language", "fr") ?: "fr"

        val radioGroup = findViewById<RadioGroup>(R.id.languageRadioGroup)
        val radioFrench = findViewById<RadioButton>(R.id.radioFrench)
        val radioGerman = findViewById<RadioButton>(R.id.radioGerman)
        val radioItalian = findViewById<RadioButton>(R.id.radioItalian)

        when (currentLang) {
            "fr" -> radioFrench.isChecked = true
            "de" -> radioGerman.isChecked = true
            "it" -> radioItalian.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = when (checkedId) {
                R.id.radioFrench -> "fr"
                R.id.radioGerman -> "de"
                R.id.radioItalian -> "it"
                else -> "fr"
            }
            prefs.edit().putString("app_language", lang).apply()
        }
    }
}
