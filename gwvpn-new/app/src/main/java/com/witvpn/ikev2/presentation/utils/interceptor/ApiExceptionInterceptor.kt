package com.witvpn.ikev2.presentation.utils.interceptor

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import java.nio.charset.Charset
import javax.inject.Inject

class ApiExceptionInterceptor @Inject constructor() : Interceptor {

    @Throws(Exception::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.isSuccessful) {
            val responseBody = response.body ?: return response
            val source = responseBody.source()
            source.request(Long.MAX_VALUE);// request the entire body.

            val buffer: Buffer = source.buffer
            val responseBodyString = buffer.clone().readString(Charset.forName("UTF-8"))
            try {
                val json = JSONObject(responseBodyString)
                val error = json.getInt("success")
                if (error == 0) {

                    return Response.Builder()
                        .code(500)
                        .body(ResponseBody.create(responseBody.contentType(), responseBody.contentLength(), buffer))
                        .protocol(Protocol.HTTP_2)
                        .message(json.getString("message"))
                        .request(chain.request())
                        .build()
                }
            } catch (e: Exception) {
                // not json
                return response
            }
        }

        return response
    }
}