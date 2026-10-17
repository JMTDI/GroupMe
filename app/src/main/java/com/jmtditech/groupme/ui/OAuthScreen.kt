package com.jmtditech.groupme.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app GroupMe OAuth sign-in. Loads the authorize URL in a WebView and intercepts the redirect to the
 * (HTTPS) callback URL: as soon as a navigation carries `access_token`, it is handed to [onToken] and the
 * callback page itself is never loaded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthWebView(url: String, onToken: (String) -> Unit, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    val currentOnToken by rememberUpdatedState(onToken)
    val handled = remember { booleanArrayOf(false) }

    fun tryCapture(target: Uri?): Boolean {
        if (handled[0] || target == null || target.scheme != "https") return false
        val token = target.getQueryParameter("access_token")?.takeIf { it.isNotBlank() } ?: return false
        handled[0] = true
        currentOnToken(token)
        return true
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        CompactBar("Sign in with GroupMe", onBack = onClose)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (tryCapture(u)) return true
                            val host = u.host.orEmpty()
                            if (u.scheme == "http" && (host == "groupme.com" || host.endsWith(".groupme.com"))) {
                                view.loadUrl(u.buildUpon().scheme("https").build().toString())
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                            loading = true
                            // Fallback for redirects that don't go through shouldOverrideUrlLoading.
                            if (tryCapture(pageUrl?.let { Uri.parse(it) })) view.stopLoading()
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            if (request.isForMainFrame) loading = false
                        }
                    }
                    loadUrl(url)
                    requestFocus()
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
