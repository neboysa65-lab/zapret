package com.witvpn.ikev2.data.remote.model

import com.google.gson.annotations.SerializedName

data class PackagesResponse(
    val data: List<PackageObject>,
    val success: Int
)

data class PackageObject(
    @SerializedName("createdAt")
    var createdAt: String?,
    @SerializedName("id")
    var id: String?,
    @SerializedName("packageDuration")
    var packageDuration: String?,
    @SerializedName("packageId")
    var packageId: String?,
    @SerializedName("packageName")
    var packageName: String?,
    @SerializedName("packagePlatform")
    var packagePlatform: String?,
    @SerializedName("packagePricing")
    var packagePricing: Int?,
    @SerializedName("__v")
    var v: Int?
)