package com.wdtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

data class VkTurnCreds(
    val username: String,
    val password: String,
    val urls: List<String>
)

enum class VkAuthScreenMode {
    LOGIN,
    JOIN_CALL,
}

object VkAuthWebViewManager {
    private const val TAG = "VkAuthWV"
    private const val AUTH_TIMEOUT_MS = 5 * 60_000L
    private const val NOTIFICATION_ID = 9002
    private const val CHANNEL_ID = "vk_auth_channel"

    const val EXTRA_MODE = "authMode"
    const val EXTRA_JOIN_HASH = "joinHash"

    val authMutex = Mutex()
    private val pendingLoginResult = AtomicReference<CompletableDeferred<Result<Unit>>?>(null)
    private val pendingTurnResult = AtomicReference<CompletableDeferred<Result<VkTurnCreds>>?>(null)
    var activeActivity: VkAuthActivity? = null
    var pendingIntentToStart: Intent? = null

    /** Вход в аккаунт VK без присоединения к звонку (кнопка на главной). */
    suspend fun loginOnly(context: Context): Result<Unit> {
        return authMutex.withLock {
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingLoginResult.getAndSet(deferred)?.cancel()

            val intent = Intent(context, VkAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, VkAuthScreenMode.LOGIN.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            showAuthNotification(context, VkAuthScreenMode.LOGIN, null)

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            } finally {
                pendingLoginResult.set(null)
                pendingIntentToStart = null
                clearAuthNotification(context)
                try {
                    activeActivity?.finish()
                } catch (_: Exception) {
                }
                activeActivity = null
            }
        }
    }

    /** Присоединение к звонку и получение TURN (при подключении туннеля). */
    suspend fun authenticate(context: Context, hash: String): Result<VkTurnCreds> {
        val cleanHash = stripVkUrlStatic(hash.trim())
        if (cleanHash.length < 8) {
            return Result.failure(IllegalArgumentException("Некорректный VK-хеш"))
        }
        return authMutex.withLock {
            val deferred = CompletableDeferred<Result<VkTurnCreds>>()
            pendingTurnResult.getAndSet(deferred)?.cancel()

            val intent = Intent(context, VkAuthActivity::class.java).apply {
                putExtra(EXTRA_MODE, VkAuthScreenMode.JOIN_CALL.name)
                putExtra(EXTRA_JOIN_HASH, cleanHash)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            showAuthNotification(context, VkAuthScreenMode.JOIN_CALL, cleanHash)

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            } finally {
                pendingTurnResult.set(null)
                pendingIntentToStart = null
                clearAuthNotification(context)
                try {
                    activeActivity?.finish()
                } catch (_: Exception) {
                }
                activeActivity = null
            }
        }
    }

    suspend fun authenticateAll(context: Context, hashes: List<String>): Map<String, VkTurnCreds> {
        val out = linkedMapOf<String, VkTurnCreds>()
        for (hash in hashes.distinct()) {
            val clean = stripVkUrlStatic(hash.trim())
            if (clean.length < 8) continue
            val result = authenticate(context, clean)
            if (result.isFailure) {
                throw result.exceptionOrNull() ?: Exception("VK auth failed for $clean")
            }
            out[clean] = result.getOrThrow()
        }
        return out
    }

    fun writeCredsFile(context: Context, credsByHash: Map<String, VkTurnCreds>): File {
        val root = JSONObject()
        val hashes = JSONObject()
        credsByHash.forEach { (hash, creds) ->
            val entry = JSONObject()
            entry.put("u", creds.username)
            entry.put("p", creds.password)
            entry.put("urls", JSONArray(creds.urls))
            hashes.put(hash, entry)
        }
        root.put("hashes", hashes)
        val file = File(context.filesDir, "vk_turn_creds.json")
        file.writeText(root.toString())
        return file
    }

