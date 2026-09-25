package com.patjackson.latertext

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patjackson.latertext.core.designsystem.LaterTextTheme
import com.patjackson.latertext.feature.composer.*
import com.patjackson.latertext.feature.schedules.*
import com.patjackson.latertext.product.ProductIds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** Real feature composables with static fixtures and inert callbacks; cannot schedule or send. */
@RunWith(AndroidJUnit4::class)
class ProductAtlasCaptureTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val app: Context get() = instrumentation.targetContext.applicationContext
    private val knownIds = setOf(ProductIds.component_latertext_upcoming_content,
        ProductIds.component_latertext_composer_content, ProductIds.component_latertext_schedule_editor_content)
    private lateinit var output: File

    @Test fun captureSyntheticMatrixRow() {
        assumeTrue(arguments.getString("latertextAtlasDisposable") == "true")
        assertTrue("Capture requires an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.READ_SMS))
        val profile = arguments.getString("latertextAtlasProfile")
        assertTrue(profile in setOf("phone_normal", "phone_font200"))
        assertEquals(if (profile == "phone_font200") 2f else 1f, app.resources.configuration.fontScale, 0.001f)
        assertEquals(420, app.resources.displayMetrics.densityDpi)
        assertEquals("en", app.resources.configuration.locales[0].language)
        val commit = requireNotNull(arguments.getString("latertextAtlasCommit"))
        assertTrue(commit.matches(Regex("[0-9a-f]{40}")))
        val apkHash = requireNotNull(arguments.getString("latertextAtlasApkSha256"))
        assertEquals(apkHash, hash(File(app.applicationInfo.sourceDir)))
        output = File(app.cacheDir, "product-atlas/$profile")
        assertFalse(output.exists())
        assertTrue(output.mkdirs())
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        var target by mutableStateOf(AppScreen.UPCOMING)
        composeRule.setContent {
            LaterTextTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }
                        .testTag(target.productComponentId)) {
                        when (target) {
                            AppScreen.UPCOMING -> UpcomingScreen(emptyList(), false, {}, {}, {})
                            AppScreen.COMPOSER -> ComposerScreen(ComposerUiState(), {}, {}, {}, {}, {}, {}, {}, {})
                            else -> ScheduleEditorScreen(ScheduleEditorUiState(
                                date = LocalDate.of(2026, 10, 1), time = LocalTime.of(9, 0),
                                weekDays = setOf(java.time.DayOfWeek.THURSDAY), exactTimingAvailable = true,
                            ), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        for ((name, screen) in listOf("empty_upcoming" to AppScreen.UPCOMING,
            "blank_composer" to AppScreen.COMPOSER, "schedule_editor" to AppScreen.SCHEDULE_EDITOR)) {
            composeRule.runOnIdle { target = screen }
            composeRule.waitForIdle()
            waitTag(screen.productComponentId)
            capture(name, screen.productComponentId)
        }
        File(output, "attestation.json").writeText(JSONObject()
            .put("git_commit_sha", commit).put("definition_digest", ProductIds.PRODUCT_DEFINITION_DIGEST)
            .put("apk_sha256", apkHash).put("package_name", app.packageName)
            .put("app_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            .put("density_dpi", app.resources.displayMetrics.densityDpi)
            .put("font_scale_ppm", (app.resources.configuration.fontScale * 1_000_000).roundToInt())
            .put("locale", app.resources.configuration.locales[0].toLanguageTag()).toString(2))
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            check(result.size < 2_000) { "Accessibility tree exceeds capture budget" }
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun waitTag(id: String) = waitUntil { nodes().any { it.isVisibleToUser && it.viewIdResourceName == id } }
    private fun waitText(text: String) = waitUntil { nodes().any { it.isVisibleToUser && it.text?.toString() == text } }
    private fun click(text: String) {
        waitText(text)
        var node = nodes().first { it.isVisibleToUser && it.text?.toString() == text }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        // No raw accessibility text, screenshots, exceptions or account data in failures.
        throw AssertionError("Synthetic capture did not reach its expected UI checkpoint")
    }
    private fun capture(name: String, expectedId: String) {
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val snapshot = nodes().filter { it.isVisibleToUser && it.packageName?.toString() == app.packageName && it.viewIdResourceName in knownIds }
        assertTrue("Expected Product ID must be measured", snapshot.any { it.viewIdResourceName == expectedId })
        assertEquals("Fixture must have unique product nodes", snapshot.size, snapshot.map { it.viewIdResourceName }.distinct().size)
        // AndroidX enables drawing and waits for redraw on ATD images before sampling.
        val bitmap = takeScreenshot()
        try {
            assertEquals(1080, bitmap.width)
            assertEquals(2400, bitmap.height)
            val targetBounds = Rect().also(snapshot.first { it.viewIdResourceName == expectedId }::getBoundsInScreen)
            assertTrue(targetBounds.left >= 0 && targetBounds.top >= 0 && targetBounds.right <= bitmap.width && targetBounds.bottom <= bitmap.height && targetBounds.width() > 0 && targetBounds.height() > 0)
            val targetPixels = IntArray(targetBounds.width() * targetBounds.height())
            bitmap.getPixels(targetPixels, 0, targetBounds.width(), targetBounds.left, targetBounds.top, targetBounds.width(), targetBounds.height())
            assertTrue("Product target screenshot is blank; drawing may be disabled", targetPixels.any { it != targetPixels[0] })
            val hierarchy = JSONArray()
            snapshot.forEachIndexed { index, node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height && bounds.width() > 0 && bounds.height() > 0)
                var parent = node.parent
                while (parent != null && parent.viewIdResourceName !in knownIds) parent = parent.parent
                val parentIndex = snapshot.indexOfFirst { it.viewIdResourceName == parent?.viewIdResourceName }
                hierarchy.put(JSONObject()
                    .put("node_id", "node-$index")
                    .put("parent_node_id", if (parentIndex >= 0) "node-$parentIndex" else JSONObject.NULL)
                    .put("bounds_px", JSONObject().put("x", bounds.left).put("y", bounds.top).put("width", bounds.width()).put("height", bounds.height()))
                    .put("product_entity_id", node.viewIdResourceName)
                    .put("role", if (node.isClickable) "button" else "group")
                    .put("enabled", node.isEnabled))
            }
            File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(output, "$name.json").writeText(JSONObject()
                .put("coordinate_space", "source_px_v1")
                .put("source_width_px", bitmap.width).put("source_height_px", bitmap.height)
                .put("nodes", hierarchy).put("unknowns", JSONArray()).toString(2))
        } finally { bitmap.recycle() }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
