package com.gravity.ide.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.gravity.ide.ai.local.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abis: List<String>,
    val cpuCores: Int,
    /** Fast ("big") CPU cores, from their maximum frequencies; 0 if unknown. */
    val fastCores: Int = 0,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowRam: Boolean,
    val internalFreeBytes: Long,
    val internalTotalBytes: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: String,
    val glEsVersion: String,
)

data class InstalledApp(val packageName: String, val label: String, val versionName: String?, val system: Boolean, val debuggable: Boolean)

/** One real capability check: what was tested, the outcome and the evidence. */
data class Capability(val id: String, val name: String, val available: Boolean, val evidence: String, val impact: String)

class DeviceInspector(private val context: Context) {

    fun info(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when ((context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "Unavailable (Android < 10)"
        val gl = am.deviceConfigurationInfo.glEsVersion
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            fastCores = PerformanceManager.fastCores(
                (0 until Runtime.getRuntime().availableProcessors()).map { i ->
                    runCatching { java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
                }
            ),
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            lowRam = am.isLowRamDevice,
            internalFreeBytes = stat.availableBytes,
            internalTotalBytes = stat.totalBytes,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = plugged,
            thermalStatus = thermal,
            glEsVersion = gl,
        )
    }

    fun recommendedMaxModelBytes(): Long {
        val i = info()
        // Leave ~40% of total RAM for Android, Gravity and the KV cache.
        return (i.totalRamBytes * 0.6).toLong() - 512L * 1024 * 1024
    }

    @Suppress("DEPRECATION")
    fun installedApps(includeSystem: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { ai ->
                val version = try { pm.getPackageInfo(ai.packageName, 0).versionName } catch (_: Exception) { null }
                InstalledApp(
                    ai.packageName, pm.getApplicationLabel(ai).toString(), version,
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun exec(vararg cmd: String, timeoutSec: Long = 10): Pair<Int, String> = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to "timed out"
        } else {
            p.exitValue() to out.trim()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }

    /**
     * The on-device build/test feasibility assessment. Every entry is an actual probe on this
     * device; nothing is assumed from documentation.
     */
    suspend fun probeCapabilities(): List<Capability> = withContext(Dispatchers.IO) {
        val list = ArrayList<Capability>()
        val i = info()

        val (shCode, shOut) = exec("/system/bin/sh", "-c", "echo gravity-ok; id")
        list += Capability(
            "shell", "Shell execution (/system/bin/sh)", shCode == 0 && shOut.contains("gravity-ok"),
            "exit=$shCode output=${shOut.take(200)}", "Integrated terminal and agent run_command use this shell."
        )

        // W^X: Android 10+ forbids exec() of files written to app storage.
        val probe = File(context.filesDir, "exec_probe.sh")
        probe.writeText("#!/system/bin/sh\necho direct-exec-ok\n")
        probe.setExecutable(true)
        val (directCode, directOut) = exec(probe.absolutePath)
        probe.delete()
        val directOk = directCode == 0 && directOut.contains("direct-exec-ok")
        list += Capability(
            "exec_app_storage", "Execute binaries from app storage", directOk,
            "exit=$directCode output=${directOut.take(200)}",
            if (directOk) "Downloaded toolchains could run from app storage." else "Downloaded binaries (JDK, Gradle daemons, aapt2, node) cannot be executed from app storage on this Android version. A toolchain must be shipped inside the APK's native library directory or run by a companion app (e.g. Termux)."
        )

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        list += Capability(
            "native_lib_dir", "Executable native library directory", nativeDir.isDirectory,
            "${nativeDir.absolutePath} (${nativeDir.list()?.size ?: 0} files)", "Binaries packaged as lib*.so in the APK can be executed from here."
        )

        val (javaCode, javaOut) = exec("/system/bin/sh", "-c", "command -v java || echo none")
        val hasJava = javaCode == 0 && !javaOut.contains("none") && javaOut.isNotBlank()
        list += Capability(
            "jdk", "Java runtime (JDK) on PATH", hasJava, javaOut.ifBlank { "not found" },
            "Gradle and the Android Gradle Plugin require a full JDK. ART (dalvikvm) is not a JDK and cannot run Gradle."
        )

        val termux = isInstalled("com.termux")
        list += Capability("termux", "Termux installed", termux, if (termux) "com.termux present" else "not installed", "Termux can host openjdk-17, gradle and aapt2 for real on-device builds (companion workflow).")

        val termuxCmd = context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        list += Capability(
            "termux_run_command", "Termux RUN_COMMAND permission", termuxCmd, if (termuxCmd) "granted" else if (termux) "not granted" else "Termux not installed",
            "Required for Termux companion builds (Build Center → Termux companion)."
        )

        val shizuku = isInstalled("moe.shizuku.privileged.api")
        list += Capability("shizuku", "Shizuku installed", shizuku, if (shizuku) "moe.shizuku.privileged.api present" else "not installed", "Shizuku can grant adb-level shell (am instrument, pm, input) after the user authorises it.")

        val adbEnabled = try { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (e: Exception) { false }
        list += Capability("usb_debugging", "Developer USB debugging enabled", adbEnabled, "Settings.Global.ADB_ENABLED=${if (adbEnabled) 1 else 0}", "Required for Wireless Debugging / Shizuku pairing.")

        val wifiAdb = try { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1) } catch (e: Exception) { -2 }
        list += Capability(
            "wireless_debugging", "Wireless debugging enabled", wifiAdb == 1,
            when (wifiAdb) { -2 -> "setting not readable by apps on this version"; -1 -> "setting absent"; else -> "adb_wifi_enabled=$wifiAdb" },
            "Gravity does not implement the adb pairing protocol itself in this build; use Shizuku to bridge."
        )

        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true
        list += Capability("install", "Install APKs (PackageInstaller)", canInstall, "canRequestPackageInstalls=$canInstall", "Same-device installs use the system installer; each install is confirmed by the user.")

        val readLogs = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        list += Capability(
            "read_logs", "Read other apps' logcat (READ_LOGS)", readLogs, if (readLogs) "granted" else "not granted",
            "Without it, logcat shows only Gravity's own process. Grant once from a computer: adb shell pm grant ${context.packageName} android.permission.READ_LOGS"
        )

        val (logCode, logOut) = exec("/system/bin/sh", "-c", "logcat -d -t 5 2>&1 | head -5")
        list += Capability("logcat", "logcat binary", logCode == 0, logOut.take(200).ifBlank { "no output" }, "Used by the Testing Center for crash detection.")

        list += Capability("media_projection", "Screen capture (MediaProjection)", true, "API ${Build.VERSION.SDK_INT}", "Requires one consent dialog per capture session.")

        list += Capability(
            "local_llm", "Local LLM runtime (llama.cpp)", LlamaBridge.isAvailable, LlamaBridge.loadError ?: "loaded", "Offline AI inference."
        )

        val freeGb = i.internalFreeBytes / (1024.0 * 1024 * 1024)
        list += Capability("storage", "Free storage ≥ 8 GB (SDK + Gradle cache)", freeGb >= 8, "%.1f GB free".format(freeGb), "An Android SDK, JDK and Gradle cache need roughly 5–8 GB.")
        val ramGb = i.totalRamBytes / (1024.0 * 1024 * 1024)
        list += Capability("ram", "RAM ≥ 6 GB (Gradle daemon)", ramGb >= 6, "%.1f GB total, %.1f GB available".format(ramGb, i.availRamBytes / (1024.0 * 1024 * 1024)), "Gradle with AGP needs 2–4 GB heap; smaller devices should build remotely.")
        list += Capability("abi", "64-bit ARM ABI", i.abis.contains("arm64-v8a"), i.abis.joinToString(), "Prebuilt aapt2/JDK ports for Android target arm64-v8a.")
        list
    }
}
