package com.ambient.tvclock

enum class DashboardPage(val index: Int) {
    STATUS(0),
    HOME(1),
    CALENDAR(2),
    MUSIC(3);

    companion object {
        fun fromIndex(index: Int): DashboardPage =
            values().firstOrNull { it.index == index } ?: HOME

        val LAST: DashboardPage get() = values().last()
    }
}
