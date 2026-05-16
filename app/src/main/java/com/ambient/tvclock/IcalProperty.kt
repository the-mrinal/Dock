package com.ambient.tvclock

data class IcalProperty(
    val name: String,
    val params: Map<String, String>,
    val value: String
) {
    fun param(name: String): String? = params[name.uppercase()]
}
