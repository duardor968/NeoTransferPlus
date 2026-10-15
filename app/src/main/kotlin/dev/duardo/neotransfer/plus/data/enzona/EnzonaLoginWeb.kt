package dev.duardo.neotransfer.plus.data.enzona

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The ENZONA login page inside a WebView the UI owns. The `http://apk-callback` redirect is intercepted
 * locally and never reaches the network; only identity.enzona.net over HTTPS may load in the main frame.
 * The UI sets FLAG_SECURE while this is visible. URLs and codes are never logged or put in an Intent.
 */
object EnzonaLoginWeb {
    interface Listener {
        /** Pass to [EnzonaSession.acceptCallback]. Called at most once per [attach]. */
        fun onCallback(url: String)
        fun onLoading(loading: Boolean)
        /** Navigation left the login origin, TLS/HTTP failed, or a download was attempted. */
        fun onBlocked()
    }

    /** Configures [webView] and loads [authorizationUrl] once. Call [release] when the screen goes away. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView, authorizationUrl: String, listener: Listener) {
        val delivered = AtomicBoolean(false)
        webView.isSaveEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setDownloadListener { _, _, _, _, _ -> listener.onBlocked() }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String?, prompt: GeolocationPermissions.Callback) {
                prompt.invoke(origin, false, false)
            }
            override fun onShowFileChooser(view: WebView, result: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                result.onReceiveValue(null)
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            private fun deliver(view: WebView, value: String) {
                view.stopLoading()
                if (delivered.compareAndSet(false, true)) {
                    listener.onLoading(true)
                    listener.onCallback(value)
                }
            }
            private fun reject(view: WebView) {
                view.stopLoading()
                listener.onLoading(false)
                listener.onBlocked()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val value = request.url.toString()
                if (EnzonaAuthContract.isCallbackUri(value)) {
                    if (request.isForMainFrame) deliver(view, value)
                    return true
                }
                if (!isLoginNavigation(value)) {
                    if (request.isForMainFrame) reject(view)
                    return true
                }
                return delivered.get()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val value = request.url.toString()
                // Runs on a WebView worker. No callback (including a subframe) reaches HTTP.
                if (EnzonaAuthContract.isCallbackUri(value)) {
                    if (request.isForMainFrame) view.post { deliver(view, value) }
                    return blocked()
                }
                if (!request.url.scheme.equals("https", ignoreCase = true) ||
                    (request.isForMainFrame && !isLoginNavigation(value))) {
                    if (request.isForMainFrame) view.post { reject(view) }
                    return blocked()
                }
                return null // Normal HTTPS resources; never add bearer headers or a JS bridge.
            }
            override fun onPageStarted(view: WebView, value: String?, favicon: Bitmap?) {
                if (value != null && EnzonaAuthContract.isCallbackUri(value)) deliver(view, value)
                else if (value == null || !isLoginNavigation(value)) reject(view)
                else listener.onLoading(true)
            }
            override fun onPageFinished(view: WebView, value: String?) {
                if (!delivered.get()) listener.onLoading(false)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) reject(view)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) reject(view)
            }
            // TLS stays strict: the APK's certificate bypass is deliberately not copied.
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                reject(view)
            }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                handler.cancel()
                reject(view)
            }
        }
        if (isLoginNavigation(authorizationUrl)) webView.loadUrl(authorizationUrl) else listener.onBlocked()
    }

    fun release(webView: WebView) {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    }

    /**
     * Sign-out only, after the WebView is gone and [EnzonaSession.signOut] succeeded. Android's cookie jar
     * and web storage are app-wide: this app has no other WebView. It does not revoke server tokens.
     */
    suspend fun clearWebSession(): Unit = withContext(Dispatchers.Main.immediate) {
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine { continuation ->
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    fun isLoginNavigation(value: String): Boolean = try {
        val uri = URI(value)
        !uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("identity.enzona.net", ignoreCase = true) && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
    } catch (_: Exception) { false }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
        ByteArrayInputStream(ByteArray(0)))
}
