package io.github.vyomtunnel.sdk

import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class VyomPermissionActivity : AppCompatActivity() {
    private val vpnRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        VyomVpnManager.completePermission(this,
            intent.getIntExtra("permissionGeneration", -1), it.resultCode == RESULT_OK)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            if (savedInstanceState == null) vpnRequest.launch(permissionIntent)
        } else {
            VyomVpnManager.completePermission(this,
                intent.getIntExtra("permissionGeneration", -1), true)
            finish()
        }
    }
}