package com.witvpn.ikev2.presentation.utils

import android.content.Context
import com.witvpn.ikev2.presentation.MyApp

private const val SharePref_DB = "myPref"

fun Any?.putStringPref(key: String, value: String) {
    MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.edit()
        ?.putString(key, value)
        ?.apply()
}

fun Any?.getStringPref(key: String, default: String? = null): String? {
    return MyApp.self.applicationContext.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.getString(key, default)
}

fun Any?.putBooleanPref(key: String, value: Boolean) {
    MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.edit()
        ?.putBoolean(key, value)
        ?.apply()
}

fun Any?.clearAllPref() {
    MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.edit()
        ?.clear()
        ?.apply()
}

fun Any?.removePref(key: String) {
    MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.edit()
        ?.remove(key)
        ?.apply()
}

fun Any?.getBooleanPref(key: String): Boolean {
    return MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.getBoolean(key, false) ?: false
}

fun Any?.getBooleanPref(key: String, default: Boolean): Boolean {
    return MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.getBoolean(key, default) ?: default
}

fun Any?.putIntPref(key: String, value: Int) {
    MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.edit()
        ?.putInt(key, value)
        ?.apply()
}

fun Any?.getIntPref(key: String, default: Int = 0): Int {
    return MyApp.self.applicationContext?.getSharedPreferences(SharePref_DB, Context.MODE_PRIVATE)
        ?.getInt(key, default) ?: default
}