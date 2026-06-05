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
 */
class DashboardPagerAdapter(
    private val onPageReady: (DashboardPage, View, isNew: Boolean) -> Unit
) : RecyclerView.Adapter<DashboardPagerAdapter.PageHolder>() {

    // Order matches DashboardPage indices: STATUS, HOME, CALENDAR, MUSIC, MEAL.
    private val layouts = intArrayOf(
        R.layout.screen_status,
        R.layout.screen_home,
        R.layout.screen_calendar,
        R.layout.screen_music,
        R.layout.screen_meal
    )

    override fun getItemCount(): Int = layouts.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val isNew = !holder.bound
        holder.bound = true
        onPageReady(DashboardPage.fromIndex(position), holder.itemView, isNew)
    }

    class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var bound: Boolean = false
    }
}
