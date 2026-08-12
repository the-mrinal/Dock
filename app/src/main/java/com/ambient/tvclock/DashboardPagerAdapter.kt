package com.ambient.tvclock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Pager adapter that caches the inflated page view *and* the binder attached to it
 * inside each [PageHolder]. ViewPager2 calls [onBindViewHolder] more than once per
 * page during pager updates and restores; without this cache we previously allocated
 * a fresh `HomeScreenBinder` / `CalendarScreenBinder` / `MusicScreenBinder` every
 * rebind, throwing away artwork / focus state and re-running `findViewById`.
 *
 * The page list is fixed for the adapter's lifetime; when the set of enabled
 * pages changes (settings toggle), MainActivity swaps in a new adapter.
 */
class DashboardPagerAdapter(
    private val pages: List<DashboardPage>,
    private val onPageReady: (DashboardPage, View, isNew: Boolean) -> Unit
) : RecyclerView.Adapter<DashboardPagerAdapter.PageHolder>() {

    override fun getItemCount(): Int = pages.size

    // viewType is the enum ordinal so each page keeps a stable holder identity
    // regardless of its position in the (runtime-dependent) page list.
    override fun getItemViewType(position: Int): Int = pages[position].ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val page = DashboardPage.entries[viewType]
        val view = LayoutInflater.from(parent.context).inflate(page.layoutRes, parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val isNew = !holder.bound
        holder.bound = true
        onPageReady(pages[position], holder.itemView, isNew)
    }

    class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var bound: Boolean = false
    }
}
