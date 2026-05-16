package com.ambient.tvclock

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CalendarEventAdapter(
    private val context: android.content.Context
) : RecyclerView.Adapter<CalendarEventAdapter.Holder>() {

    private var events: List<CalendarEvent> = emptyList()
    private var nowMillis: Long = System.currentTimeMillis()

    fun submit(events: List<CalendarEvent>, nowMillis: Long) {
        this.events = events
        this.nowMillis = nowMillis
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = events.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_event, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val event = events[position]
        val happening = event.isHappeningNow(nowMillis)
        val past = event.isPast(nowMillis)

        holder.textTime.text = CalendarDisplayHelper.formatEventTime(event)
        holder.textTitle.text = event.title
        holder.textSource.text = CalendarDisplayHelper.sourceLabel(context, event.source)

        if (event.location.isNotEmpty()) {
            holder.textLocation.visibility = View.VISIBLE
            holder.textLocation.text = event.location
        } else {
            holder.textLocation.visibility = View.GONE
        }

        val titleAlpha = when {
            past -> 0.4f
            happening -> 1f
            else -> 1f
        }
        holder.textTitle.alpha = titleAlpha
        holder.textTime.alpha = if (past) 0.4f else 0.85f

        holder.textTitle.setTypeface(
            null,
            if (happening) Typeface.BOLD else Typeface.NORMAL
        )

        val bg = if (happening) R.color.event_now_highlight else android.R.color.transparent
        holder.itemView.setBackgroundColor(
            if (happening) {
                ContextCompat.getColor(context, bg)
            } else {
                android.graphics.Color.TRANSPARENT
            }
        )
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val textTime: TextView = view.findViewById(R.id.textEventTime)
        val textSource: TextView = view.findViewById(R.id.textEventSource)
        val textTitle: TextView = view.findViewById(R.id.textEventTitle)
        val textLocation: TextView = view.findViewById(R.id.textEventLocation)
    }
}
