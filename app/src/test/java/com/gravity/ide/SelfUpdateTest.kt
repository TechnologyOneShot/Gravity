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
}
