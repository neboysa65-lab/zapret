package com.witvpn.ikev2.domain.model

import com.witvpn.ikev2.data.remote.model.PackageObject

data class Package(
    var __v: Int?,
    var createdAt: String?,
    var id: String?,
    var packageDuration: String?,
    var packageId: String?,
    var packageName: String?,
    var packagePlatform: String?,
    var packagePricing: Int?
) {
    companion object {
        fun fromObject(packageObject: PackageObject): Package {
            return Package(
                __v = packageObject.v,
                createdAt = packageObject.createdAt,
                id = packageObject.id,
                packageDuration = packageObject.packageDuration,
                packageId = packageObject.packageId,
                packageName = packageObject.packageName,
                packagePricing = packageObject.packagePricing,
                packagePlatform = packageObject.packagePlatform
            )
        }
    }
}