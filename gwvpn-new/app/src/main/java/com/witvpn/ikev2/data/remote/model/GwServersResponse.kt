package com.witvpn.ikev2.data.remote.model

import com.google.gson.annotations.SerializedName

data class GwServersResponse(
    @SerializedName("success") val success: Int?,
    @SerializedName("error") val error: Int?,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: List<GwServerDto>?
)

data class GwServerDto(
    @SerializedName("meta") val meta: GwServerMetaDto?,
    @SerializedName("enc") val enc: GwEncDto?
)

data class GwServerMetaDto(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("country_code") val countryCode: String?,
    @SerializedName("state") val state: String?,
    @SerializedName("premium") val premium: Boolean?,
    @SerializedName("recommend") val recommend: Boolean?,
    @SerializedName("priority") val priority: Int?,
    @SerializedName("status") val status: Boolean?
)

data class GwEncDto(
    @SerializedName("eph") val eph: String?,
    @SerializedName("ct") val ct: String?,
    @SerializedName("iv") val iv: String?
)
