package com.witvpn.ikev2.presentation.utils

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object GooglePlayHelper {
    fun deviceHasGooglePlayServices(context: Context): Boolean {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return availability == ConnectionResult.SUCCESS
    }
}