    fun notifyLoginFailure(error: Exception) {
        val deferred = pendingLoginResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.failure(error))
        }
    }

    fun notifyLoginSuccess() {
        val deferred = pendingLoginResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.success(Unit))
        }
    }

    fun notifyTurnResult(result: Result<VkTurnCreds>) {
        val deferred = pendingTurnResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }

    fun notifyCancelled() {
        pendingLoginResult.getAndSet(null)?.complete(Result.failure(CancellationException("Cancelled")))
        pendingTurnResult.getAndSet(null)?.complete(Result.failure(CancellationException("Cancelled")))
    }

    fun checkAndShowPendingAuth(context: Context) {
        val intent = pendingIntentToStart
        if (intent != null && activeActivity == null) {
            context.startActivity(intent)
        }
    }

    fun hasVkSessionCookie(): Boolean = vkRemixSid().isNotBlank()

    internal fun vkRemixSid(): String {
        val cm = CookieManager.getInstance()
        val raw = listOf(
            cm.getCookie("https://vk.com"),
            cm.getCookie("https://vk.ru"),
            cm.getCookie("https://m.vk.com"),
        ).filterNotNull().joinToString(";")
        return raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("remixsid=") }
            ?.removePrefix("remixsid=")
            ?.trim()
            .orEmpty()
    }

    private fun showAuthNotification(context: Context, mode: VkAuthScreenMode, hash: String?) {
        if (MainActivity.isForeground) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Авторизация VK",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, VkAuthActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            if (hash != null) putExtra(EXTRA_JOIN_HASH, hash)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 2, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = Intent(context, VkAuthCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 3, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val body = when (mode) {
            VkAuthScreenMode.LOGIN -> "Войдите в аккаунт VK"
            VkAuthScreenMode.JOIN_CALL -> "Нажмите «Продолжить» в браузере"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Требуется вход в VK")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Отменить", cancelPendingIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearAuthNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun encodeTurnCredsPayload(hash: String, creds: VkTurnCreds): String {
        val json = JSONObject()
        json.put("u", creds.username)
        json.put("p", creds.password)
        json.put("urls", JSONArray(creds.urls))
        val b64 = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "TURN_CREDS|$hash|$b64\n"
    }
}

class VkAuthActivity : ComponentActivity() {
    private lateinit var screenMode: VkAuthScreenMode
    private var loginHandled = false

