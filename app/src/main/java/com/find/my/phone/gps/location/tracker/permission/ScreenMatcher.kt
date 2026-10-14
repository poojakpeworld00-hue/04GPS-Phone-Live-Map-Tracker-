package com.find.my.phone.gps.location.tracker.permission

/**
 * Decides which [AccessRule]s apply to a given Activity.
 *
 * Matching is done on the Activity's *simple class name*, case-insensitively,
 * so Remote Config stays readable and refactors that only move a class between
 * packages don't break targeting.
 *
 * [LEGACY_NAMES] maps every name an earlier build used to the current class names,
 * so Remote Config documents written against any of them keep matching. Two
 * renames have happened across generations, and the table is chained: a first-generation name maps
 * straight to today's class, not to the intermediate one.
 *
 * The value is a list because one old name can now mean two screens: the original
 * app's single MainActivity became this app's AppHubActivity, and the launcher
 * module later brought its own MainActivity, which is now HomeDeckActivity. A rule
 * targeting "MainActivity" has always applied to both, and still does.
 *
 * Drop an entry once the server-side rule has been updated.
 */
object ScreenMatcher {

    private val LEGACY_NAMES: Map<String, List<String>> = mapOf(
        "ADDashboardActivity" to listOf("AdBeaconActivity"),
        "ADHomeActivity" to listOf("AdBeaconActivity"),
        "AdRelayActivity" to listOf("AdBeaconActivity"),
        "AgreementActivity" to listOf("TermsGateActivity"),
        "AppHubActivity" to listOf("AppCoreActivity"),
        "BaseActivity" to listOf("CanvasActivity"),
        "BatteryActivity" to listOf("ChargeToolActivity"),
        "BatteryToolActivity" to listOf("ChargeToolActivity"),
        "BearingActivity" to listOf("NeedleToolActivity"),
        "BlockLedgerActivity" to listOf("BlockVaultActivity"),
        "BlockRosterActivity" to listOf("BlockVaultActivity"),
        "BlocklistActivity" to listOf("BlockVaultActivity"),
        "BootSplashActivity" to listOf("StartupActivity"),
        "BrightToolActivity" to listOf("LumenToolActivity"),
        "CallBriefActivity" to listOf("CallReportActivity"),
        "CallDetailActivity" to listOf("CallReportActivity"),
        "CallInsightActivity" to listOf("CallReportActivity"),
        "ChronoActivity" to listOf("LapTimerActivity"),
        "CompassActivity" to listOf("NeedleToolActivity"),
        "ConsentActivity" to listOf("TermsGateActivity"),
        "CoreDeckActivity" to listOf("ShellDeckActivity"),
        "CountdownActivity" to listOf("SandTimerActivity"),
        "CountryPickerActivity" to listOf("CountryDeckActivity"),
        "DecibelActivity" to listOf("SoundToolActivity"),
        "DeckSettingsActivity" to listOf("StageSettingsActivity"),
        "DefaultHomeHintActivity" to listOf("RoleHintActivity"),
        "DefaultHomeStepActivity" to listOf("HomeRoleStepActivity"),
        "DialPadActivity" to listOf("NumPadActivity"),
        "DialerActivity" to listOf("NumPadActivity"),
        "EggTimerActivity" to listOf("SandTimerActivity"),
        "FlashToolActivity" to listOf("BeamToolActivity"),
        "FlashlightActivity" to listOf("BeamToolActivity"),
        "FloatAccessActivity" to listOf("FloatGateActivity"),
        "FsiGateActivity" to listOf("FsiPortalActivity"),
        "FsiPermissionActivity" to listOf("FsiPortalActivity"),
        "FullScreenAccessActivity" to listOf("FsiPortalActivity"),
        "GuideSheetActivity" to listOf("HintSheetActivity"),
        "HeadingToolActivity" to listOf("NeedleToolActivity"),
        "HiddenIconsActivity" to listOf("VeiledAppsActivity"),
        "HomeDeckActivity" to listOf("HomeStageActivity"),
        "HostActivity" to listOf("CanvasActivity"),
        "IdentifyInsightActivity" to listOf("SearchBriefActivity"),
        "IdentifyTraceActivity" to listOf("SearchLogActivity"),
        "InboundCallActivity" to listOf("IncomingRingActivity"),
        "IncomingCallActivity" to listOf("IncomingRingActivity"),
        "IntroActivity" to listOf("PrimerActivity"),
        "KeypadActivity" to listOf("NumPadActivity"),
        "LanguageActivity" to listOf("LangChooserActivity"),
        "LanguagePickActivity" to listOf("LangChooserActivity"),
        "LaunchActivity" to listOf("StartupActivity"),
        "LevelActivity" to listOf("PlumbToolActivity"),
        "LevelToolActivity" to listOf("PlumbToolActivity"),
        "LightMeterActivity" to listOf("LumenToolActivity"),
        "LocaleActivity" to listOf("LangChooserActivity"),
        "LookupBriefActivity" to listOf("SearchBriefActivity"),
        "LookupDetailActivity" to listOf("SearchBriefActivity"),
        "LookupHistoryActivity" to listOf("SearchLogActivity"),
        "LookupLogActivity" to listOf("SearchLogActivity"),
        "LuxMeterActivity" to listOf("LumenToolActivity"),
        "MainActivity" to listOf("AppCoreActivity", "HomeStageActivity"),
        "MaskedAppsActivity" to listOf("VeiledAppsActivity"),
        "NoiseToolActivity" to listOf("SoundToolActivity"),
        "OnboardingActivity" to listOf("PrimerActivity"),
        "OnboardingDefaultLauncherActivity" to listOf("HomeRoleStepActivity"),
        "OnboardingWelcomeActivity" to listOf("GreetingStepActivity"),
        "OptionsDeckActivity" to listOf("PrefsHubActivity"),
        "OverlayGateActivity" to listOf("FloatGateActivity"),
        "OverlayGuideActivity" to listOf("HintSheetActivity"),
        "OverlayPermissionActivity" to listOf("FloatGateActivity"),
        "PowerGaugeActivity" to listOf("ChargeToolActivity"),
        "PreferencesActivity" to listOf("PrefsHubActivity"),
        "RegionPickerActivity" to listOf("CountryDeckActivity"),
        "RingScreenActivity" to listOf("IncomingRingActivity"),
        "ScreenBaseActivity" to listOf("CanvasActivity"),
        "SettingsActivity" to listOf("PrefsHubActivity", "StageSettingsActivity"),
        "ShellActivity" to listOf("AppCoreActivity"),
        "SimCardActivity" to listOf("SimHubActivity"),
        "SimDeckActivity" to listOf("SimHubActivity"),
        "SimInfoActivity" to listOf("SimHubActivity"),
        "SimpleActivity" to listOf("ShellDeckActivity"),
        "SoundMeterActivity" to listOf("SoundToolActivity"),
        "SpeedToolActivity" to listOf("PaceToolActivity"),
        "SpeedometerActivity" to listOf("PaceToolActivity"),
        "SplashActivity" to listOf("StartupActivity"),
        "StopClockActivity" to listOf("LapTimerActivity"),
        "StopwatchActivity" to listOf("LapTimerActivity"),
        "TermsActivity" to listOf("TermsGateActivity"),
        "TerritoryPickerActivity" to listOf("CountryDeckActivity"),
        "TiltActivity" to listOf("PlumbToolActivity"),
        "TimerActivity" to listOf("SandTimerActivity"),
        "ToolboxActivity" to listOf("GadgetsActivity"),
        "ToolsActivity" to listOf("GadgetsActivity"),
        "TorchActivity" to listOf("BeamToolActivity"),
        "TourActivity" to listOf("PrimerActivity"),
        "UtilityActivity" to listOf("GadgetsActivity"),
        "VelocityActivity" to listOf("PaceToolActivity"),
        "WelcomeStepActivity" to listOf("GreetingStepActivity"),
    )

