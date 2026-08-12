package com.ambient.tvclock

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Thin "Protected" ad-block status card. Does no filtering itself — it fetches a
 * one-shot summary from the homelab dashboard (`/api/summary`) and displays it.
 * All the real work (DNS filtering, debloat, monitoring) lives on the homelab.
 *
 * Single card, one focusable Refresh button — mirrors [StatusScreenBinder]'s
 * visual language (dot + halo + big status + detail + action).
 */
class AdBlockScreenBinder(
    private val root: View,
    private val onOpenDashboard: () -> Unit,
) {
    private val dot: View = root.findViewById(R.id.adblockStatusDot)
    private val halo: View = root.findViewById(R.id.adblockStatusHalo)
    private val statusText: TextView = root.findViewById(R.id.textAdblockStatus)
    private val detailText: TextView = root.findViewById(R.id.textAdblockDetail)
    val actionButton: TextView = root.findViewById(R.id.buttonAdblockAction)

    init {
        actionButton.setOnClickListener { onOpenDashboard() }
        showLoading()
    }

    private fun showLoading() {
        tint(R.color.status_idle)
        statusText.text = root.context.getString(R.string.adblock_status_checking)
        detailText.text = root.context.getString(R.string.adblock_detail_checking)
    }

    /** Fetch the block summary from the dashboard and rebind. Safe to call often. */
    fun refresh() {
        val url = AdBlockPreferences.getSummaryUrl(root.context)
        val request = Request.Builder().url(url).build()
        HttpClients.shared.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                root.post { bindUnavailable() }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val body = response.body?.string()
                response.close()
                val summary = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                root.post {
                    if (summary == null || !response.isSuccessful) bindUnavailable()
                    else bind(summary)
                }
            }
        })
    }

    private fun bind(summary: JSONObject) {
        val context = root.context
        val available = summary.optBoolean("available", false)
        val protected = summary.optBoolean("protected", false)
        if (!available) {
            bindUnavailable()
            return
        }
        val blocked = summary.optInt("blocked_today", 0)
        val queries = summary.optInt("queries_today", 0)
        val rate = summary.optDouble("block_rate", 0.0)

        if (protected) {
            tint(R.color.status_ok)
            statusText.text = context.getString(R.string.adblock_status_protected)
        } else {
            tint(R.color.status_pending)
            statusText.text = context.getString(R.string.adblock_status_unprotected)
        }
        detailText.text = context.getString(
            R.string.adblock_detail_stats,
            formatCount(blocked),
            formatCount(queries),
            rate,
        )
    }

    private fun bindUnavailable() {
        tint(R.color.status_error)
        statusText.text = root.context.getString(R.string.adblock_status_unavailable)
        detailText.text = root.context.getString(R.string.adblock_detail_unavailable)
    }

    private fun formatCount(n: Int): String =
        if (n >= 1000) String.format("%,d", n) else n.toString()

    private fun tint(colorRes: Int) {
        val color = ContextCompat.getColor(root.context, colorRes)
        dot.background?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        halo.backgroundTintList = ColorStateList.valueOf(haloTint(color))
    }

    private fun haloTint(color: Int): Int {
        val alpha = 0x24
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
