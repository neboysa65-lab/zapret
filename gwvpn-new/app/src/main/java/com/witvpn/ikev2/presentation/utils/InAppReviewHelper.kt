package com.witvpn.ikev2.presentation.utils

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import timber.log.Timber

object InAppReviewHelper {
    fun requestInAppReviewGPS(activity: Activity) {
        val manager = ReviewManagerFactory.create(activity)
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCanceledListener {
                    Timber.d("Review Canceled")
                }
                flow.addOnCompleteListener {
                    Timber.d("Review Complete")
                }
                flow.addOnFailureListener {
                    Timber.d("Review Failure")
                }
                flow.addOnSuccessListener {
                    Timber.d("Review Success")
                }
            } else {
                Timber.e(task.exception)
            }
        }
    }

    fun requestInAppReview(activity: Activity) {
        if (GooglePlayHelper.deviceHasGooglePlayServices(activity)) {
            requestInAppReviewGPS(activity)
        }
    }
}