package com.witvpn.ikev2.presentation.utils

import android.annotation.SuppressLint
import android.provider.Settings
import android.util.Base64
import android.util.Patterns
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.MyApp
import org.strongswan.android.logic.TrustedCertificateManager
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
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

//    fun getDeviceInfo(context: Context): Map<String, String> {
//        val easyDeviceMod = EasyDeviceMod(context)
//        return mutableMapOf(
//            "imei" to Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
//            "screen_display_id" to easyDeviceMod.screenDisplayID,
//            "model" to easyDeviceMod.model,
//            "manufacturer" to easyDeviceMod.manufacturer,
//            "os_codename" to easyDeviceMod.osCodename,
//            "os_version" to easyDeviceMod.osVersion,
//            "product" to easyDeviceMod.product,
//            "hardware" to easyDeviceMod.hardware,
//            "display_version" to easyDeviceMod.displayVersion,
//        )
//    }

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

fun String?.parseCertificate(): X509Certificate? {

    var certificate: X509Certificate? = null
    val data: ByteArray
    try {
        data = Base64.decode(this, Base64.DEFAULT)
        val factory = CertificateFactory.getInstance("X.509")
        val inputStream = ByteArrayInputStream(data)
        certificate = factory.generateCertificate(inputStream) as X509Certificate
    } catch (e: Exception) {
        e.printStackTrace()
    } catch (e: CertificateException) {
        e.printStackTrace()
    } catch (e: FileNotFoundException) {
        e.printStackTrace()
    }

    return certificate
}

fun String.isValidEmail() = Patterns.EMAIL_ADDRESS.matcher(this).matches()

fun X509Certificate?.storeCertificate(): Boolean {
    return try {
        KeyStore.getInstance("LocalCertificateStore")
            .apply {
                load(null, null)
                setCertificateEntry(null, this@storeCertificate)
                TrustedCertificateManager.getInstance().reset()
            }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}