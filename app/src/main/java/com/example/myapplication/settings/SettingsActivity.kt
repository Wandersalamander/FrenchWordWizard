package com.example.myapplication.settings

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.R
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.setDebouncedOnClickListener
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.SentenceSource
import com.example.myapplication.dictionary.openDictionaryStream
import com.example.myapplication.dictionary.parseVocabHash
import com.example.myapplication.llm.LlmService
import com.example.myapplication.streak.StreakAlarmScheduler
import com.example.myapplication.streak.StreakTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = AppPrefs.get(this)
        val currentLanguage = Language.fromCode(prefs.getString(AppPrefs.KEY_APP_LANGUAGE, null))

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
            prefs.edit().putString(AppPrefs.KEY_APP_LANGUAGE, language.code).apply()
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
            prefs.edit().putString(AppPrefs.KEY_SENTENCE_SOURCE, source.storageValue).apply()
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

        downloadButton.setDebouncedOnClickListener { LlmService.startDownload(applicationContext) }
        cancelButton.setDebouncedOnClickListener { LlmService.cancelDownload() }
        retryButton.setDebouncedOnClickListener { LlmService.retry(applicationContext) }
        deleteButton.setDebouncedOnClickListener { LlmService.deleteModel(applicationContext) }

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

        setupStreakSection()
        setupResetProgressSection()
    }

    private fun setupStreakSection() {
        val statsView = findViewById<TextView>(R.id.streakStatsText)
        val switchView = findViewById<SwitchCompat>(R.id.streakReminderSwitch)
        val timeButton = findViewById<Button>(R.id.streakReminderTimeButton)

        fun renderStats() {
            val current = StreakTracker.currentStreak(this)
            val longest = StreakTracker.longestStreak(this)
            val shields = StreakTracker.freezesAvailable(this)
            statsView.text = "Current: $current day${plural(current)} · Best: $longest day${plural(longest)} · 🛡️ $shields"
        }

        fun renderTime() {
            val h = StreakTracker.reminderHour(this)
            val m = StreakTracker.reminderMinute(this)
            timeButton.text = String.format(Locale.US, "%02d:%02d", h, m)
        }

        renderStats()
        renderTime()
        switchView.isChecked = StreakTracker.isReminderEnabled(this)
        timeButton.isEnabled = switchView.isChecked

        switchView.setOnCheckedChangeListener { _, isChecked ->
            StreakTracker.setReminderEnabled(this, isChecked)
            timeButton.isEnabled = isChecked
            // schedule() handles both arming and cancelling based on the flag.
            StreakAlarmScheduler.schedule(this)
        }

        timeButton.setDebouncedOnClickListener {
            val hour = StreakTracker.reminderHour(this)
            val minute = StreakTracker.reminderMinute(this)
            TimePickerDialog(
                this,
                { _, h, m ->
                    StreakTracker.setReminderTime(this, h, m)
                    renderTime()
                    StreakAlarmScheduler.schedule(this)
                },
                hour,
                minute,
                true,  // 24-hour view; matches the "21:00" default semantics
            ).show()
        }
    }

    private fun setupResetProgressSection() {
        val prefs = AppPrefs.get(this)
        val spinner = findViewById<Spinner>(R.id.resetLanguageSpinner)
        val button = findViewById<Button>(R.id.resetProgressButton)

        val languages = Language.values().toList()
        val labels = languages.map { it.englishName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val current = Language.fromCode(prefs.getString(AppPrefs.KEY_APP_LANGUAGE, null))
        spinner.setSelection(languages.indexOf(current).coerceAtLeast(0))

        button.setDebouncedOnClickListener {
            val language = languages.getOrNull(spinner.selectedItemPosition) ?: return@setDebouncedOnClickListener
            val dialog = AlertDialog.Builder(this)
                .setTitle("Reset ${language.englishName} progress?")
                .setMessage(
                    "All learned/hard flags and quiz stats for ${language.englishName} " +
                        "will be permanently deleted. This cannot be undone."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        val removed = wipeProgressFor(language)
                        Toast.makeText(
                            this@SettingsActivity,
                            "Reset ${language.englishName} progress ($removed entries cleared).",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    ContextCompat.getColor(this@SettingsActivity, R.color.danger)
                )
            }
            dialog.show()
        }
    }

    private suspend fun wipeProgressFor(language: Language): Int = withContext(Dispatchers.IO) {
        val hashes = collectHashes(language)
        if (hashes.isEmpty()) return@withContext 0
        val prefs = AppPrefs.get(this@SettingsActivity)
        val editor = prefs.edit()
        var removed = 0
        // Vocab hashes in the CSVs are 32-char MD5s; per-word keys are stored
        // as "<hash><suffix>" by Vocab.savePreferences. Match by hash prefix.
        for (key in prefs.all.keys) {
            if (key.length >= 32 && hashes.contains(key.substring(0, 32))) {
                editor.remove(key)
                removed++
            }
        }
        editor.putLong(AppPrefs.progressWipedAtKey(language.code), System.currentTimeMillis())
        editor.apply()
        removed
    }

    private fun collectHashes(language: Language): Set<String> {
        val hashes = mutableSetOf<String>()
        openDictionaryStream(this, language).bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                parseVocabHash(line)?.let { hashes.add(it) }
            }
        }
        return hashes
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"

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
