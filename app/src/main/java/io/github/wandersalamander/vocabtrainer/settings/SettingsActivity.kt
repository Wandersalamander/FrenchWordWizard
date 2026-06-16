package io.github.wandersalamander.vocabtrainer.settings

import android.app.TimePickerDialog
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.applySystemBarAndActionBarInsetsAsPadding
import io.github.wandersalamander.vocabtrainer.data.backup.ProgressBackup
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.LanguageWipeRepository
import io.github.wandersalamander.vocabtrainer.enableEdgeToEdge
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener
import io.github.wandersalamander.vocabtrainer.themeActionBarHeightPx
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MAX
import io.github.wandersalamander.vocabtrainer.dictionary.AUTO_INTRODUCE_THRESHOLD_MIN
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.openDictionaryStream
import io.github.wandersalamander.vocabtrainer.dictionary.parseVocabHash
import io.github.wandersalamander.vocabtrainer.llm.LlmService
import io.github.wandersalamander.vocabtrainer.streak.StreakAlarmScheduler
import io.github.wandersalamander.vocabtrainer.streak.StreakTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    // SAF launchers for the Backup & restore section. Registered as properties
    // so they're wired up before the activity reaches STARTED. CreateDocument
    // hands back a writable Uri for a new file; OpenDocument hands back a
    // readable Uri for one the user picked.
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { performExport(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { performImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        // Pad the inner LinearLayout (not the ScrollView): the ScrollView keeps
        // its edge-to-edge bounds so the scrollbar runs the full height, while
        // the content starts below the action bar and tucks under it on scroll.
        findViewById<View>(R.id.contentRoot)
            .applySystemBarAndActionBarInsetsAsPadding(themeActionBarHeightPx())

        val prefs = AppPrefs.get(this)

        // Language is chosen from the flag button on the main screen
        // (MainActivity.setupLanguageMenu), so Settings no longer hosts a
        // language selector.

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
            SentenceSource.persist(this, source)
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
                    // A successful download flips the persisted pref to LLM (see
                    // LlmService.startDownload). If that happened while this
                    // screen was open — or the user reopened Settings with an LLM
                    // pref already saved — reflect it now that the radio is usable.
                    if (status is LlmService.Status.Ready &&
                        SentenceSource.fromPrefs(prefs) == SentenceSource.LLM
                    ) {
                        radioSentenceLlm.isChecked = true
                    }
                    val modelGone = status is LlmService.Status.NotDownloaded ||
                        status is LlmService.Status.Unavailable
                    if (modelGone && radioSentenceLlm.isChecked) {
                        radioSentenceEasy.isChecked = true
                    }
                }
            }
        }

        setupAutoAddSection()
        setupStreakSection()
        setupBackupSection()
        setupResetProgressSection()
    }

    private fun setupAutoAddSection() {
        // The threshold is per-language; this screen tunes whichever language is
        // currently selected. The label below makes that scope explicit so the
        // knob doesn't read as a global setting.
        val language = Language.fromPrefs(this)
        val valueText = findViewById<TextView>(R.id.autoAddValueText)
        val decreaseButton = findViewById<TextView>(R.id.autoAddDecreaseButton)
        val increaseButton = findViewById<TextView>(R.id.autoAddIncreaseButton)

        findViewById<TextView>(R.id.autoAddLanguageLabel).text =
            "for ${language.flag} ${language.englishName}"

        fun render(value: Int) {
            valueText.text = value.toString()
            // The TextView itself reads as "5", so we lift the role onto the
            // contentDescription with the unit included — TalkBack users get
            // "Words to learn at once: 5" instead of a bare digit.
            valueText.contentDescription = "Words to learn at once: $value"
            // Boundary buttons go to 35% opacity so the floor/ceiling reads
            // visually before the no-op tap explains it.
            decreaseButton.isEnabled = value > AUTO_INTRODUCE_THRESHOLD_MIN
            decreaseButton.alpha = if (decreaseButton.isEnabled) 1f else 0.35f
            increaseButton.isEnabled = value < AUTO_INTRODUCE_THRESHOLD_MAX
            increaseButton.alpha = if (increaseButton.isEnabled) 1f else 0.35f
        }

        fun current(): Int = AppPrefs.autoAddThreshold(this, language.code)

        fun update(delta: Int) {
            val next = current() + delta
            AppPrefs.setAutoAddThreshold(this, language.code, next)
            render(AppPrefs.autoAddThreshold(this, language.code))
        }

        render(current())

        // No debounce: this is a stepper, the user expects rapid taps to land
        // 1:1 against the value rather than getting swallowed.
        decreaseButton.setOnClickListener { update(-1) }
        increaseButton.setOnClickListener { update(+1) }
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
            // The bare "21:00" reads as "twenty-one zero zero" with no role
            // hint. Spelling out the action gives SR users the same affordance
            // sighted users get from the section context.
            timeButton.contentDescription = String.format(
                Locale.US,
                "Reminder time, currently %02d:%02d, double-tap to change",
                h, m,
            )
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

        val current = Language.fromPrefs(this)
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

    private fun setupBackupSection() {
        findViewById<Button>(R.id.exportProgressButton).setDebouncedOnClickListener {
            exportLauncher.launch(defaultBackupFileName())
        }
        findViewById<Button>(R.id.importProgressButton).setDebouncedOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Import progress?")
                .setMessage(
                    "Pick a backup file to restore. Its entries are merged into your current " +
                        "progress — anything with the same word/skill is overwritten."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose file") { _, _ ->
                    // "*/*" so a backup the file manager tagged with an unexpected MIME type
                    // is still selectable — the contents are validated on import.
                    importLauncher.launch(arrayOf("*/*"))
                }
                .show()
        }
    }

    private fun defaultBackupFileName(): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "vocabtrainer-progress-$date.json"
    }

    private fun performExport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ProgressBackup.export(this@SettingsActivity)
                    (contentResolver.openOutputStream(uri)
                        ?: throw IOException("Couldn't open the chosen file for writing."))
                        .use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
            result.onSuccess {
                Toast.makeText(this@SettingsActivity, "Progress exported.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                showBackupError("Export failed", "Couldn't write the backup file. Please try again.")
            }
        }
    }

    private fun performImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    (contentResolver.openInputStream(uri)
                        ?: throw IOException("Couldn't open the chosen file for reading."))
                        .use { ProgressBackup.import(this@SettingsActivity, it) }
                }
            }
            result.onSuccess { summary -> showImportResult(summary) }
                .onFailure { e ->
                    val msg = (e as? ProgressBackup.InvalidBackupException)?.message
                        ?: "Couldn't read this file as a backup. Please pick the file you exported."
                    showBackupError("Import failed", msg)
                }
        }
    }

    private fun showImportResult(summary: ProgressBackup.ImportSummary) {
        val streakLine = if (summary.streakImported) "\n• Streak restored" else ""
        AlertDialog.Builder(this)
            .setTitle("Progress imported")
            .setMessage(
                "Restored ${summary.distinctWords} words " +
                    "(${summary.statRows} skill rows, ${summary.reviewLogRows} review log entries)." +
                    streakLine +
                    "\n\nReturn to practice to see your progress reloaded."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBackupError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private suspend fun wipeProgressFor(language: Language): Int = withContext(Dispatchers.IO) {
        val hashes = collectHashes(language)
        if (hashes.isEmpty()) return@withContext 0

        // Delete the rows from Room (the live source of truth) and drop the
        // hashes from the in-memory cache so any MyDictionary already holding
        // these Vocabs sees a fresh state on its next reloadPreferences.
        val dao = DataLayer.database.vocabDao()
        dao.deleteStatsForHashes(hashes)
        dao.deleteFlagsForHashes(hashes)
        DataLayer.vocabPersistence.evict(hashes)

        // Also wipe the legacy SharedPreferences entries for these hashes.
        // If we left them, a future reinstall via Android Auto Backup would
        // restore them AND skip the importer (because the in-Room marker
        // exists), but only an Auto-Backup-restored install with an empty
        // Room DB hits the importer at all — see LegacyImporter. The safer
        // model is: every wipe is fully reflected in both stores.
        val prefs = AppPrefs.get(this@SettingsActivity)
        val editor = prefs.edit()
        var removed = 0
        for (key in prefs.all.keys) {
            if (key.length >= 32 && hashes.contains(key.substring(0, 32))) {
                editor.remove(key)
                removed++
            }
        }
        editor.apply()

        // A wipe empties the language, so its calibration frontier (a fact
        // derived from the now-deleted progress) is stale. Forget it: on the
        // next open the language looks fresh and re-calibrates, exactly like a
        // first install.
        AppPrefs.clearCalibration(this@SettingsActivity, language.code)

        LanguageWipeRepository.recordWipe(language.code)
        removed.coerceAtLeast(hashes.size)
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
