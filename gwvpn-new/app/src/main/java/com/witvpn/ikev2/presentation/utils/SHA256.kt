package com.witvpn.ikev2.presentation.utils

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object SHA256 {
    fun sha256(text: String): String {
        return try {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            val hash: ByteArray = messageDigest.digest(text.toByteArray(StandardCharsets.UTF_8))
            java.lang.String.format("%064x", BigInteger(1, hash))
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            text
        }
    }
}