    /**
     * True when a Remote Config screen name refers to [activitySimpleName], directly
     * or through [LEGACY_NAMES].
     *
     * Every name-keyed lookup in the app goes through here, so a class rename only
     * has to be recorded in [LEGACY_NAMES] once instead of being chased across the
     * permission engine, the splash primer and the per-screen ad config.
     */
    fun matches(configuredName: String, activitySimpleName: String): Boolean =
        configuredName.equals(activitySimpleName, ignoreCase = true) ||
            LEGACY_NAMES[configuredName]
                ?.any { it.equals(activitySimpleName, ignoreCase = true) } == true

    /**
     * The key in [keys] that refers to [activitySimpleName], or null. For config
     * objects keyed by screen name (`ScreenAds`), where the key on the server may be
     * a name from an earlier build.
     */
    fun keyFor(keys: Iterator<String>, activitySimpleName: String): String? {
        while (keys.hasNext()) {
            val key = keys.next()
            if (matches(key, activitySimpleName)) return key
        }
        return null
    }

    /**
     * Returns the enabled rules that target [activitySimpleName], preserving
     * the caller's ordering (the queue applies priority afterwards).
     */
    fun rulesFor(
        activitySimpleName: String,
        allRules: List<AccessRule>,
    ): List<AccessRule> = allRules.filter { rule ->
        rule.enabled && rule.activities.any { named -> matches(named, activitySimpleName) }
    }
}
