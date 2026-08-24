package com.witvpn.ikev2.data.remote.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    val data: UserObject? = null,
    val success: Int? = null,
    val message: String? = null
)

data class UserObject(
    @SerializedName("createdAt")
    val createdAt: String? = null,
    val email: String? = null,
    val firstName: String? = null,
    val id: String? = null,
    val lastName: String? = null,
    @SerializedName("isAnonymous")
    val isAnonymous: Boolean = false,
    @SerializedName("isPremium")
    val isPremium: Boolean = false,
    @SerializedName("premiumEnd")
    val premiumEnd: String? = null
)