package com.witvpn.ikev2.presentation.ui

//import com.google.android.gms.ads.AdError
//import com.google.android.gms.ads.AdRequest
//import com.google.android.gms.ads.FullScreenContentCallback
//import com.google.android.gms.ads.LoadAdError
//import com.google.android.gms.ads.interstitial.InterstitialAd
//import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Insets
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.Observer
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ActivityMainBinding
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.presentation.base.BaseActivity
import com.witvpn.ikev2.presentation.utils.GooglePlayHelper
import com.witvpn.ikev2.presentation.utils.InAppUpdateHelper
import com.witvpn.ikev2.presentation.utils.PremiumNotificationHelper
import com.witvpn.ikev2.presentation.utils.SharePrefs
import com.witvpn.ikev2.presentation.utils.getBooleanPref
import com.witvpn.ikev2.presentation.utils.getIntPref
import com.witvpn.ikev2.presentation.utils.getSessionUserId
import com.witvpn.ikev2.presentation.utils.putBooleanPref
import com.witvpn.ikev2.presentation.utils.putIntPref
import com.witvpn.ikev2.presentation.utils.parseApiDate
import com.witvpn.ikev2.presentation.utils.show
import com.witvpn.ikev2.presentation.utils.updateColorNavigationBar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import com.witvpn.ikev2.awg.AmneziaWGManager
import com.witvpn.ikev2.vless.VlessManager

