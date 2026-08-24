package com.witvpn.ikev2.features.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri

private const val id = "ZAo_7z5mSFY2OWFi"

fun Context.launchTelegram(){
    val intent = try {
        try {
            //Check for Telegram Messenger App
            packageManager?.getPackageInfo("org.telegram.messenger", 0)
        } catch (e: Exception) {
            //Check for Telegram X App
            packageManager?.getPackageInfo("org.thunderdog.challegram", 0)
        }
        Intent(Intent.ACTION_VIEW, Uri.parse("tg://join?invite=$id"))
    } catch (e: Exception) { //App not found open in browser
        Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/+$id"))
    }
    try {
        startActivity(intent)
    } catch (_: Exception) {
        // No activity found to handle intent
    }
}