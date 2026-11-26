package com.gravity.ide.ui

import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView

/**
 * A WebView's page runs in a separate web engine process. When that process crashes, or the phone
 * closes it to free memory (common while a phone model is writing), Android also closes the whole
 * app unless the WebView's client handles onRenderProcessGone. Every WebView in Yamini does, with
 * these helpers.
 */
object WebEngine {
    /** What to tell the user when a page's web engine is gone. */
    fun goneMessage(detail: RenderProcessGoneDetail): String = goneMessage(detail.didCrash())

    fun goneMessage(crashed: Boolean): String = if (crashed) {
        "The page crashed the phone's web engine (often an endless loop or something huge in memory). Tap Reload to open it again."
    } else {
        "The phone closed the page's web engine to free memory (a phone model uses a lot while it writes). Tap Reload to open it again."
    }

    /** Takes a WebView whose engine is gone off the screen and frees it; it must not be used again. */
    fun discard(view: WebView) {
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
    }
}
