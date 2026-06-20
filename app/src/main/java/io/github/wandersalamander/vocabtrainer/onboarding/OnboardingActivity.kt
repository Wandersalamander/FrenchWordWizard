package io.github.wandersalamander.vocabtrainer.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.wandersalamander.vocabtrainer.R
import io.github.wandersalamander.vocabtrainer.applySystemBarAndActionBarInsetsAsPadding
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.enableEdgeToEdge
import io.github.wandersalamander.vocabtrainer.quiz.MainActivity
import io.github.wandersalamander.vocabtrainer.setDebouncedOnClickListener

/**
 * First-ever-launch setup. Lets the user choose a learning language before
 * anything else happens. On commit it persists the language plus the
 * onboarding-done flag ([AppPrefs.completeOnboarding]) and hands off to
 * [MainActivity], which runs the once-per-language level check, then asks for
 * the active-pool size, then (first time only) the button tutorial — so the
 * very first thing a user touches is their own choice, not a tour of buttons
 * for a screen they haven't seen.
 *
 * The active-pool size is deliberately NOT asked here: it's a drill-loop
 * parameter, asked after the assessment so language placement and drill setup
 * stay separate concerns. See MainActivity.onAssessmentComplete.
 *
 * Themed as the Monokai code editor the rest of the app lives in: the dark
 * canvas is "configured" like a file and the language reads as a cyan value.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var selected: Language

    // Per-tile views so selection can repaint the marker/contentDescription
    // without re-querying the container.
    private val tilesByLanguage = LinkedHashMap<Language, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        // No action bar on this screen, so pass 0 — only the status/nav bar
        // insets are added to the scroll content's existing padding.
        findViewById<View>(R.id.onbRoot)
            .applySystemBarAndActionBarInsetsAsPadding(0)

        selected = defaultLanguage()

        buildLanguageTiles()
        setupBeginButton()
        animateEntrance()
    }

    /**
     * Best-guess initial language: the device's primary locale if we ship that
     * language, otherwise [Language.DEFAULT]. Only a starting highlight — the
     * user can pick any tile.
     */
    private fun defaultLanguage(): Language {
        val deviceCode = resources.configuration.locales[0].language
        return Language.values().firstOrNull { it.code == deviceCode } ?: Language.DEFAULT
    }

    private fun buildLanguageTiles() {
        val list = findViewById<LinearLayout>(R.id.onbLanguageList)
        Language.values().forEach { language ->
            val tile = layoutInflater.inflate(R.layout.item_onb_language, list, false)
            tile.findViewById<TextView>(R.id.onbLangFlag).text = language.flag
            tile.findViewById<TextView>(R.id.onbLangName).text = language.englishName
            tile.findViewById<TextView>(R.id.onbLangGreeting).text = language.greeting
            tile.setOnClickListener { selectLanguage(language) }
            list.addView(tile)
            tilesByLanguage[language] = tile
        }
        repaintSelection()
    }

    private fun selectLanguage(language: Language) {
        if (language == selected) return
        selected = language
        repaintSelection()
    }

    /** Reflect [selected] across every tile: border state, marker, and label. */
    private fun repaintSelection() {
        tilesByLanguage.forEach { (language, tile) ->
            val isSelected = language == selected
            tile.isSelected = isSelected
            val mark = tile.findViewById<TextView>(R.id.onbLangMark)
            mark.text = if (isSelected) "●" else "○"
            mark.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isSelected) R.color.seg_listen else R.color.card_text_muted,
                ),
            )
            // The bare flag/name carry no selection cue for TalkBack, so state
            // it explicitly on the row.
            val state = if (isSelected) "selected" else "not selected"
            tile.contentDescription = "${language.englishName}, ${language.greeting}, $state"
        }
    }

    private fun setupBeginButton() {
        findViewById<TextView>(R.id.onbBegin).setDebouncedOnClickListener {
            AppPrefs.completeOnboarding(this, selected.code)
            // Fresh task root: the user can't navigate "back" into a setup
            // screen that no longer applies.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    /**
     * Staggered fade-and-rise as the screen settles, one block at a time top to
     * bottom, so the config reads as being "typed in" rather than dumped at
     * once. Pure ViewPropertyAnimator — no dependency on the Motion stack.
     */
    private fun animateEntrance() {
        val blocks = listOf(
            R.id.onbCrumb, R.id.onbTitle, R.id.onbSubtitle,
            R.id.onbLangHeading, R.id.onbLanguageList,
            R.id.onbBegin, R.id.onbFooter,
        )
        blocks.forEachIndexed { index, id ->
            val view = findViewById<View>(id)
            view.alpha = 0f
            view.translationY = 24f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(40L * index)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
