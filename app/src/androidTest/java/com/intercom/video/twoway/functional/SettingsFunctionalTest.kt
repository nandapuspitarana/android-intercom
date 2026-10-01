package com.intercom.video.twoway.functional

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intercom.video.twoway.ui.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Settings through the real UI: toggles are stored and take effect, and the language switches between English and Indonesian. */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class SettingsFunctionalTest {
    private val setup = FunctionalTestRule()
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetLanguage() = LocaleHelper.save(context, LocaleHelper.SYSTEM)

    @After
    fun restoreLanguage() = LocaleHelper.save(context, LocaleHelper.SYSTEM)

    private fun openSettings() {
        setup.launch()
        compose.waitForTag("open_settings")
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForTag("switch_listen_background")
    }

    private fun stored() = runBlocking { setup.container.settings.settings.first() }

    @Test
    fun theTogglesAreStoredAndTheEngineFollowsThem() {
        openSettings()
        compose.onNodeWithTag("switch_listen_background").assertIsOn()
        compose.onNodeWithTag("switch_auto_reject").performClick()
        compose.onNodeWithTag("switch_start_on_boot").performClick()
        assertTrue("stored", await { stored().autoRejectUnknown && stored().startOnBoot })
        // the running engine picks up "ignore unpaired phones" at once
        assertTrue(await { setup.container.runtimeOrNull!!.engine.autoRejectUnknown })

        compose.onNodeWithTag("switch_auto_reject").performClick()
        assertTrue(await { !setup.container.runtimeOrNull!!.engine.autoRejectUnknown })
        assertFalse(stored().autoRejectUnknown)
    }

    @Test
    fun theLanguageSwitchesToIndonesianAndBack() {
        openSettings()
        compose.onNodeWithTag("settings_back").assertTextContains("Back")

        compose.onNodeWithTag("language_in").performClick()
        // the screen is recreated in Indonesian
        compose.waitForText("Pengaturan")
        assertEquals(LocaleHelper.INDONESIAN, LocaleHelper.saved(context))
        assertTrue(await { stored().language == LocaleHelper.INDONESIAN })

        compose.onNodeWithTag("language_en").performClick()
        compose.waitForText("Settings")
        assertEquals(LocaleHelper.ENGLISH, LocaleHelper.saved(context))
    }

    @Test
    fun theBatteryGuidanceIsShownInSettings() {
        openSettings()
        compose.waitForTag("guidance_battery")
        compose.waitForTag("guidance_vendor")
    }
}
