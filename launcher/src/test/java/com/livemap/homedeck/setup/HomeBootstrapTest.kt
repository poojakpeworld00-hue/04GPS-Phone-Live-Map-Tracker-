package com.livemap.homedeck.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBootstrapTest {
    private val blob = """
        {"organic":{
            "default_launcher_prompt": false,
            "panels": {"apps": false, "messages": true},
            "guide": {"enabled": true, "swipe_right_contacts": false, "swipe_left_apps": true, "swipe_up_drawer": true},
            "gestures": {
                "left_swipe":  {"inter_enabled": true, "inter_counter": 10, "url_enabled": false, "url": "https://example.com/promo"},
                "right_swipe": {"inter_enabled": false, "inter_counter": 10, "url_enabled": true, "url": " https://example.com/offer "},
                "drawer_open": {"inter_enabled": false, "inter_counter": 0, "url_enabled": false, "url": ""}
            }
        },
        "marketing":{
            "guide": {"enabled": false}
        }}
    """.trimIndent()

    @Test
    fun readsEveryBlockFromTheOrganicBranch() {
        val s = HomeBootstrap.parse(blob, organic = true)
        assertFalse(s.defaultLauncherPrompt)
        assertFalse(s.panelEnabled(HomeBootstrap.PANEL_APPS))
        assertTrue(s.panelEnabled(HomeBootstrap.PANEL_HOST))
        assertTrue(s.guideEnabled)
        assertFalse(s.guideStepEnabled("swipe_right_contacts"))
        assertTrue(s.guideStepEnabled("swipe_left_apps"))
        assertTrue(s.guideStepEnabled("not_a_step"))
        assertTrue(s.gesture("left_swipe").interEnabled)
        assertEquals(10, s.gesture("left_swipe").interCounter)
        assertFalse(s.gesture("left_swipe").urlEnabled)
        assertTrue(s.gesture("right_swipe").urlEnabled)
        assertEquals("https://example.com/offer", s.gesture("right_swipe").url)
        assertEquals(HomeBootstrap.GestureSetup(), s.gesture("drawer_open"))
    }

    @Test
    fun marketingBranchAndMissingFieldsFallBackToDefaults() {
        val s = HomeBootstrap.parse(blob, organic = false)
        assertTrue(s.defaultLauncherPrompt)
        assertTrue(s.panelEnabled(HomeBootstrap.PANEL_APPS))
        assertTrue(s.panelEnabled(HomeBootstrap.PANEL_HOST))
        assertFalse(s.guideEnabled)
        assertFalse(s.guideStepEnabled("swipe_left_apps"))
        assertEquals(HomeBootstrap.GestureSetup(), s.gesture("left_swipe"))
    }

    @Test
    fun guideMasterOffSilencesEveryStep() {
        val s = HomeBootstrap.parse("""{"organic":{"guide":{"enabled":false,"swipe_up_drawer":true}}}""", organic = true)
        assertFalse(s.guideStepEnabled("swipe_up_drawer"))
    }

    @Test
    fun flatBlobWithoutAudienceBranchesAppliesToBoth() {
        val flat = """{"default_launcher_prompt":false}"""
        assertFalse(HomeBootstrap.parse(flat, organic = true).defaultLauncherPrompt)
        assertFalse(HomeBootstrap.parse(flat, organic = false).defaultLauncherPrompt)
    }

    @Test
    fun blankGarbageAndMissingBranchYieldDefaults() {
        listOf("", "   ", "not json", "[1]", """{"organic":{}}""", """{"marketing":{"guide":{"enabled":false}}}""").forEach { raw ->
            val s = HomeBootstrap.parse(raw, organic = true)
            assertTrue(raw, s.defaultLauncherPrompt)
            assertTrue(raw, s.panelEnabled(HomeBootstrap.PANEL_APPS))
            assertTrue(raw, s.guideStepEnabled("swipe_up_drawer"))
            assertFalse(raw, s.gesture("left_swipe").interEnabled)
        }
        assertEquals(HomeBootstrap.DEFAULT, HomeBootstrap.parse("", organic = true))
    }

    @Test
    fun osStyleIsPerAudienceAndOffByDefault() {
        val raw = """{"organic":{"os_style": true},"marketing":{"guide":{"enabled":true}}}"""
        assertTrue(HomeBootstrap.parse(raw, organic = true).osStyle)
        assertFalse(HomeBootstrap.parse(raw, organic = false).osStyle)
        assertFalse(HomeBootstrap.parse("", organic = true).osStyle)
        assertFalse(HomeBootstrap.parse("not json", organic = false).osStyle)
        assertTrue(HomeBootstrap.parse("""{"os_style": true}""", organic = false).osStyle)
    }

    @Test
    fun searchWidgetDefaultsToGoogle() {
        val raw = """{"organic":{"search_widget": " Chrome "},"marketing":{"search_widget": "bing"}}"""
        assertEquals(HomeBootstrap.SEARCH_WIDGET_CHROME, HomeBootstrap.parse(raw, organic = true).searchWidget)
        assertEquals(HomeBootstrap.SEARCH_WIDGET_GOOGLE, HomeBootstrap.parse(raw, organic = false).searchWidget)
        assertEquals(HomeBootstrap.SEARCH_WIDGET_GOOGLE, HomeBootstrap.parse("", organic = true).searchWidget)
    }

    @Test
    fun firstPageWidgetsAreOnByDefault() {
        val raw = """{"organic":{"should_show_time_widget": false},"marketing":{"should_show_search_widget": false}}"""
        val organic = HomeBootstrap.parse(raw, organic = true)
        val marketing = HomeBootstrap.parse(raw, organic = false)
        assertFalse(organic.showTimeWidget)
        assertTrue(organic.showSearchWidget)
        assertTrue(marketing.showTimeWidget)
        assertFalse(marketing.showSearchWidget)
        assertTrue(HomeBootstrap.parse("", organic = true).showTimeWidget)
        assertTrue(HomeBootstrap.parse("not json", organic = false).showSearchWidget)
    }

    @Test
    fun negativeCounterClampsToZero() {
        val s = HomeBootstrap.parse("""{"gestures":{"left_swipe":{"inter_counter":-3}}}""", organic = true)
        assertEquals(0, s.gesture("left_swipe").interCounter)
    }

    @Test
    fun drawerAppAdsAreOffWithoutTheSwitch() {
        val s = HomeBootstrap.parse(
            """{"drawerAppAds":[{"logo":"l","landing_url":"https://a","label":"A"}]}""", organic = true,
        )
        assertFalse(s.drawerAppAdEnabled)
        assertEquals(8, s.drawerAppAdEvery)
        assertEquals(1, s.drawerAppAds.size)
    }

    @Test
    fun drawerAppAdsDropEntriesWithoutALandingUrl() {
        val s = HomeBootstrap.parse(
            """{"drawerAppAdEnabled":true,"drawerAppAdEvery":0,"drawerAppAds":[
                {"logo":" https://logo ","landing_url":" https://a ","label":" A "},
                {"logo":"x","landing_url":"","label":"no url"},
                "not an object"
            ]}""",
            organic = true,
        )
        assertTrue(s.drawerAppAdEnabled)
        assertEquals(1, s.drawerAppAdEvery)
        assertEquals(listOf(HomeBootstrap.SponsoredApp("https://logo", "https://a", "A")), s.drawerAppAds)
    }
}
