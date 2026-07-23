package com.wdtt.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

class VpnPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_VPN)
        } else {
            onActivityResult(REQUEST_CODE_VPN, RESULT_OK, null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN) {
            if (resultCode == RESULT_OK) {
                TunnelControl.startFromSavedSettings(this)
            }
            finish()
        }
    }

    companion object {
        private const val REQUEST_CODE_VPN = 1001
    }
}
