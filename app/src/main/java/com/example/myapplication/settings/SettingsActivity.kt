package com.example.myapplication.settings

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.R
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.SentenceSource
import com.example.myapplication.llm.LlmService
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
        val currentLanguage = Language.fromCode(prefs.getString("app_language", null))

        val radioGroup = findViewById<RadioGroup>(R.id.languageRadioGroup)
        val radioFrench = findViewById<RadioButton>(R.id.radioFrench)
        val radioGerman = findViewById<RadioButton>(R.id.radioGerman)
        val radioItalian = findViewById<RadioButton>(R.id.radioItalian)
        val radioChinese = findViewById<RadioButton>(R.id.radioChinese)

        val radioByLanguage = mapOf(
            Language.FRENCH to radioFrench,
            Language.GERMAN to radioGerman,
            Language.ITALIAN to radioItalian,
            Language.CHINESE to radioChinese,
        )
        val languageByRadioId = mapOf(
            R.id.radioFrench to Language.FRENCH,
            R.id.radioGerman to Language.GERMAN,
            R.id.radioItalian to Language.ITALIAN,
            R.id.radioChinese to Language.CHINESE,
        )

        radioByLanguage[currentLanguage]?.isChecked = true

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val language = languageByRadioId[checkedId] ?: Language.DEFAULT
            prefs.edit().putString("app_language", language.code).apply()
        }

        // Sentence-source preference: easy / hard / LLM. Defaults to EASY for
        // fresh installs so new users get the gentlest material out of the box.
        val sentenceGroup = findViewById<RadioGroup>(R.id.sentenceSourceRadioGroup)
        val radioSentenceEasy = findViewById<RadioButton>(R.id.radioSentenceEasy)
        val radioSentenceLlm = findViewById<RadioButton>(R.id.radioSentenceLlm)
        val radioByPosition = mapOf(
            SentenceSource.EASY to radioSentenceEasy,
            SentenceSource.HARD to findViewById<RadioButton>(R.id.radioSentenceHard),
            SentenceSource.LLM to radioSentenceLlm,
        )
        val sourceByRadioId = mapOf(
            R.id.radioSentenceEasy to SentenceSource.EASY,
            R.id.radioSentenceHard to SentenceSource.HARD,
            R.id.radioSentenceLlm to SentenceSource.LLM,
        )
        radioByPosition[SentenceSource.fromPrefs(prefs)]?.isChecked = true
        // Start disabled — the status flow below re-enables once the model is Ready.
        radioSentenceLlm.isEnabled = false
        sentenceGroup.setOnCheckedChangeListener { _, checkedId ->
            val source = sourceByRadioId[checkedId] ?: SentenceSource.DEFAULT
            prefs.edit().putString(SentenceSource.PREF_KEY, source.storageValue).apply()
        }

        // AI status — kick off init in case the user opened settings before MainActivity ran,
        // then mirror the status flow into the views.
        LlmService.warmup(applicationContext)

        val statusText = findViewById<TextView>(R.id.aiStatusText)
        val statusDetail = findViewById<TextView>(R.id.aiStatusDetail)
        val downloadButton = findViewById<Button>(R.id.aiDownloadButton)
        val cancelButton = findViewById<Button>(R.id.aiCancelButton)
        val retryButton = findViewById<Button>(R.id.aiRetryButton)
        val deleteButton = findViewById<Button>(R.id.aiDeleteButton)

        downloadButton.setOnClickListener { LlmService.startDownload(applicationContext) }
        cancelButton.setOnClickListener { LlmService.cancelDownload() }
        retryButton.setOnClickListener { LlmService.retry(applicationContext) }
        deleteButton.setOnClickListener { LlmService.deleteModel(applicationContext) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LlmService.status.collect { status ->
                    statusText.text = renderStatus(status)

                    val detail = (status as? LlmService.Status.Unavailable)?.reason
                    if (detail != null) {
                        statusDetail.text = detail
                        statusDetail.visibility = View.VISIBLE
                    } else {
                        statusDetail.visibility = View.GONE
                    }

                    downloadButton.visibility =
                        if (status is LlmService.Status.NotDownloaded) View.VISIBLE else View.GONE
                    cancelButton.visibility =
                        if (status is LlmService.Status.Downloading) View.VISIBLE else View.GONE
                    retryButton.visibility =
                        if (status is LlmService.Status.Unavailable) View.VISIBLE else View.GONE
                    deleteButton.visibility =
                        if (status is LlmService.Status.Ready ||
                            status is LlmService.Status.Unavailable
                        ) View.VISIBLE else View.GONE

                    // Only let the user pick LLM sentences when the model is
                    // actually usable. If the model is definitively gone
                    // (NotDownloaded / Unavailable) but the persisted pref was
                    // LLM, fall back to EASY so we don't sit on a broken
                    // selection. During Initializing/Downloading we don't yank
                    // an existing selection — the model is on its way.
                    radioSentenceLlm.isEnabled = status is LlmService.Status.Ready
                    val modelGone = status is LlmService.Status.NotDownloaded ||
                        status is LlmService.Status.Unavailable
                    if (modelGone && radioSentenceLlm.isChecked) {
                        radioSentenceEasy.isChecked = true
                    }
                }
            }
        }
    }

    private fun renderStatus(status: LlmService.Status): String = when (status) {
        is LlmService.Status.Unknown -> "AI sentences: checking…"
        is LlmService.Status.NotDownloaded -> "AI sentences: model not downloaded"
        is LlmService.Status.Downloading -> {
            val downloaded = Formatter.formatShortFileSize(this, status.bytesDownloaded)
            if (status.totalBytes > 0L) {
                val total = Formatter.formatShortFileSize(this, status.totalBytes)
                val pct = (100L * status.bytesDownloaded / status.totalBytes).toInt()
                "AI sentences: downloading — $downloaded / $total ($pct%)"
            } else {
                "AI sentences: downloading — $downloaded"
            }
        }
        is LlmService.Status.Initializing -> "AI sentences: loading model…"
        is LlmService.Status.Ready -> "AI sentences: ready ✓"
        is LlmService.Status.Unavailable -> "AI sentences: unavailable"
    }
}
