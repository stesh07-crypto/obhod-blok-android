package com.wdtt.client

import android.content.Context

object RunetDirectHelper {
    fun allowedIpsV4(context: Context): String {
        return "0.0.0.0/0"
    }
}
