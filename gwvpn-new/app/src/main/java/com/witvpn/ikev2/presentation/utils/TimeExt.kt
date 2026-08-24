package com.witvpn.ikev2.presentation.utils

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("SimpleDateFormat")
fun Calendar.toStringWithPattern(pattern: String = "HH:mm dd/MM/yyyy"): String {
    val sdf = SimpleDateFormat(pattern, Locale.US)
    return sdf.format(this.time)
}

@SuppressLint("SimpleDateFormat")
fun String.parseApiDate(): String? {
    return try {
        val inputFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.ENGLISH)
        val date = inputFormat.parse(this) ?: return null
        val outputFormat = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
        outputFormat.format(date)
    } catch (e: Exception) {
        null
    }
}