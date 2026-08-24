package com.witvpn.ikev2.presentation.utils.interceptor

import com.google.gson.Gson
import com.witvpn.ikev2.BuildConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

class ModifyRequestInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalPost = originalRequest.body.bodyToString()
        val hashRequestObject = HashRequestObject()

        val modifyPost: String
        val newPost: String
        val newType: String
        if (originalRequest.body is FormBody) {
            modifyPost = FormBody.Builder()
                .add("hash", hashRequestObject.hash ?: "")
                .add("time", hashRequestObject.time ?: "")
                .add("bundleId", hashRequestObject.bundleId ?: "")
                .build()
                .bodyToString()

            newPost = "$originalPost&$modifyPost"
            newType = "application/x-www-form-urlencoded;charset=UTF-8"
        } else {
            modifyPost = hashRequestObject.toJson()
            newPost = originalPost.jsonMergeJson(modifyPost)
            newType = "application/json; charset=utf-8"
        }

        val newRequest = originalRequest.newBuilder()
            .post(RequestBody.create(newType.toMediaTypeOrNull(), newPost))
            .build()

        return chain.proceed(newRequest)
    }

    private fun RequestBody?.bodyToString(): String {
        return try {
            val copy = this
            val buffer = Buffer()
            copy?.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: IOException) {
            Timber.i("#error - bodyToString : ${e.localizedMessage}")
            ""
        }
    }
}

private fun String.jsonMergeJson(modify: String): String {
    return this.replace("}", ",").plus(modify.replace("{", ""))
}

class HashRequestObject {
    var hash: String? = null
    var time: String? = null
    var bundleId: String? = null

    init {
        val unixtime = System.currentTimeMillis() / 1000L
        val tmp: String = "$unixtime|strongVPN!@#"

        bundleId = BuildConfig.APPLICATION_ID
        time = unixtime.toString()
        hash = tmp.hash256()
    }

    fun toJson(): String {
        return Gson().toJson(this)
    }

    private fun String.hash256(): String {
        val bytes = this.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("", { str, it -> str + "%02x".format(it) })
    }

}