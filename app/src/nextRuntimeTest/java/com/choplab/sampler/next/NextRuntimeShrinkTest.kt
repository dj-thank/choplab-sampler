package com.choplab.sampler.next

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.choplab.sampler.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import java.util.Locale
import java.util.regex.Pattern

/** Matching PreviewAndroidTest only. Owned emulator profile; no provider, model, microphone or media import. */
@RunWith(AndroidJUnit4::class)
class NextRuntimeShrinkTest {
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        assumeTrue(InstrumentationRegistry.getArguments().getString("choplabNextRuntimeFixture") == "true")
        check(it.packageName == "com.choplab.sampler.preview" && BuildConfig.BUILD_TYPE == "preview" && !BuildConfig.DEBUG)
    }

    @Test fun nextLauncherAndJaEnLabelsSurviveResourceShrinking() {
        val context = context()
        val info = context.packageManager.getActivityInfo(ComponentName(context, NextActivity::class.java), 0)
        assertTrue(info.exported)
        assertEquals("${context.packageName}.next", info.taskAffinity)
        for ((locale, title) in listOf(Locale.JAPANESE to "おとひろい NEXT", Locale.ENGLISH to "Earth Song NEXT")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            assertEquals(title, localized.resources.getString(info.labelRes))
        }
    }

    @Test fun optimizedNextActivityStartsAndShowsTheFourStagesThroughAndroidAccessibility() {
        val context = context()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(context, NextActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val welcome = "(?:Close and explore|閉じて始める)"
            val source = "1\\s+(?:Source|入れる)"
            // Compose exposes the label as a TextView child of the clickable action node.
            assertNotNull("NEXT never reached the welcome guide or editor", device.wait(
                Until.findObject(By.clickable(true).hasDescendant(By.text(Pattern.compile("(?:$welcome|$source)")))), 30_000))
            val welcomeText = By.text(Pattern.compile(welcome))
            val welcomeButton = By.clickable(true).hasDescendant(welcomeText)
            device.findObject(welcomeButton)?.click()
            assertTrue("Welcome guide did not close", device.wait(Until.gone(welcomeText), 10_000))
            for (stage in listOf(source, "2\\s+(?:Chop|チョップ)", "3\\s+(?:Beat|ビート)", "4\\s+(?:Save|保存)")) {
                val stageText = By.text(Pattern.compile(stage))
                assertTrue("NEXT stage is not visible: $stage", device.wait(Until.hasObject(stageText), 10_000))
                assertTrue("NEXT stage has no clickable action: $stage", device.wait(
                    Until.hasObject(By.clickable(true).hasDescendant(stageText)), 10_000))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun packagedRhinoAndReflectiveTimeAgoPatternsRemainUsable() {
        val loader = context().classLoader
        val type = loader.loadClass("org.mozilla.javascript.Context")
        val scriptable = loader.loadClass("org.mozilla.javascript.Scriptable")
        val context = type.getMethod("enter").invoke(null)
        try {
            type.getMethod("setOptimizationLevel", Int::class.javaPrimitiveType).invoke(context, -1)
            val scope = type.getMethod("initSafeStandardObjects").invoke(context)
            val result = type.getMethod("evaluateString", scriptable, String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Any::class.java).invoke(context, scope,
                "JSON.stringify({sound: 'audio'.split('').reverse().join(''), n: Math.round(1.6)})", "offline-fixture", 1, null)
            assertEquals("{\"sound\":\"oidua\",\"n\":2}", requireNotNull(result).toString())
        } finally { type.getMethod("exit").invoke(null) }
        for (locale in listOf("en", "ja")) {
            val pattern = loader.loadClass("org.schabi.newpipe.extractor.timeago.patterns.$locale")
            assertNotNull(pattern.getMethod("getInstance").invoke(null))
        }
    }

    @Test fun ortJniTensorRetainsItsRuntimeContract() {
        context()
        val samples = floatArrayOf(.25f, -.5f)
        // Exercise the exact packaged JNI names without loading a model or closing the shared environment.
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(samples), longArrayOf(1, 2)).use {
            val output = FloatArray(2); it.floatBuffer.get(output)
            assertArrayEquals(samples, output, 0f)
        }
    }
}
