package com.ambient.tvclock.receiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.ambient.tvclock.R
import com.ambient.tvclock.receiver.Protocol
import com.ambient.tvclock.util.Logger

/**
 * Full-bleed SurfaceView container that hosts the AirPlay video stream.
 *
 * Sits inside MainActivity's root container; visibility is toggled by the
 * activeConnection observer. [currentSurface] returns the live Surface or
 * null when the SurfaceView hasn't been created yet (or has been destroyed),
 * matching the contract VideoDecoder expects.
 */
class StreamingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val surfaceView: SurfaceView = SurfaceView(context)
    val senderPill: TextView = TextView(context)

    @Volatile
    private var surface: Surface? = null

    init {
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                Logger.d("StreamingOverlay: surface created")
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Logger.d("StreamingOverlay: surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                Logger.d("StreamingOverlay: surface destroyed")
            }
        })

        senderPill.apply {
            setTextColor(Color.parseColor("#F5F5F5"))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.bg_sender_pill)
            val paddingX = dp(16)
            val paddingY = dp(6)
            setPadding(paddingX, paddingY, paddingX, paddingY)
        }
        val pillLayout = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            val margin = dp(24)
            setMargins(margin, margin, margin, margin)
        }
        addView(senderPill, pillLayout)
    }

    fun currentSurface(): Surface? = surface

    fun setSenderInfo(senderName: String, protocol: Protocol) {
        val template = when (protocol) {
            Protocol.AIRPLAY -> R.string.streaming_pill_airplay
            Protocol.CAST -> R.string.streaming_pill_cast
            Protocol.MIRACAST -> R.string.streaming_pill_miracast
        }
        senderPill.text = context.getString(template, senderName)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
