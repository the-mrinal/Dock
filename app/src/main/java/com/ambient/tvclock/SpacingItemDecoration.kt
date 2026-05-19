package com.ambient.tvclock

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Even gutters between grid items in a [androidx.recyclerview.widget.GridLayoutManager].
 *
 * Used by the Music page Browse (4-col playlist grid, 28dp gap) and the
 * NowPlaying Recently Played row (5-col card grid, 24dp gap). Splits the
 * gap evenly between adjacent items so the grid stays flush to the parent
 * edges — no half-gap at the start/end.
 */
class SpacingItemDecoration private constructor(
    private val gapPx: Int,
    private val spanCount: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount
        // Split the inter-item gap evenly between each item's left+right edges.
        val half = gapPx / 2
        outRect.left = if (column == 0) 0 else half
        outRect.right = if (column == spanCount - 1) 0 else half
        // Vertical gap between rows.
        if (position >= spanCount) {
            outRect.top = gapPx
        }
    }

    companion object {
        fun grid(context: Context, gapDp: Int, spanCount: Int): SpacingItemDecoration {
            val px = (gapDp * context.resources.displayMetrics.density).toInt()
            return SpacingItemDecoration(px, spanCount)
        }
    }
}
