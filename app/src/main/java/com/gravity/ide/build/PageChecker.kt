package com.gravity.ide.build

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Tests a generated web app for real: opens it in an off-screen WebView, taps each button once
 * and collects the JavaScript errors the WebView reports (syntax errors, functions that do not
 * exist…), so the AI can be asked to fix actual problems. It runs offline (requests outside the
 * app are blocked) and under its own origin, so it never touches the data of the app's preview.
 */
class PageChecker(private val context: Context, private val workspacesRoot: File) {

    data class Report(val loaded: Boolean, val errors: List<String>, val controlsTapped: Int)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun check(projectRoot: File, timeoutMs: Long = 20_000): Report = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val errors = LinkedHashSet<String>()
            val loader = WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/project/", WebViewAssetLoader.InternalStoragePathHandler(context, workspacesRoot))
                .build()
            val wv = WebView(context)
            var done = false
            var loaded = false
            var tapped = 0

            fun finish() {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { wv.stopLoading(); wv.destroy() }
                if (cont.isActive) cont.resume(Report(loaded, errors.filterNot(::isOfflineNoise).take(12), tapped))
            }

            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errors += "${m.message()} (line ${m.lineNumber()})"
                    return true
                }

                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.confirm()
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.confirm()
                    return true
                }

                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                    result.confirm(defaultValue ?: "")
                    return true
                }

                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    callback.onReceiveValue(null)
                    return true
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host == HOST) return loader.shouldInterceptRequest(request.url)
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked during the offline check", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true // stay on the page

                override fun onPageFinished(view: WebView, url: String) {
                    if (loaded || done) return
                    loaded = true
                    handler.postDelayed({
                        if (done) return@postDelayed
                        view.evaluateJavascript(TAP_ALL) { r ->
                            tapped = r?.trim('"')?.toIntOrNull() ?: 0
                            handler.postDelayed({ finish() }, 1500)
                        }
                    }, 800)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !done) {
                        errors += "The page could not be opened: ${error.description}"
                        finish()
                    }
                }
            }
            handler.postDelayed({
                if (!loaded) errors += "The page did not finish loading within ${timeoutMs / 1000} s"
                finish()
            }, timeoutMs)
            cont.invokeOnCancellation { handler.post { finish() } }
            wv.loadUrl("https://$HOST/project/${projectRoot.name}/index.html")
        }
    }

    companion object {
        private const val HOST = "appcheck.androidplatform.net"

        /** Taps every button once; form submits are not allowed to navigate away. Returns the count. */
        private const val TAP_ALL = """(function () {
  document.addEventListener('submit', function (e) { e.preventDefault(); }, true);
  var n = 0;
  var els = document.querySelectorAll('button, input[type=button], input[type=submit], [onclick]');
  for (var i = 0; i < els.length && n < 60; i++) {
    try { els[i].click(); n++; } catch (x) { console.error('Tapping a button failed: ' + x); }
  }
  return n;
})()"""

        /** Errors caused only by the check being offline, which the AI should not "fix". */
        fun isOfflineNoise(error: String): Boolean =
            listOf("Failed to fetch", "NetworkError", "net::ERR", "Failed to load resource").any { it in error }
    }
}
