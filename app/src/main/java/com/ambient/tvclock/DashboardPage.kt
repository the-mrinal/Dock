package com.ambient.tvclock

enum class DashboardPage(val index: Int) {
    HOME(0),
    CALENDAR(1),
    MUSIC(2);

    companion object {
        fun fromIndex(index: Int): DashboardPage =
            values().firstOrNull { it.index == index } ?: HOME
    }
}
