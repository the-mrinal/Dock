package com.ambient.tvclock

import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class CalendarEventAdapter(
    private val context: android.content.Context
) : ListAdapter<CalendarEventAdapter.Row, CalendarEventAdapter.Holder>(DIFF) {

    data class Row(
        val event: CalendarEvent,
        val isHappening: Boolean,
        val isPast: Boolean
    )

    fun submit(events: List<CalendarEvent>, nowMillis: Long) {
        submitList(
            events.map { Row(it, it.isHappeningNow(nowMillis), it.isPast(nowMillis)) }
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_event, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val event = row.event
        val happening = row.isHappening
        val past = row.isPast

        if (event.isAllDay) {
            holder.textTimeStart.text = context.getString(R.string.calendar_all_day)
            holder.textTimeEnd.visibility = View.GONE
        } else {
            holder.textTimeStart.text = CalendarDisplayHelper.formatTime(event.startMillis)
            holder.textTimeEnd.visibility = View.VISIBLE
            holder.textTimeEnd.text = CalendarDisplayHelper.formatTime(event.endMillis)
        }

        holder.textTitle.text = event.title
        holder.textSource.text = CalendarDisplayHelper.sourceLabel(context, event.source)
        holder.textSource.setBackgroundResource(
            when (event.source) {
                CalendarSource.PERSONAL -> R.drawable.bg_chip_personal
                CalendarSource.WORK -> R.drawable.bg_chip_work
            }
        )
        holder.sourceRail.setBackgroundResource(
            when (event.source) {
                CalendarSource.PERSONAL -> R.drawable.bg_rail_personal
                CalendarSource.WORK -> R.drawable.bg_rail_work
            }
        )

        if (event.location.isNotEmpty()) {
            holder.textLocation.visibility = View.VISIBLE
            holder.textLocation.text = event.location
        } else {
            holder.textLocation.visibility = View.GONE
        }

        if (happening) {
            holder.nowBadge.visibility = View.VISIBLE
            holder.row.setBackgroundResource(R.drawable.bg_event_row_now)
        } else {
            holder.nowBadge.visibility = View.GONE
            holder.row.setBackgroundResource(android.R.color.transparent)
        }

        holder.textTitle.setTypeface(null, if (happening) Typeface.BOLD else Typeface.NORMAL)

        val titleAlpha = if (past) 0.45f else 1f
        holder.textTitle.alpha = titleAlpha
        holder.textTimeStart.alpha = if (past) 0.45f else 0.9f
        holder.textTimeEnd.alpha = if (past) 0.45f else 0.65f
        holder.textSource.alpha = if (past) 0.55f else 1f

        val strike = past
        holder.textTimeStart.paintFlags =
            if (strike) holder.textTimeStart.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.textTimeStart.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        holder.textTimeEnd.paintFlags =
            if (strike) holder.textTimeEnd.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.textTimeEnd.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.eventRow)
        val sourceRail: View = view.findViewById(R.id.eventSourceRail)
        val textTimeStart: TextView = view.findViewById(R.id.textEventTimeStart)
        val textTimeEnd: TextView = view.findViewById(R.id.textEventTimeEnd)
        val textSource: TextView = view.findViewById(R.id.textEventSource)
        val textTitle: TextView = view.findViewById(R.id.textEventTitle)
        val textLocation: TextView = view.findViewById(R.id.textEventLocation)
        val nowBadge: TextView = view.findViewById(R.id.textEventNowBadge)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean {
                return oldItem.event.startMillis == newItem.event.startMillis &&
                    oldItem.event.title == newItem.event.title &&
                    oldItem.event.source == newItem.event.source
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean {
                return oldItem == newItem
            }
        }
    }
}
