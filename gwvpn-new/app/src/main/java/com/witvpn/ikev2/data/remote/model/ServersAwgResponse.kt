package com.witvpn.ikev2.data.remote.model

import com.google.gson.annotations.SerializedName

data class ServersAwgResponse(
    @SerializedName("success") val success: Int?,
    @SerializedName("error") val error: Int?,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: List<ServerAwgDto>?
)

data class ServerAwgDto(
    @SerializedName("ip_address") val ipAddress: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("state") val state: String?,
    @SerializedName("country_code") val countryCode: String?,
    @SerializedName("premium") val premium: Boolean?,
    @SerializedName("config") val config: String?,
    @SerializedName("status") val status: Boolean?,
    @SerializedName("priority") val priority: Int?,
    @SerializedName("recommend") val recommend: Boolean?
)
