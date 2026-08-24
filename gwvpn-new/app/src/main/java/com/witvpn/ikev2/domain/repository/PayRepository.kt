package com.witvpn.ikev2.domain.repository

interface PayRepository {
    companion object {
        const val FK2_SYSTEM_NAME = "fk2"
    }
    enum class Plan {
        ONE_MONTH,
        THREE_MONTH,
        SIX_MONTH
    }
    suspend fun getPayFK2Url(userID: String, plan: Plan): String
}