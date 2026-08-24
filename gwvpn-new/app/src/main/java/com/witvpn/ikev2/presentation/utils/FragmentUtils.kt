package com.witvpn.ikev2.presentation.utils

import android.util.Log
import androidx.annotation.NonNull
import androidx.fragment.app.Fragment

object FragmentUtils {
    fun <T> getParent(@NonNull fragment: Fragment, @NonNull callbackInterface: Class<T>): T? {
        val parentFragment = fragment.parentFragment
        val fragmentActivity = fragment.activity

        if (callbackInterface.isInstance(parentFragment)) {
            return parentFragment as T
        }

        if (callbackInterface.isInstance(fragmentActivity)) {
            return fragmentActivity as T
        }
        Log.w("FragmentUtil", "$callbackInterface not found")
        return null
    }
}