    private val interceptorJSCode = """
        (function() {
            if (window.__wdtt_vk_auth_installed) return;
            window.__wdtt_vk_auth_installed = true;

            function tryEmitTurnServer(data) {
                if (!data) return;
                var ts = data.turn_server;
                if (!ts && data.response && data.response.turn_server) {
                    ts = data.response.turn_server;
                }
                if (ts && ts.username && ts.credential && ts.urls) {
                    window.WdttVkAuth.onTurnServer(JSON.stringify(ts));
                }
            }

            const origFetch = window.fetch;
            window.fetch = async function() {
                const response = await origFetch.apply(this, arguments);
                try {
                    const clone = response.clone();
                    const text = await clone.text();
                    if (text && text.indexOf('turn_server') !== -1) {
                        tryEmitTurnServer(JSON.parse(text));
                    }
                } catch(e) {}
                return response;
            };

            const origXHROpen = XMLHttpRequest.prototype.open;
            const origXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._wdtt_url = url;
                return origXHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const xhr = this;
                xhr.addEventListener('load', function() {
                    try {
                        if (!xhr.responseText || xhr.responseText.indexOf('turn_server') === -1) return;
                        tryEmitTurnServer(JSON.parse(xhr.responseText));
                    } catch(e) {}
                });
                return origXHRSend.apply(this, arguments);
            };
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VkAuthWebViewManager.activeActivity = this

        screenMode = when (intent.getStringExtra(VkAuthWebViewManager.EXTRA_MODE)) {
            VkAuthScreenMode.LOGIN.name -> VkAuthScreenMode.LOGIN
            else -> VkAuthScreenMode.JOIN_CALL
        }
        val joinHash = intent.getStringExtra(VkAuthWebViewManager.EXTRA_JOIN_HASH).orEmpty()
        if (screenMode == VkAuthScreenMode.JOIN_CALL && joinHash.length < 8) {
            VkAuthWebViewManager.notifyTurnResult(Result.failure(IllegalArgumentException("Некорректный VK-хеш")))
            finish()
            return
        }

        val startUrl = when (screenMode) {
            VkAuthScreenMode.LOGIN -> "https://vk.com/"
            VkAuthScreenMode.JOIN_CALL -> "https://vk.com/call/join/$joinHash"
        }
        val statusText = when (screenMode) {
            VkAuthScreenMode.LOGIN -> "Войдите в аккаунт VK"
            VkAuthScreenMode.JOIN_CALL -> "Нажмите «Продолжить» в браузере"
        }

        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var isLoading by rememberSaveable { mutableStateOf(true) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = statusText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.WHITE)
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            mediaPlaybackRequiresUserGesture = false
                                            loadWithOverviewMode = true
                                            useWideViewPort = true
                                            blockNetworkLoads = false
                                            cacheMode = WebSettings.LOAD_DEFAULT
                                            userAgentString =
                                                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                        }

                                        addJavascriptInterface(object {
                                            @JavascriptInterface
                                            fun onTurnServer(json: String) {
                                                if (screenMode != VkAuthScreenMode.JOIN_CALL) return
                                                runOnUiThread {
                                                    parseAndFinishTurn(json)
                                                }
                                            }

                                            @JavascriptInterface
                                            fun onError(message: String) {
                                                when (screenMode) {
                                                    VkAuthScreenMode.LOGIN ->
                                                        VkAuthWebViewManager.notifyLoginFailure(Exception(message))
                                                    VkAuthScreenMode.JOIN_CALL ->
                                                        VkAuthWebViewManager.notifyTurnResult(
                                                            Result.failure(Exception(message))
                                                        )
                                                }
                                                finish()
                                            }
                                        }, "WdttVkAuth")

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                super.onPageStarted(view, url, favicon)
                                                if (screenMode == VkAuthScreenMode.JOIN_CALL) {
                                                    view?.evaluateJavascript(interceptorJSCode, null)
                                                }
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                if (screenMode == VkAuthScreenMode.JOIN_CALL) {
                                                    view?.evaluateJavascript(interceptorJSCode, null)
                                                }
                                                if (screenMode == VkAuthScreenMode.LOGIN) {
                                                    checkLoginAndClose(url)
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                val url = request?.url?.toString().orEmpty()
                                                if (url.startsWith("intent://") || url.startsWith("vk://")) {
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onReceivedSslError(
                                                view: WebView?,
                                                handler: SslErrorHandler?,
                                                error: SslError?
                                            ) {
                                                val host = error?.url?.let { Uri.parse(it).host }.orEmpty()
                                                if (host.endsWith("vk.com") || host.endsWith("vk.ru") || host.endsWith("okcdn.ru")) {
                                                    handler?.proceed()
                                                } else {
                                                    handler?.cancel()
                                                }
                                            }
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                if (newProgress >= 90) isLoading = false
                                            }
                                        }
                                        loadUrl(startUrl)
                                    }
                                }
                            )
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(48.dp)
                                )
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            VkAuthWebViewManager.notifyCancelled()
                            finish()
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Black)
                    }
                }
            }
        }
    }

    private fun checkLoginAndClose(pageUrl: String?) {
        if (loginHandled || screenMode != VkAuthScreenMode.LOGIN) return
        val sid = VkAuthWebViewManager.vkRemixSid()
        if (sid.length < 8) return

        val url = pageUrl.orEmpty().lowercase()
        val onLoginFlow = url.contains("id.vk.com") && (url.contains("authorize") || url.contains("login"))
        if (onLoginFlow) return

        loginHandled = true
        Log.d("VkAuthWV", "VK login detected, closing WebView")
        VkAuthWebViewManager.notifyLoginSuccess()
        finish()
    }

    private fun parseAndFinishTurn(json: String) {
        try {
            val obj = JSONObject(json)
            val user = obj.optString("username")
            val pass = obj.optString("credential")
            val urlsRaw = obj.optJSONArray("urls") ?: JSONArray()
            val urls = mutableListOf<String>()
            for (i in 0 until urlsRaw.length()) {
                val url = urlsRaw.optString(i)
                if (url.isNotBlank()) urls.add(url)
            }
            if (user.isBlank() || pass.isBlank() || urls.isEmpty()) {
                Log.e("VkAuthWV", "Incomplete turn_server: $json")
                return
            }
            Log.d("VkAuthWV", "TURN creds captured, urls=${urls.size}")
            VkAuthWebViewManager.notifyTurnResult(Result.success(VkTurnCreds(user, pass, urls)))
            finish()
        } catch (e: Exception) {
            Log.e("VkAuthWV", "parse error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (VkAuthWebViewManager.activeActivity === this) {
            VkAuthWebViewManager.activeActivity = null
        }
    }
}

class VkAuthCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VkAuthWebViewManager.notifyCancelled()
        VkAuthWebViewManager.activeActivity?.finish()
        val notifMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifMgr.cancel(9002)
    }
}
