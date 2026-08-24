package com.witvpn.ikev2.data.remote

import com.witvpn.ikev2.data.remote.model.AdsResponse
import com.witvpn.ikev2.data.remote.model.LoginResponse
import com.witvpn.ikev2.data.remote.model.PackagesResponse
import com.witvpn.ikev2.data.remote.model.ServersResponse
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.*

interface ApiService {
    @POST("user/server")
    @FormUrlEncoded
    suspend fun getServers(@FieldMap param: MutableMap<String, Any>): ServersResponse

    @POST("user/server_awg")
    @FormUrlEncoded
    suspend fun getServersAwg(@FieldMap param: MutableMap<String, Any>): com.witvpn.ikev2.data.remote.model.ServersAwgResponse

    @POST("user/server_gw")
    @FormUrlEncoded
    suspend fun getServersGw(@FieldMap param: MutableMap<String, Any>): com.witvpn.ikev2.data.remote.model.GwServersResponse

    @POST("user/login")
    @FormUrlEncoded
    suspend fun login(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/signup")
    @FormUrlEncoded
    suspend fun signup(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/otp")
    @FormUrlEncoded
    suspend fun otp(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/profile")
    @FormUrlEncoded
    suspend fun profile(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/loginAnonymousUser")
    @FormUrlEncoded
    suspend fun createAnonymousUser(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/updateTotalUploadDownload")
    @FormUrlEncoded
    suspend fun updateTotalUploadDownload(@FieldMap param: MutableMap<String, Any>): LoginResponse

    @POST("user/packages")
    @FormUrlEncoded
    suspend fun packages(@FieldMap param: MutableMap<String, Any>): PackagesResponse

    @POST("user/ads")
    @FormUrlEncoded
    suspend fun ads(@FieldMap param: MutableMap<String, Any>): AdsResponse

    @POST("user/subscription")
    @FormUrlEncoded
    suspend fun subscription(@FieldMap param: MutableMap<String, Any>)

    @GET("getpayurl")
    suspend fun getPayUrl(@Query("userId") userID: String,
                          @Query("system") system: String,
                          @Query("plan") plan: String): ResponseBody
                          
    @POST("user/servers/load")
    @FormUrlEncoded
    suspend fun getServersLoad(@FieldMap param: MutableMap<String, Any>): retrofit2.Response<com.witvpn.ikev2.data.remote.model.ServersLoadResponse>
}