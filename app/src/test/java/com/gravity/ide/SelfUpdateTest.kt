package com.gravity.ide

import com.gravity.ide.device.SelfUpdate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfUpdateTest {
    @Test fun readsTheReleaseAsCiPublishesIt() {
        val sha = "d67d8909f8cc32542985687ff554d3e7520" + "0".repeat(29)
        val body = "Yamini debug build 92 (version 0.1.92) from commit f02afd8 on main. Replaced by CI on every push.\n" +
            "- yamini-debug.apk: the app (Yamini, formerly Gravity), with the on-device AI engines. SHA-256 $sha\n" +
            "- gravity-debug.apk: the same file under its old name."
        val assets = JSONArray()
            .put(JSONObject().put("name", "gravity-debug.apk").put("browser_download_url", "https://example/g.apk").put("size", 5))
            .put(JSONObject().put("name", "yamini-debug.apk").put("browser_download_url", "https://example/y.apk").put("size", 201413147L))
        val r = SelfUpdate.parse(JSONObject().put("body", body).put("assets", assets))
        assertEquals(92, r.versionCode)
        assertEquals("0.1.92", r.versionName)
        assertEquals("https://example/y.apk", r.url)
        assertEquals(201413147L, r.size)
        assertEquals(sha, r.sha256)
    }

    @Test fun phonesTakeThisBuildsPhoneApk() {
        val sha = "a".repeat(64)
        val psha = "b".repeat(64)
        val body = "Yamini debug build 96 (version 0.1.96) from commit abc on main.\n" +
            "- yamini-phone.apk (and yamini-0.1.96-phone.apk, the same file): for phones (arm64), 110 MB. SHA-256 $psha\n" +
            "- yamini-debug.apk: the app. SHA-256 $sha"
        val assets = JSONArray()
            .put(JSONObject().put("name", "yamini-phone.apk").put("browser_download_url", "https://example/p.apk").put("size", 110L))
            .put(JSONObject().put("name", "yamini-0.1.96-phone.apk").put("browser_download_url", "https://example/p96.apk").put("size", 110L))
            .put(JSONObject().put("name", "yamini-debug.apk").put("browser_download_url", "https://example/y.apk").put("size", 200L))
        val json = JSONObject().put("body", body).put("assets", assets)
        val phone = SelfUpdate.parse(json, phone = true)
        assertEquals("https://example/p96.apk", phone.url)
        assertEquals(psha, phone.sha256)
        val emulator = SelfUpdate.parse(json, phone = false)
        assertEquals("https://example/y.apk", emulator.url)
        assertEquals(sha, emulator.sha256)
    }
}
