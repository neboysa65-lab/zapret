package com.witvpn.ikev2.data.repository

import com.witvpn.ikev2.data.remote.ApiService
import com.witvpn.ikev2.domain.repository.PayRepository
import com.witvpn.ikev2.domain.repository.PayRepository.Plan
import com.witvpn.ikev2.domain.repository.PayRepository.Companion.FK2_SYSTEM_NAME
import javax.inject.Inject

class PayRepositoryImpl @Inject constructor(): PayRepository {
    @Inject
    lateinit var apiService: ApiService

    override suspend fun getPayFK2Url(userID: String, plan: Plan) =
        apiService.getPayUrl(userID, FK2_SYSTEM_NAME, when(plan) {
            Plan.ONE_MONTH -> "month1"
            Plan.THREE_MONTH -> "month3"
            Plan.SIX_MONTH -> "month6"
        }).string()
}