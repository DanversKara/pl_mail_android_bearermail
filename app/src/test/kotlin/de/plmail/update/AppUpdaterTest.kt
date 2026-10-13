package de.plmail.update

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading GitHub's "latest release" answer. Robolectric for the real org.json; the network is not
 * involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppUpdaterTest {

    private fun release(tag: String, prerelease: Boolean = false) =
        JSONObject()
            .put("tag_name", tag)
            .put("prerelease", prerelease)
            .put("body", "Notes")
            .put("html_url", "https://github.com/x/y/releases/tag/$tag")
            .put(
                "assets",
                JSONArray()
                    .put(asset("plmail-${tag.removePrefix("v")}-foss.apk"))
                    .put(asset("plmail-${tag.removePrefix("v")}-google.apk"))
                    .put(asset("plmail-${tag.removePrefix("v")}-foss.aab")),
            )

    private fun asset(name: String) =
        JSONObject()
            .put("name", name)
            .put("size", 1234L)
            .put("browser_download_url", "https://example/$name")

    @Test
    fun `a newer release offers the APK for the running flavour`() {
        val update = AppUpdater.parse(release("v0.0.31"), current = "0.0.30", flavour = "google")!!
        assertEquals("0.0.31", update.version)
        assertTrue(update.apkUrl.endsWith("plmail-0.0.31-google.apk"))
    }

    @Test
    fun `the same or an older release is no update`() {
        assertNull(AppUpdater.parse(release("v0.0.30"), current = "0.0.30", flavour = "foss"))
        assertNull(AppUpdater.parse(release("v0.0.29"), current = "0.0.30", flavour = "foss"))
    }

    @Test
    fun `pre-releases are not offered`() {
        assertNull(
            AppUpdater.parse(
                release("v0.1.0-rc1", prerelease = true),
                current = "0.0.30",
                flavour = "foss",
            )
        )
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(AppUpdater.compareVersions("0.0.10", "0.0.9") > 0)
        assertTrue(AppUpdater.compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, AppUpdater.compareVersions("0.2.0-rc1", "0.2.0"))
    }
}
