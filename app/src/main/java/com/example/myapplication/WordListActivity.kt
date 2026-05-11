package com.example.myapplication

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class WordListActivity : AppCompatActivity() {
    private lateinit var dictionary: MyDictionary
    private lateinit var adapter: WordListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var sortSpinner: Spinner
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var pageLabel: TextView
    private lateinit var emptyLabel: TextView

    private val pageSize = 100
    private var currentPage = 0
    private var sortIndex = 0
    private var sortedWords: List<Vocab> = emptyList()

    private val sortOptions = listOf(
        "Foreign (A-Z)",
        "Foreign (Z-A)",
        "English (A-Z)",
        "Most struggling",
        "Most mastered",
        "Recently viewed",
        "Least recently viewed",
        "Most viewed",
        "Slowest avg time",
        "Importance",
        "Hard flagged first",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_list)

        val prefs = getSharedPreferences("vocabulary_preferences", Context.MODE_PRIVATE)
        val language = Language.fromCode(prefs.getString("app_language", null))
        sortIndex = prefs.getInt("wordlist_sort_index", 0).coerceIn(0, sortOptions.lastIndex)

        dictionary = MyDictionary(openDictionaryStream(this, language), prefs)

        recyclerView = findViewById(R.id.wordListRecycler)
        sortSpinner = findViewById(R.id.sortSpinner)
        prevButton = findViewById(R.id.prevPageButton)
        nextButton = findViewById(R.id.nextPageButton)
        pageLabel = findViewById(R.id.pageLabel)
        emptyLabel = findViewById(R.id.emptyLabel)

        adapter = WordListAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortOptions,
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortSpinner.adapter = spinnerAdapter
        sortSpinner.setSelection(sortIndex, false)

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (position != sortIndex) {
                    sortIndex = position
                    currentPage = 0
                    prefs.edit().putInt("wordlist_sort_index", sortIndex).apply()
                    refreshList()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        prevButton.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                showPage()
            }
        }
        nextButton.setOnClickListener {
            if (currentPage < totalPages() - 1) {
                currentPage++
                showPage()
            }
        }

        refreshList()
    }

    private fun refreshList() {
        val viewed = dictionary.csvData.filter { it.nTimesViewed > 0 }
        sortedWords = applySort(viewed, sortIndex)
        val total = totalPages()
        if (currentPage >= total) currentPage = (total - 1).coerceAtLeast(0)
        showPage()
    }

    private fun applySort(words: List<Vocab>, idx: Int): List<Vocab> {
        return when (idx) {
            0 -> words.sortedBy { it.french.lowercase() }
            1 -> words.sortedByDescending { it.french.lowercase() }
            2 -> words.sortedBy { it.english.lowercase() }
            // Words the user marked "I know it" (ignore=true) are not struggling
            // regardless of failureProbability, which is high after only 1 view.
            // Push them to the bottom of the struggling sort.
            3 -> words.sortedWith(
                compareBy<Vocab> { it.ignore }
                    .thenByDescending { it.failureProbability() }
            )
            // Inverse: ignored words are the most mastered, so put them on top.
            4 -> words.sortedWith(
                compareByDescending<Vocab> { it.ignore }
                    .thenBy { it.failureProbability() }
            )
            5 -> words.sortedByDescending { it.lastDisplayed }
            6 -> words.sortedBy { it.lastDisplayed }
            7 -> words.sortedByDescending { it.nTimesViewed }
            8 -> words.sortedByDescending { it.viewTimeMilli }
            // Importance: surface the most-important *unknown* words first;
            // push ignored ("I know it") words to the bottom.
            9 -> words.sortedWith(
                compareBy<Vocab> { it.ignore }
                    .thenBy { it.importance }
            )
            10 -> words.sortedByDescending { it.flaggedHard }
            else -> words
        }
    }

    private fun totalPages(): Int {
        if (sortedWords.isEmpty()) return 1
        return (sortedWords.size + pageSize - 1) / pageSize
    }

    private fun showPage() {
        if (sortedWords.isEmpty()) {
            adapter.update(emptyList())
            emptyLabel.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            pageLabel.text = "0 words"
            prevButton.isEnabled = false
            nextButton.isEnabled = false
            return
        }
        emptyLabel.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val from = currentPage * pageSize
        val to = minOf(from + pageSize, sortedWords.size)
        adapter.update(sortedWords.subList(from, to))
        recyclerView.scrollToPosition(0)
        val total = totalPages()
        pageLabel.text = String.format(
            "Page %d / %d  •  %d words",
            currentPage + 1,
            total,
            sortedWords.size,
        )
        prevButton.isEnabled = currentPage > 0
        nextButton.isEnabled = currentPage < total - 1
    }
}
