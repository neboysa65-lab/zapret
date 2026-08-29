package com.witvpn.ikev2.presentation.utils

import android.annotation.SuppressLint
import android.provider.Settings
import android.util.Patterns
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.MyApp
import java.util.*

@SuppressLint("HardwareIds")
object Util {
    val anonymousId: String by lazy {
        return@lazy Settings.Secure.getString(MyApp.self.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun validateEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    fun validatePassword(password: String, confirmPassword: String): Boolean {
        return password == confirmPassword
    }

    fun getResId(input: String?): Int? {
        if (input.isNullOrBlank()) return null

        val inputTmp = input.lowercase(Locale.ROOT)

        var result = inputTmp.toResId()
        if (result == -1) {
            result = "ic_$inputTmp".toResId()
        }

        if (result == -1 && inputTmp.isNotEmpty()) {
            result = "ic_${inputTmp.subSequence(0, 1)}".toResId()
        }

        // Не найдено ни одного ресурса — возвращаем null, чтобы вызывающий
        // код применил свой fallback (? : ic_globe), а не упал на setImageResource(-1).
        return if (result == -1) null else result
    }

}

fun String.toResId(): Int {
    return try {
        val clazz = R.drawable::class.java
        val field = clazz.getDeclaredField(this)
        field.getInt(field)
    } catch (exception: Exception) {
        -1
    }
}

fun String.isValidEmail() = Patterns.EMAIL_ADDRESS.matcher(this).matches()
