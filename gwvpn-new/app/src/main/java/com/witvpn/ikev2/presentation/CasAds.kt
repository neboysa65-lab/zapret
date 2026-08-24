package com.witvpn.ikev2.presentation

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cleveradssolutions.sdk.AdContentInfo
import com.cleveradssolutions.sdk.AdFormat
import com.cleveradssolutions.sdk.screen.CASInterstitial
import com.cleveradssolutions.sdk.screen.ScreenAdContentCallback
import com.cleversolutions.ads.AdError
import com.cleversolutions.ads.InitialConfiguration
import com.cleversolutions.ads.InitializationListener
import com.cleversolutions.ads.android.CAS
import com.witvpn.ikev2.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CAS.AI (CleverAdsSolutions) mediation wrapper.
 *
 * Replaces the previous Yandex Mobile Ads integration. Exposes the same surface the UI already
 * relies on: a single interstitial shown to non-premium users when the VPN connection is started
 * or stopped.
 *
 * The SDK's autoload mode handles caching and retry-on-failure internally, so no manual retry
 * loop is needed here.
 *
 * ## Why the watchdogs exist
 *
 * The show callbacks gate the VPN connect flow: whoever calls [showConnectAd] only proceeds once
 * `onFinishOrError` runs. If an ad were shown and the SDK never reported a terminal event, the
 * user would be left unable to connect with no visible error. Every path therefore completes
 * exactly once, and a timeout backs up the SDK callbacks.
 */
object CasAds {

    /** `show()` was called but the ad never reached the screen. */
    private const val SHOW_TIMEOUT_MS = 7_000L

    /** The ad is on screen but no dismissal was ever reported. Generous: video ads are long. */
    private const val DISMISS_TIMEOUT_MS = 300_000L

    private const val PLACEMENT_CONNECT = "vpn_connect"
    private const val PLACEMENT_DISCONNECT = "vpn_disconnect"

    /**
     * CAS ID identifying the app in the CAS dashboard. A build config field rather than
     * [Context.getPackageName], as recommended by CAS.
     */
    private val casId: String get() = BuildConfig.CAS_ID

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** The in-flight show request. Consumed exactly once, by whichever path finishes first. */
    private val pending = AtomicReference<PendingRequest?>(null)
    private val nextRequestId = AtomicLong(1L)

    @Volatile
    private var timeoutRunnable: Runnable? = null

    @Volatile
    private var interstitialAd: CASInterstitial? = null

    @Volatile
    private var initialized = false

    private class PendingRequest(val id: Long, val callback: () -> Unit)

    /**
     * Initializes the CAS SDK. Call once, from the main process only.
     *
     * Test ad mode is enabled for debug builds so development traffic is never billed as real
     * impressions.
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }

        CAS.buildManager()
            .withCasId(casId)
            .withTestAdMode(BuildConfig.DEBUG)
            .withCompletionListener(object : InitializationListener {
                override fun onCASInitialized(config: InitialConfiguration) {
                    val error = config.error
                    if (error != null) {
                        Timber.e("CAS init failed: %s", error)
                    } else {
                        Timber.i(
                            "CAS initialized. country=%s consentRequired=%s",
                            config.countryCode,
                            config.isConsentRequired
                        )
                    }
                }
            })
            .build(context)
    }

    /**
     * Creates (once) the interstitial instance and starts filling the ad cache.
     * Safe to call repeatedly and from any thread.
     */
    fun startLoadAds() {
        ensureInterstitial()
    }

    /**
     * Shows the interstitial before establishing a VPN connection.
     *
     * [onFinishOrError] always runs exactly once — when the ad is dismissed, immediately when no
     * ad is available, or via timeout — so the connection flow is never blocked.
     */
    fun showConnectAd(activity: Activity, onFinishOrError: () -> Unit) {
        showInterstitial(activity, PLACEMENT_CONNECT, onFinishOrError)
    }

    /** Shows the interstitial before tearing down a VPN connection. */
    fun showDisconnectAd(activity: Activity, onFinishOrError: () -> Unit) {
        showInterstitial(activity, PLACEMENT_DISCONNECT, onFinishOrError)
    }

    private fun showInterstitial(
        activity: Activity,
        placement: String,
        onFinishOrError: () -> Unit
    ) {
        val ad = ensureInterstitial()

        // Nothing cached: autoload is already retrying in the background, so don't hold up
        // the user waiting for an ad that may never arrive.
        if (ad == null || !ad.isLoaded) {
            onFinishOrError()
            return
        }

        val request = PendingRequest(nextRequestId.getAndIncrement(), onFinishOrError)

        // An ad is already on screen; let this caller through rather than queueing behind it.
        if (!pending.compareAndSet(null, request)) {
            onFinishOrError()
            return
        }

        scheduleTimeout(request.id, SHOW_TIMEOUT_MS)

        try {
            ad.placement = placement
            ad.show(activity)
        } catch (e: Throwable) {
            // Never let a failure to display an ad strand the connect flow.
            Timber.e(e, "CAS interstitial show() failed")
            complete(request.id)
        }
    }

    private fun ensureInterstitial(): CASInterstitial? {
        interstitialAd?.let { return it }

        val context = MyApp.self
        initialize(context)

        synchronized(lock) {
            interstitialAd?.let { return it }

            val ad = try {
                CASInterstitial(context, casId)
            } catch (e: Throwable) {
                Timber.e(e, "CAS interstitial creation failed")
                return null
            }

            ad.apply {
                // Preserve the previous behaviour of showing an ad on every connect/disconnect
                // rather than applying CAS's default minimum interval between impressions.
                minInterval = 0
                contentCallback = adCallback
                // Autoload keeps a fresh ad cached and retries failed loads on its own.
                isAutoloadEnabled = true
            }

            interstitialAd = ad
            return ad
        }
    }

    private val adCallback = object : ScreenAdContentCallback() {
        override fun onAdFailedToLoad(format: AdFormat, error: AdError) {
            // Preload failure only; no request is waiting on this. Autoload will retry.
            Timber.w("CAS interstitial failed to load: %s", error)
        }

        override fun onAdShowed(ad: AdContentInfo) {
            // The ad reached the screen, so the SDK is alive and a dismissal is expected.
            // Swap the short watchdog for a long safety net.
            pending.get()?.let { scheduleTimeout(it.id, DISMISS_TIMEOUT_MS) }
        }

        override fun onAdFailedToShow(format: AdFormat, error: AdError) {
            Timber.w("CAS interstitial failed to show: %s", error)
            completeCurrent()
        }

        override fun onAdDismissed(ad: AdContentInfo) {
            completeCurrent()
        }
    }

    /** Completes the in-flight request, whichever it is. */
    private fun completeCurrent() {
        val request = pending.getAndSet(null) ?: return
        cancelTimeout()
        runOnMain(request.callback)
    }

    /** Completes [id] only if it is still the in-flight request, so stale timeouts are no-ops. */
    private fun complete(id: Long) {
        val request = pending.get() ?: return
        if (request.id != id) return
        if (!pending.compareAndSet(request, null)) return
        cancelTimeout()
        runOnMain(request.callback)
    }

    private fun scheduleTimeout(id: Long, delayMs: Long) {
        cancelTimeout()
        val runnable = Runnable {
            Timber.w("CAS interstitial timed out after %d ms; resuming flow", delayMs)
            complete(id)
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
