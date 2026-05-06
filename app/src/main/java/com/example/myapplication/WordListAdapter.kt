package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class WordListAdapter(
    private var items: List<Vocab>,
) : RecyclerView.Adapter<WordListAdapter.WordViewHolder>() {

    class WordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val foreign: TextView = itemView.findViewById(R.id.itemForeign)
        val english: TextView = itemView.findViewById(R.id.itemEnglish)
        val stars: TextView = itemView.findViewById(R.id.itemStars)
        val meta: TextView = itemView.findViewById(R.id.itemMeta)
        val flags: TextView = itemView.findViewById(R.id.itemFlags)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_word_row, parent, false)
        return WordViewHolder(view)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        val v = items[position]
        holder.foreign.text = v.french
        holder.english.text = v.english
        holder.stars.text = v.getStarsString()
        val avgTimeS = v.meanTimeViewedMilli() / 1000.0
        holder.meta.text = String.format(
            "%d views  •  ⧖ %.1fs  •  %s",
            v.nTimesViewed,
            avgTimeS,
            formatLastSeen(v.lastDisplayed),
        )
        val flagBits = mutableListOf<String>()
        if (v.flaggedHard) flagBits.add("Hard")
        if (v.ignore) flagBits.add("Marked learned")
        if (flagBits.isEmpty()) {
            holder.flags.visibility = View.GONE
        } else {
            holder.flags.visibility = View.VISIBLE
            holder.flags.text = flagBits.joinToString("  •  ")
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<Vocab>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun formatLastSeen(lastDisplayed: Long): String {
        if (lastDisplayed == 0L) return "never"
        val diffMs = System.currentTimeMillis() - lastDisplayed
        if (diffMs < 0) return "just now"
        val mins = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        if (mins < 1) return "just now"
        if (mins < 60) return "${mins}m ago"
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        if (hours < 48) return "${hours}h ago"
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return "${days}d ago"
    }
}
