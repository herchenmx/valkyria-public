package com.example.hevycompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Shows the real Hevy web login page in a full-screen WebView.
 * reCAPTCHA Enterprise v3 fires naturally in the real page context.
 * We inject JS to intercept the /login response and extract tokens,
 * then return them to the caller via setResult().
 */
class WebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_ACCESS_TOKEN  = "access_token"
        const val RESULT_REFRESH_TOKEN = "refresh_token"
        const val RESULT_EXPIRES_AT    = "expires_at"

        /**
         * The WebView carries a `TokenBridge` JS bridge that setResult()s
         * with whatever tokens are handed in. Only allow navigation to
         * origins where that bridge is safe to expose (Hevy itself).
         *
         * A redirect to any other origin — even a Hevy-driven one like the
         * privacy policy or a password-reset third party — must be blocked
         * or opened externally, otherwise a MITM or a compromised link
         * inside hevy.com can hand attacker tokens to the bridge.
         */
        internal fun isHevyOrigin(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            if (!"https".equals(uri.scheme, ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            return host == "hevy.com" || host == "www.hevy.com"
        }
    }

    private var webView: WebView? = null
    private var settled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).also { this.webView = it }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Pre-API-30 default is `true` for file access — irrelevant for
            // https navigation but shrinks the attack surface if the WebView
            // ever ends up loading a `file:///` URL (it shouldn't).
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun onTokens(accessToken: String, refreshToken: String, expiresAt: String) {
                if (settled) return
                settled = true
                val result = Intent().apply {
                    putExtra(RESULT_ACCESS_TOKEN,  accessToken)
                    putExtra(RESULT_REFRESH_TOKEN, refreshToken)
                    putExtra(RESULT_EXPIRES_AT,    expiresAt)
                }
                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }, "TokenBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(injectionScript(), null)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isHevyOrigin(url)) return false
                openExternally(url)
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                if (isHevyOrigin(url)) return false
                openExternally(url)
                return true
            }
        }

        webView.loadUrl("https://hevy.com")
    }

    /** Send an off-origin URL to the OS browser rather than the WebView. */
    private fun openExternally(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.let { wv ->
            wv.removeJavascriptInterface("TokenBridge")
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
    }

    private fun injectionScript() = """
        (function() {
            if (window.__hevyBridgeInstalled) return;
            window.__hevyBridgeInstalled = true;

            /* ── Intercept fetch ────────────────────────────────────── */
            var _fetch = window.fetch;
            window.fetch = function() {
                var args = arguments;
                return _fetch.apply(this, args).then(function(resp) {
                    try {
                        var url = typeof args[0] === 'string' ? args[0]
                                : (args[0] && args[0].url) || '';
                        if (url.indexOf('/login') !== -1 && resp.ok) {
                            resp.clone().json().then(function(d) {
                                if (d && d.access_token)
                                    TokenBridge.onTokens(
                                        d.access_token,
                                        d.refresh_token || '',
                                        d.expires_at    || '');
                            }).catch(function(){});
                        }
                    } catch(e) {}
                    return resp;
                });
            };

            /* ── Intercept XHR ──────────────────────────────────────── */
            var _open = XMLHttpRequest.prototype.open;
            var _send = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, url) {
                this.__url = url;
                return _open.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                this.addEventListener('load', function() {
                    try {
                        if (this.__url && this.__url.indexOf('/login') !== -1
                                && this.status === 200) {
                            var d = JSON.parse(this.responseText);
                            if (d && d.access_token)
                                TokenBridge.onTokens(
                                    d.access_token,
                                    d.refresh_token || '',
                                    d.expires_at    || '');
                        }
                    } catch(e) {}
                });
                return _send.apply(this, arguments);
            };

        })();
    """.trimIndent()
}
