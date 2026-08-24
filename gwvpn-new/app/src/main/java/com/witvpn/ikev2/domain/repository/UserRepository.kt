package com.witvpn.ikev2.domain.repository

import com.witvpn.ikev2.domain.model.Ads
import com.witvpn.ikev2.domain.model.Package
import com.witvpn.ikev2.domain.model.User

interface UserRepository {
    suspend fun login(param: MutableMap<String, Any>): User

    suspend fun register(param: MutableMap<String, Any>): User

    suspend fun otp(param: MutableMap<String, Any>): User

    suspend fun profile(param: MutableMap<String, Any>): User

    suspend fun createAnonymousUser(param: MutableMap<String, Any>): User

    suspend fun updateTotalUploadDownload(param: MutableMap<String, Any>): User

    suspend fun packages(param: MutableMap<String, Any>): List<Package>

    suspend fun ads(param: MutableMap<String, Any>): List<Ads>

    suspend fun subscription(param: MutableMap<String, Any>)
}