package com.gravity.ide

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.gravity.ide.di.AppContainer
import java.io.FileInputStream

/**
 * Helpers for the on-device verification suite. Everything is logged under the tag
 * "GravityCI" so the CI job can print exactly what the app did and showed.
 */
object Ci {
    const val TAG = "GravityCI"
    const val SHOT_DIR = "/data/local/tmp/gravity-ci"

    val container: AppContainer
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GravityApp).container

    fun log(section: String, message: String) {
        val text = "[$section] $message"
        // Logcat truncates long lines; split so nothing is lost.
        text.chunked(3000).forEach { Log.i(TAG, it) }
    }

    /** Runs a command as the shell user and returns its output (blocks until it exits). */
    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { pfd.close() }
    }

    /** Real screenshot of the emulator screen, pulled by the CI job afterwards. */
    fun screenshot(name: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        shell("mkdir -p $SHOT_DIR")
        shell("screencap -p $SHOT_DIR/$safe.png")
    }
}
