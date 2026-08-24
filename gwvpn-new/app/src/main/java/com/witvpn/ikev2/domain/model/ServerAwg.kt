package com.witvpn.ikev2.domain.model

data class ServerAwg(
    val ipAddress: String,
    val country: String,
    val state: String?,
    val countryCode: String,
    val premium: Boolean,
    val config: String,
    val status: Boolean,
    val priority: Int,
    val recommend: Boolean
)