interface MainDelegate {
    fun recreateApp()
    fun showInterstitialAd()
    fun openLeftMenu()
    fun closeLeftMenu()
    fun isLeftMenuOpen(): Boolean
}

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>(), MainDelegate {
    private val navController by lazy {
        val hostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        hostFragment.navController
    }

    private val viewModel: ShareViewModel by viewModels()
    private val handleUserResource = Observer<User> {}

    @javax.inject.Inject
    lateinit var userRepository: UserRepository

    /*private val adRequest = AdRequest.Builder().build()
    private var mInterstitialAd: InterstitialAd? = null
        set(value) {
            field = value
            field?.fullScreenContentCallback = screenContentCallback
        }
    private val screenContentCallback = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() {
            initAdmob()
            mInterstitialAd = null
        }

        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
            initAdmob()
            mInterstitialAd = null
        }

        override fun onAdShowedFullScreenContent() {
            mInterstitialAd = null
        }
    }
    private val interstitialAdLoadCallback = object : InterstitialAdLoadCallback() {
        override fun onAdFailedToLoad(adError: LoadAdError) {
            mInterstitialAd = null
        }

        override fun onAdLoaded(interstitialAd: InterstitialAd) {
            mInterstitialAd = interstitialAd
        }
    }*/

    private lateinit var firebaseAnalytics: FirebaseAnalytics


    override fun onCreate(savedInstanceState: Bundle?) {
        // Clear navigation saved state to prevent back stack restoration crash
        // This fixes: "Restoring the Navigation back stack failed"
        if (savedInstanceState != null) {
            savedInstanceState.remove("android:support:fragments")
        }
        super.onCreate(savedInstanceState)
        // Initialize VPN Managers with try-catch to prevent immediate crashes on problematic devices like Honor
        try {
            VlessManager.initialize(this)
            AmneziaWGManager.initialize(this)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize VPN cores")
        }

        try {
            if (GooglePlayHelper.deviceHasGooglePlayServices(this)) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(this)
            }
        } catch (e: Exception) {
            Timber.e(e, "Firebase Analytics init failed")
        }
        viewModel.userLiveData.observe(this, handleUserResource)

        checkAndRequestNotificationPermissions()

        InAppUpdateHelper.checkAndRequestAppUpdate(this)

//        initNavigationDrawer()

        try {
            if (GooglePlayHelper.deviceHasGooglePlayServices(this)) {
                FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Timber.w("Fetching FCM registration token failed", task.exception)
                        return@OnCompleteListener
                    }
                    Timber.d(task.result)
                })
            }
        } catch (e: Exception) {
            Timber.e(e, "Firebase Messaging init failed")
        }
        configureButtomNavigation()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { v, insets ->
            val systemBars = insets.getInsets(Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
    }

    private fun checkAndRequestNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                0
            )
        }
    }

    override fun initBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    private fun configureButtomNavigation() {
        val bottomDisplayFragments = arrayOf(
            R.id.mainFragment,
            R.id.QRFragment,
            R.id.OTPFragment,
            R.id.supportFragment,
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (bottomDisplayFragments.contains(destination.id)) {
                binding.bottomNavigation.visibility = VISIBLE
            } else {
                binding.bottomNavigation.visibility = GONE
            }
        }
        binding.bottomNavigation.setOnItemSelectedListener {
            val pageId = when(it.itemId){
                R.id.connect -> R.id.mainFragment
                R.id.qr -> R.id.QRFragment
                R.id.settings -> R.id.OTPFragment
                R.id.support -> R.id.supportFragment
                else -> throw IllegalArgumentException()
            }
            navController.navigate(pageId)
            true
        }

    }

    override fun initView() {
        //val intent = Intent(this, TelegramAD::class.java)
        //startActivity(intent)
        val hostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = hostFragment.navController
        navController.addOnDestinationChangedListener { _, destination, _ ->
            Timber.i("destination = ${destination.label}")
            val colorNav = when (destination.label) {
//                "MainFragment" -> R.color.colorNavBottomBackground
                else -> R.color.black_almost
            }
            this.updateColorNavigationBar(colorNav)
        }
    }

    override fun onResume() {
        super.onResume()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userId = getSessionUserId() ?: return@launch
                val param = mutableMapOf<String, Any>("userId" to userId)
                val user = userRepository.profile(param)

                val wasPremium = getBooleanPref(SharePrefs.KEY_WAS_PREMIUM, false)
                val isPremium = user.hasPremiumSubscribe

                if (!wasPremium && isPremium) {
                    val endDate = user.premiumEnd?.parseApiDate() ?: user.premiumEnd ?: ""
                    launch(Dispatchers.Main) {
                        PremiumNotificationHelper.sendActivationNotification(this@MainActivity, endDate)
                    }
                }

                putBooleanPref(SharePrefs.KEY_WAS_PREMIUM, isPremium)

                if (isPremium && !user.premiumEnd.isNullOrEmpty()) {
                    val daysLeft = calculateDaysLeft(user.premiumEnd)
                    if (daysLeft in intArrayOf(7, 3, 1)) {
                        val lastReminder = getIntPref(SharePrefs.KEY_LAST_REMINDER_DAYS, 0)
                        if (lastReminder != daysLeft) {
                            val endDate = user.premiumEnd?.parseApiDate() ?: user.premiumEnd ?: ""
                            launch(Dispatchers.Main) {
                                PremiumNotificationHelper.sendExpirationReminder(this@MainActivity, daysLeft, endDate)
                            }
                            putIntPref(SharePrefs.KEY_LAST_REMINDER_DAYS, daysLeft)
                        }
                    }
                }

                launch(Dispatchers.Main) {
                    viewModel.replaceUser(user)
                }
            } catch (_: Exception) {}
        }
    }

    private fun calculateDaysLeft(premiumEnd: String): Int {
        return try {
            val inputFormat = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.ENGLISH)
            val endDate = inputFormat.parse(premiumEnd) ?: return -1
            val diff = endDate.time - System.currentTimeMillis()
            (diff / (1000 * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun initAdmob(refresh: Boolean = false) {
        val ads = viewModel.user?.ads
        var bannerId = ads?.firstOrNull { ad -> ad.adsType == "banner" }?.adsId ?: ""
        var interstitialAdId = ads?.firstOrNull { ad -> ad.adsType == "interstitial" }?.adsId ?: ""
        if (BuildConfig.DEBUG) {
            bannerId = "ca-app-pub-3940256099942544/6300978111"
            interstitialAdId = "ca-app-pub-3940256099942544/1033173712"
        }
        Timber.i("Banner: $bannerId\nInterstitial: $interstitialAdId")

        if (refresh) {
            //InterstitialAd.load(this, interstitialAdId, adRequest, interstitialAdLoadCallback)
            return
        }

        /*val adView = AdView(this)
            .apply {
                adSize = AdSize.BANNER
                adUnitId = bannerId
                loadAd(adRequest)
            }*/

//        binding.adsContainer.removeAllViews()
//        //binding.adsContainer.addView(adView)
//        binding.adsContainer.show(true)
        //InterstitialAd.load(this, interstitialAdId, adRequest, interstitialAdLoadCallback)
    }

    override fun recreateApp() {
        finish()
        startActivity(intent)
    }

    override fun showInterstitialAd() {
//        mInterstitialAd?.show(this)
    }

//    private fun initNavigationDrawer() {
//        binding.leftDrawer.apply {
//            accountButton.setOnClickListener {
//                closeLeftMenu()
//                val hostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
//                val navController = hostFragment.navController
//                navController.navigate(R.id.action_mainFragment_to_OTPFragment)
//            }
//            mainLeftMenuQR.setOnClickListener {
//                closeLeftMenu()
//                val hostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
//                val navController = hostFragment.navController
//                navController.navigate(R.id.action_mainFragment_to_QRFragment)
//            }
//            mainLeftMenuTelegramChanel.setOnClickListener {
//                startActivity(telegramJoinIntent(this@MainActivity, "ZAo_7z5mSFY2OWFi"))
//            }
//            mainLeftMenuSupport.setOnClickListener {
//                startActivity(telegramJoinIntent(this@MainActivity, "ZAo_7z5mSFY2OWFi"))
//            }
//        }
//    }

    fun telegramJoinIntent(context: Context, tg: String): Intent {
        return try {
            try {
                context.packageManager.getPackageInfo(
                    "org.telegram.messenger",
                    0
                )//Check for Telegram Messenger App
            } catch (e: Exception) {
                context.packageManager.getPackageInfo(
                    "org.thunderdog.challegram",
                    0
                )//Check for Telegram X App
            }
            Intent(Intent.ACTION_VIEW, Uri.parse("tg://join?invite=$tg"))
        } catch (e: Exception) { //App not found open in browser
            Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/+$tg"))
        }
    }

    fun telegramUserIntent(context: Context, tg: String): Intent {
        return try {
            try {
                context.packageManager.getPackageInfo(
                    "org.telegram.messenger",
                    0
                )//Check for Telegram Messenger App
            } catch (e: Exception) {
                context.packageManager.getPackageInfo(
                    "org.thunderdog.challegram",
                    0
                )//Check for Telegram X App
            }
            Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$tg"))
        } catch (e: Exception) { //App not found open in browser
            Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/$tg"))
        }
    }

    override fun openLeftMenu() {
//        binding.drawerLayout.open()
    }

    override fun closeLeftMenu() {
//        binding.drawerLayout.closeDrawers()
    }

    override fun isLeftMenuOpen() = false
//        binding.drawerLayout.isOpen
    //endregion
}
