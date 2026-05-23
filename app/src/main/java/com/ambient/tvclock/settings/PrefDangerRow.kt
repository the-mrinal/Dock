package com.ambient.tvclock.settings

import android.content.Context
import android.util.AttributeSet

/**
 * `PrefRow` variant whose label paints in the work-tag coral (`@color/cal_work_text`)
 * and which uses the same coral for the focus border. Used for destructive
 * actions (e.g. "Remove all calendars…", "Clear config"). Behaviour is
 * otherwise identical to [PrefRow].
 */
class PrefDangerRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PrefRow(context, attrs, defStyleAttr) {

    init {
        applyDangerStyle()
    }
}
