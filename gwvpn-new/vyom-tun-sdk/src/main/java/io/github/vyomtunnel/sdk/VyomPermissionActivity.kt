package io.github.vyomtunnel.sdk

import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class VyomPermissionActivity : AppCompatActivity() {
    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            // Permission granted, trigger the last saved config
            val config = VyomVpnManager.getLastConfig(this)
            if (config != null) VyomVpnManager.start(this, config)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = VpnService.prepare(this)
        if (intent != null) vpnRequest.launch(intent) else finish()
    }
}