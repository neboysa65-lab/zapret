package com.witvpn.ikev2.domain.model

import android.icu.text.SimpleDateFormat
import com.witvpn.ikev2.data.remote.model.UserObject
import java.time.format.DateTimeFormatter

data class User(
    val createdAt: String? = null,
    val email: String? = null,
    val firstName: String? = null,
    val id: String? = null,
    val lastName: String? = null,
    val isAnonymous: Boolean = false,
    val hasPremiumSubscribe: Boolean = false,
    val premiumEnd: String? = null
) {
    companion object {
        fun fromObject(userObject: UserObject?): User {
            userObject?.let { userObject ->
                return User(
                    createdAt = userObject.createdAt,
                    email = userObject.email,
                    firstName = userObject.firstName,
                    id = userObject.id,
                    lastName = userObject.lastName,
                    isAnonymous = userObject.isAnonymous,
                    hasPremiumSubscribe = userObject.isPremium,
                    premiumEnd = userObject.premiumEnd
                )           }
            return User()
        }

    }

    var ads: List<Ads> = listOf()
    var packages: List<Package> = listOf()
}