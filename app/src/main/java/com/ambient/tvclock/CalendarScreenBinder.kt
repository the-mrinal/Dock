package com.ambient.tvclock

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarScreenBinder(private val root: View) {

    private val textTitle: TextView = root.findViewById(R.id.textCalendarScreenTitle)
    private val textDate: TextView = root.findViewById(R.id.textCalendarScreenDate)
    private val textFooter: TextView = root.findViewById(R.id.textCalendarFooter)
    private val recycler: RecyclerView = root.findViewById(R.id.recyclerCalendarEvents)
    private val adapter = CalendarEventAdapter(root.context)

    private val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    init {
        recycler.layoutManager = LinearLayoutManager(root.context)
        recycler.adapter = adapter
        textTitle.text = root.context.getString(R.string.calendar_screen_title)
    }

    fun updateDateLine() {
        textDate.text = dateFormatter.format(Calendar.getInstance().time)
    }

    fun bind(snapshot: CalendarSnapshot) {
        updateDateLine()
        val context = root.context
        val now = System.currentTimeMillis()

        if (!CalendarPreferences.isEnabled(context)) {
            adapter.submit(emptyList(), now)
            textFooter.text = ""
            return
        }

        if (CalendarPreferences.getPersonalUrl(context).isBlank() &&
            CalendarPreferences.getWorkUrl(context).isBlank()
        ) {
            adapter.submit(emptyList(), now)
            textFooter.text = context.getString(R.string.calendar_add_in_settings)
            return
        }

        adapter.submit(snapshot.events, now)
        textFooter.text = when {
            snapshot.errorMessage != null && snapshot.events.isEmpty() ->
                context.getString(R.string.calendar_fetch_error)
            snapshot.lastUpdatedMillis > 0 ->
                context.getString(
                    R.string.calendar_last_updated,
                    CalendarDisplayHelper.formatUpdated(snapshot.lastUpdatedMillis)
                )
            else -> ""
        }
    }
}
