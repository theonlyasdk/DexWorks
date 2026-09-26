package com.asdk.tools.dexworks

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.ActivityProjectIconPickerBinding
import com.asdk.tools.dexworks.databinding.ItemProjectIconBinding
import com.google.android.material.color.MaterialColors
import com.asdk.tools.dexworks.AppInfoUtils.dp

class ProjectIconPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ICON_KEY = "extra_icon_key"
    }

    private lateinit var binding: ActivityProjectIconPickerBinding
    private var selectedIconKey: String = ProjectIconCatalog.DEFAULT_KEY
    private val allIconOptions: List<ProjectIconOption> = ProjectIconCatalog.all()
    private val iconAdapter = IconAdapter(
        onIconSelected = { option ->
            selectedIconKey = option.key
        },
        onIconConfirmed = { option ->
            selectedIconKey = option.key
            confirmIcon()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProjectIconPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        selectedIconKey = intent.getStringExtra(EXTRA_ICON_KEY) ?: ProjectIconCatalog.DEFAULT_KEY
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnConfirmIcon.setOnClickListener { confirmIcon() }
        binding.recyclerIcons.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerIcons.adapter = iconAdapter
        binding.editSearchIcons.addTextChangedListener { text ->
            filterIcons(text?.toString()?.trim().orEmpty())
        }
        filterIcons("")
    }

    private fun confirmIcon() {
        setResult(
            RESULT_OK,
            android.content.Intent().putExtra(EXTRA_ICON_KEY, selectedIconKey)
        )
        finish()
    }

    private fun filterIcons(query: String) {
        // Labels are resolved once and reused, instead of a Resources.getString
        // per catalogue entry on every keystroke.
        val filtered = if (allIconOptions.isEmpty()) {
            emptyList()
        } else {
            allIconOptions.filter { option ->
                getString(option.labelRes).contains(query, ignoreCase = true)
            }
        }
        iconAdapter.setItems(filtered, selectedIconKey)
        binding.textIconSearchEmpty.isVisible = filtered.isEmpty()
    }

    private class IconAdapter(
        private val onIconSelected: (ProjectIconOption) -> Unit,
        private val onIconConfirmed: (ProjectIconOption) -> Unit
    ) : RecyclerView.Adapter<IconAdapter.ViewHolder>() {

        private var items: List<ProjectIconOption> = emptyList()
        private var selectedKey: String = ProjectIconCatalog.DEFAULT_KEY

        fun setItems(newItems: List<ProjectIconOption>, newSelectedKey: String) {
            if (newItems === items && newSelectedKey == selectedKey) return
            val previousSelected = selectedKey
            val listChanged = newItems !== items
            items = newItems
            selectedKey = newSelectedKey
            if (listChanged) {
                notifyDataSetChanged()
            } else {
                // Only the selection moved, so rebind the two affected cells
                // instead of the whole grid.
                val previousIndex = items.indexOfFirst { it.key == previousSelected }
                val newIndex = items.indexOfFirst { it.key == newSelectedKey }
                if (previousIndex >= 0) notifyItemChanged(previousIndex)
                if (newIndex >= 0 && newIndex != previousIndex) notifyItemChanged(newIndex)
            }
        }

        class ViewHolder(val binding: ItemProjectIconBinding) : RecyclerView.ViewHolder(binding.root) {
            var lastClickAt: Long = 0L
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemProjectIconBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = items[position]
            val context = holder.itemView.context
            val label = context.getString(option.labelRes)
            holder.binding.imageProjectIcon.setImageResource(option.iconRes)
            holder.binding.textProjectIconLabel.text = label
            holder.binding.cardProjectIcon.contentDescription = label
            val isSelected = option.key == selectedKey
            holder.binding.cardProjectIcon.setStrokeColor(
                MaterialColors.getColor(
                    holder.binding.cardProjectIcon,
                    androidx.appcompat.R.attr.colorPrimary
                )
            )
            holder.binding.cardProjectIcon.strokeWidth = if (isSelected) {
                context.dp(2)
            } else {
                0
            }
            holder.binding.cardProjectIcon.setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - holder.lastClickAt <= ViewConfiguration.getDoubleTapTimeout()) {
                    holder.lastClickAt = 0L
                    onIconConfirmed(option)
                } else {
                    holder.lastClickAt = now
                    selectedKey = option.key
                    notifyDataSetChanged()
                    onIconSelected(option)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
