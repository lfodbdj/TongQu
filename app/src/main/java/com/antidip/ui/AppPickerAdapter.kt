package com.antidip.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.antidip.databinding.ItemExemptAppBinding

/** One launchable app in the parent-console picker. */
data class AppEntry(
    val pkg: String,
    val label: String,
    var checked: Boolean
)

/**
 * Multi-select + search adapter for the "foreground exempt apps" picker.
 *
 * `all` is the single source of truth for checked state; `visible` is only a
 * filtered view onto it, so searching never loses a selection. The master list
 * is small (launcher-resolved apps only), so notifyDataSetChanged() is fine.
 */
class AppPickerAdapter(
    private val onChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var all: List<AppEntry> = emptyList()
    private var visible: List<AppEntry> = emptyList()
    private var query: String = ""

    fun submit(apps: List<AppEntry>) {
        all = apps
        applyFilter()
    }

    fun filter(raw: String) {
        query = raw.trim()
        applyFilter()
    }

    fun checkedPackages(): Set<String> =
        all.filter { it.checked }.mapTo(HashSet()) { it.pkg }

    fun clearChecks() {
        all.forEach { it.checked = false }
        applyFilter()
        onChanged?.invoke()
    }

    private fun applyFilter() {
        visible = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.pkg.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemExemptAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = visible[position]
        // Detach first: RecyclerView reuses holders and isChecked would
        // otherwise fire the previous row's listener and corrupt `all`.
        holder.binding.cbExempt.setOnCheckedChangeListener(null)
        holder.binding.cbExempt.isChecked = entry.checked
        holder.binding.tvAppLabel.text = entry.label
        holder.binding.tvAppPkg.text = entry.pkg
        holder.binding.cbExempt.setOnCheckedChangeListener { _, checked ->
            entry.checked = checked
            onChanged?.invoke()
        }
        holder.itemView.setOnClickListener {
            holder.binding.cbExempt.isChecked = !holder.binding.cbExempt.isChecked
        }
    }

    override fun getItemCount(): Int = visible.size

    class VH(val binding: ItemExemptAppBinding) : RecyclerView.ViewHolder(binding.root)
}
