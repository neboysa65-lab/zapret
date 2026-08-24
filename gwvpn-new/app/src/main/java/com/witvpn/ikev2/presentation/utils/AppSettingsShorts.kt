package com.witvpn.ikev2.presentation.utils

fun Any.getSessionUserId() = getStringPref(SharePrefs.KEY_USER_ID, null)
fun Any.removeSessionUserId() = removePref(SharePrefs.KEY_USER_ID)
