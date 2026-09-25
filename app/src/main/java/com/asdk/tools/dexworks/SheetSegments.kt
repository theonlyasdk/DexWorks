package com.asdk.tools.dexworks

import android.view.View
import androidx.core.view.isVisible

/**
 * Assigns rounded segment backgrounds to a group of action rows so the first and last
 * visible rows always get the rounded ends, even when some rows are hidden.
 *
 * [dividerIds] must be ordered to match the gaps between consecutive entries of [rowIds].
 */
fun applySegmentCorners(
    content: View,
    rowIds: List<Int>,
    dividerIds: List<Int>
) {
    val rows = rowIds.map { content.findViewById<View>(it) }
    val dividers = dividerIds.map { content.findViewById<View>(it) }

    val visible = rows.filter { it.isVisible }
    val visibleIds = visible.map { it.id }.toSet()

    visible.forEachIndexed { index, row ->
        val backgroundRes = when {
            visible.size == 1 -> R.drawable.bg_sheet_action_segment_single
            index == 0 -> R.drawable.bg_sheet_action_segment_top
            index == visible.lastIndex -> R.drawable.bg_sheet_action_segment_bottom
            else -> R.drawable.bg_sheet_action_segment_middle
        }
        row.setBackgroundResource(backgroundRes)
    }

    dividers.forEachIndexed { index, divider ->
        if (index + 1 < rowIds.size) {
            divider.isVisible = rowIds[index] in visibleIds && rowIds[index + 1] in visibleIds
        }
    }
}
