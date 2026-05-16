package com.ambient.tvclock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class DashboardPagerAdapter(
    private val onPageBound: (DashboardPage, View) -> Unit
) : RecyclerView.Adapter<DashboardPagerAdapter.PageHolder>() {

    private val layouts = intArrayOf(
        R.layout.screen_home,
        R.layout.screen_calendar,
        R.layout.screen_music
    )

    override fun getItemCount(): Int = layouts.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        onPageBound(DashboardPage.fromIndex(position), holder.itemView)
    }

    class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
