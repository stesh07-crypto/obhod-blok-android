package com.wdtt.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class ConnectionStep(val order: Int, val label: String) {
    DNS(1, "DNS"),
    VK(2, "VK Hashes"),
    CAPTCHA(3, "Captcha"),
    WRAP(4, "Tunnel Wrap"),
    TURN(5, "TURN Server"),
    DTLS(6, "DTLS Handshake"),
    WORKERS(7, "Workers"),
    VPN(8, "VPN Interface"),
    DONE(9, "Connected")
}

data class ConnectionPipelineState(
    val visible: Boolean = false,
    val current: ConnectionStep? = null,
    val completed: Set<ConnectionStep> = emptySet(),
    val failed: ConnectionStep? = null,
    val timedOut: Boolean = false,
    val timeoutSec: Int = 0,
    val captchaRequired: Boolean = false
) {
    fun stepsToShow(): List<ConnectionStep> {
        return ConnectionStep.values().filter { it != ConnectionStep.DONE }
    }
}

@Composable
fun ConnectionPipelineCard(
    state: ConnectionPipelineState,
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Status card
}
