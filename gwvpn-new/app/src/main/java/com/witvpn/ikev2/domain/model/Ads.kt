package com.witvpn.ikev2.domain.model

import com.witvpn.ikev2.data.remote.model.AdsObject

data class Ads(
    var v: Int?,
    var adsId: String?,
    var adsPlatform: String?,
    var adsStatus: Boolean?,
    var adsType: String?,
    var createdAt: String?,
    var id: String?
) {
    companion object {
        fun fromObject(adsObject: AdsObject): Ads {
            return Ads(
                v = adsObject.v,
                adsId = adsObject.adsId,
                adsPlatform = adsObject.adsPlatform,
                adsStatus = adsObject.adsStatus,
                adsType = adsObject.adsType,
                createdAt = adsObject.createdAt,
                id = adsObject.id
            )
        }
    }
}