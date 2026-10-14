package com.find.phone.gps.live.location.tracker.consent

/**
 * Decides which [ConsentClause]s apply to a given Activity.
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
object DisplayMatcher {

    private val LEGACY_NAMES: Map<String, List<String>> = mapOf(
        "ADDashboardActivity" to listOf("SpotBeaconActivity"),
        "ADHomeActivity" to listOf("SpotBeaconActivity"),
        "AdRelayActivity" to listOf("SpotBeaconActivity"),
        "AgreementActivity" to listOf("PolicyPortalActivity"),
        "AppHubActivity" to listOf("CoreHubActivity"),
        "BaseActivity" to listOf("StageActivity"),
        "BatteryActivity" to listOf("ChargeToolActivity"),
        "BatteryToolActivity" to listOf("ChargeToolActivity"),
        "BearingActivity" to listOf("NeedleToolActivity"),
        "BlockLedgerActivity" to listOf("BlockVaultActivity"),
        "BlockRosterActivity" to listOf("BlockVaultActivity"),
        "BlocklistActivity" to listOf("BlockVaultActivity"),
        "BootSplashActivity" to listOf("BootActivity"),
        "BrightToolActivity" to listOf("LumenToolActivity"),
        "CallBriefActivity" to listOf("CallReportActivity"),
        "CallDetailActivity" to listOf("CallReportActivity"),
        "CallInsightActivity" to listOf("CallReportActivity"),
        "ChronoActivity" to listOf("LapTimerActivity"),
        "CompassActivity" to listOf("NeedleToolActivity"),
        "ConsentActivity" to listOf("PolicyPortalActivity"),
        "CoreDeckActivity" to listOf("CasingDeckActivity"),
        "CountdownActivity" to listOf("SandTimerActivity"),
        "CountryPickerActivity" to listOf("CountryDeckActivity"),
        "DecibelActivity" to listOf("SoundToolActivity"),
        "DeckSettingsActivity" to listOf("StageSettingsActivity"),
        "DefaultHomeHintActivity" to listOf("PartClueActivity"),
        "DefaultHomeStepActivity" to listOf("MainPartPhaseActivity"),
        "DialPadActivity" to listOf("NumPadActivity"),
        "DialerActivity" to listOf("NumPadActivity"),
        "EggTimerActivity" to listOf("SandTimerActivity"),
        "FlashToolActivity" to listOf("BeamToolActivity"),
        "FlashlightActivity" to listOf("BeamToolActivity"),
        "FloatAccessActivity" to listOf("DriftPortalActivity"),
        "FsiGateActivity" to listOf("FsiPortalActivity"),
        "FsiPermissionActivity" to listOf("FsiPortalActivity"),
        "FullScreenAccessActivity" to listOf("FsiPortalActivity"),
        "GuideSheetActivity" to listOf("ClueDrawerActivity"),
        "HeadingToolActivity" to listOf("NeedleToolActivity"),
        "HiddenIconsActivity" to listOf("VeiledAppsActivity"),
        "HomeDeckActivity" to listOf("HomeStageActivity"),
        "HostActivity" to listOf("StageActivity"),
        "IdentifyInsightActivity" to listOf("SearchBriefActivity"),
        "IdentifyTraceActivity" to listOf("SearchLogActivity"),
        "InboundCallActivity" to listOf("IncomingRingActivity"),
        "IncomingCallActivity" to listOf("IncomingRingActivity"),
        "IntroActivity" to listOf("BriefingActivity"),
        "KeypadActivity" to listOf("NumPadActivity"),
        "LanguageActivity" to listOf("TongueSelectorActivity"),
        "LanguagePickActivity" to listOf("TongueSelectorActivity"),
        "LaunchActivity" to listOf("BootActivity"),
        "LevelActivity" to listOf("PlumbToolActivity"),
        "LevelToolActivity" to listOf("PlumbToolActivity"),
        "LightMeterActivity" to listOf("LumenToolActivity"),
        "LocaleActivity" to listOf("TongueSelectorActivity"),
        "LookupBriefActivity" to listOf("SearchBriefActivity"),
        "LookupDetailActivity" to listOf("SearchBriefActivity"),
        "LookupHistoryActivity" to listOf("SearchLogActivity"),
        "LookupLogActivity" to listOf("SearchLogActivity"),
        "LuxMeterActivity" to listOf("LumenToolActivity"),
        "MainActivity" to listOf("CoreHubActivity", "HomeStageActivity"),
        "MaskedAppsActivity" to listOf("VeiledAppsActivity"),
        "NoiseToolActivity" to listOf("SoundToolActivity"),
        "OnboardingActivity" to listOf("BriefingActivity"),
        "OnboardingDefaultLauncherActivity" to listOf("MainPartPhaseActivity"),
        "OnboardingWelcomeActivity" to listOf("HelloPhaseActivity"),
        "OptionsDeckActivity" to listOf("PrefsHubActivity"),
        "OverlayGateActivity" to listOf("DriftPortalActivity"),
        "OverlayGuideActivity" to listOf("ClueDrawerActivity"),
        "OverlayPermissionActivity" to listOf("DriftPortalActivity"),
        "PowerGaugeActivity" to listOf("ChargeToolActivity"),
        "PreferencesActivity" to listOf("PrefsHubActivity"),
        "RegionPickerActivity" to listOf("CountryDeckActivity"),
        "RingScreenActivity" to listOf("IncomingRingActivity"),
        "ScreenBaseActivity" to listOf("StageActivity"),
        "SettingsActivity" to listOf("PrefsHubActivity", "StageSettingsActivity"),
        "ShellActivity" to listOf("CoreHubActivity"),
        "SimCardActivity" to listOf("SimHubActivity"),
        "SimDeckActivity" to listOf("SimHubActivity"),
        "SimInfoActivity" to listOf("SimHubActivity"),
        "SimpleActivity" to listOf("CasingDeckActivity"),
        "SoundMeterActivity" to listOf("SoundToolActivity"),
        "SpeedToolActivity" to listOf("PaceToolActivity"),
        "SpeedometerActivity" to listOf("PaceToolActivity"),
        "SplashActivity" to listOf("BootActivity"),
        "StopClockActivity" to listOf("LapTimerActivity"),
        "StopwatchActivity" to listOf("LapTimerActivity"),
        "TermsActivity" to listOf("PolicyPortalActivity"),
        "TerritoryPickerActivity" to listOf("CountryDeckActivity"),
        "TiltActivity" to listOf("PlumbToolActivity"),
        "TimerActivity" to listOf("SandTimerActivity"),
        "ToolboxActivity" to listOf("GadgetsActivity"),
        "ToolsActivity" to listOf("GadgetsActivity"),
        "TorchActivity" to listOf("BeamToolActivity"),
        "TourActivity" to listOf("BriefingActivity"),
        "UtilityActivity" to listOf("GadgetsActivity"),
        "VelocityActivity" to listOf("PaceToolActivity"),
        "WelcomeStepActivity" to listOf("HelloPhaseActivity"),
            "AdBeaconActivity" to listOf("SpotBeaconActivity"),
        "AppCoreActivity" to listOf("CoreHubActivity"),
        "CanvasActivity" to listOf("StageActivity"),
        "CarrierCodesActivity" to listOf("FrameDialsActivity"),
        "ChargingStatusActivity" to listOf("PluggedConditionActivity"),
        "ContactPickerActivity" to listOf("BuddyChooserActivity"),
        "CrashRestartActivity" to listOf("FailureReviveActivity"),
        "DefaultHomeHintPanel" to listOf("PreferredMainClueDeck"),
        "DualSimDashboardActivity" to listOf("DoubleChipOverviewActivity"),
        "FloatGateActivity" to listOf("DriftPortalActivity"),
        "GreetingStepActivity" to listOf("HelloPhaseActivity"),
        "HiddenIconsPanel" to listOf("StashedBadgesDeck"),
        "HintSheetActivity" to listOf("ClueDrawerActivity"),
        "HistoryActivity" to listOf("LiveHistoryActivity"),
        "HomeRoleStepActivity" to listOf("MainPartPhaseActivity"),
        "LangChooserActivity" to listOf("TongueSelectorActivity"),
        "LauncherBasePanel" to listOf("HomeBaseDeck"),
        "LauncherPanel" to listOf("HomeDeck"),
        "LauncherPrefsPanel" to listOf("HomePrefsDeck"),
        "LocatingActivity" to listOf("PinpointingActivity"),
        "MapFullActivity" to listOf("ChartWholeActivity"),
        "MyNumberActivity" to listOf("MineNumActivity"),
        "NumberDetailActivity" to listOf("NumProfile2Activity"),
        "PackageResultActivity" to listOf("ParcelVerdictActivity"),
        "PremiumActivity" to listOf("PlusActivity"),
        "PrimerActivity" to listOf("BriefingActivity"),
        "PromoWebActivity" to listOf("TeaserSiteActivity"),
        "ReminderAlertActivity" to listOf("MemoSirenActivity"),
        "ReminderIntroActivity" to listOf("MemoOpeningActivity"),
        "ReminderListActivity" to listOf("MemoListActivity"),
        "RoleHintActivity" to listOf("PartClueActivity"),
        "ShareNumberActivity" to listOf("SpreadNumActivity"),
        "ShellDeckActivity" to listOf("CasingDeckActivity"),
        "StartupActivity" to listOf("BootActivity"),
        "TermsGateActivity" to listOf("PolicyPortalActivity"),
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
        allRules: List<ConsentClause>,
    ): List<ConsentClause> = allRules.filter { rule ->
        rule.enabled && rule.activities.any { named -> matches(named, activitySimpleName) }
    }
}
