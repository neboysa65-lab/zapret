package com.witvpn.ikev2.data.remote.model

import com.google.gson.annotations.SerializedName

data class ServersLoadResponse(
    @SerializedName("success") val success: Int,
    @SerializedName("data") val data: Map<String, Int>
)
