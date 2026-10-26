# Analytics event registry — GPS Phone Live Map Tracker

Generated from the source (`recordKeyEvent`, `safeLog`, `recordAdEvent`, `recordAdLoadError`, launcher `onEvent`).
Every name passes through `safeEventName` in `monetize/logic/SpotIncomeGauge.kt`: characters outside `[A-Za-z0-9_]` become `_`, max 40 chars.
Until the user reaches home, each event is sent as `first_<name>` (`EventSession`). Debug builds only log to logcat (`AdEvents`).

## Revenue

| Event | Params | Where |
|---|---|---|
| `ad_impression` (Firebase standard) | ad_platform, ad_source, ad_format, ad_unit_name, value, currency, precision, placement, segment | `SpotIncomeGauge.recordPaidEvent`, from every AdMob `OnPaidEventListener` |

## Ad lifecycle (`ad_<type>_<phase>`, params screen / network / error_code)

| Event | Call sites |
|---|---|
| `ad_app_open_close` | CoreOpenSpotBook.kt:165 |
| `ad_app_open_failed / ad_app_open_no_fill` | CoreOpenSpotBook.kt:93 |
| `ad_app_open_loaded` | CoreOpenSpotBook.kt:77 |
| `ad_app_open_req` | CoreOpenSpotBook.kt:71 |
| `ad_app_open_show` | CoreOpenSpotBook.kt:198 |
| `ad_back_interstitial_close` | TakeoverRear.kt:171, TakeoverRear.kt:246 |
| `ad_back_interstitial_failed / ad_back_interstitial_no_fill` | TakeoverRear.kt:240, TakeoverRear.kt:69 |
| `ad_back_interstitial_loaded` | TakeoverRear.kt:230, TakeoverRear.kt:62 |
| `ad_back_interstitial_req` | TakeoverRear.kt:224, TakeoverRear.kt:54 |
| `ad_back_interstitial_show` | TakeoverRear.kt:165, TakeoverRear.kt:253 |
| `ad_back_interstitial_show_failed` | TakeoverRear.kt:177 |
| `ad_banner_failed / ad_banner_no_fill` | RibbonSpots.kt:198, RibbonSpots.kt:326 |
| `ad_banner_impression` | RibbonSpots.kt:213, RibbonSpots.kt:340 |
| `ad_banner_loaded` | RibbonSpots.kt:175, RibbonSpots.kt:315 |
| `ad_banner_req` | RibbonSpots.kt:223, RibbonSpots.kt:302 |
| `ad_banner_shown` | RibbonSpots.kt:188 |
| `ad_interstitial_close` | TakeoverStandard.kt:362, TakeoverStandard.kt:518 |
| `ad_interstitial_failed / ad_interstitial_no_fill` | TakeoverStandard.kt:222, TakeoverStandard.kt:411, TakeoverStandard.kt:510 |
| `ad_interstitial_impression` | TakeoverStandard.kt:537 |
| `ad_interstitial_loaded` | TakeoverStandard.kt:228, TakeoverStandard.kt:404, TakeoverStandard.kt:497 |
| `ad_interstitial_req` | TakeoverStandard.kt:215, TakeoverStandard.kt:394, TakeoverStandard.kt:486 |
| `ad_interstitial_show` | TakeoverStandard.kt:355, TakeoverStandard.kt:526 |
| `ad_native_banner_failed / ad_native_banner_no_fill` | EmbeddedTeaserRibbon.kt:72 |
| `ad_native_banner_impression` | EmbeddedTeaserRibbon.kt:290, EmbeddedTeaserRibbon.kt:78 |
| `ad_native_banner_loaded` | EmbeddedTeaserRibbon.kt:60 |
| `ad_native_banner_req` | EmbeddedTeaserRibbon.kt:82 |
| `ad_native_failed / ad_native_no_fill` | EmbeddedTeaser.kt:152 |
| `ad_native_impression` | EmbeddedTeaser.kt:160, EmbeddedTeaser.kt:425, EmbeddedTeaser.kt:660 |
| `ad_native_loaded` | EmbeddedTeaser.kt:133 |
| `ad_native_req` | EmbeddedTeaser.kt:166 |
| `ad_rewarded_close` | BonusTeaser.kt:200 |
| `ad_rewarded_failed / ad_rewarded_no_fill` | BonusTeaser.kt:90 |
| `ad_rewarded_loaded` | BonusTeaser.kt:82 |
| `ad_rewarded_req` | BonusTeaser.kt:70 |
| `ad_rewarded_show` | BonusTeaser.kt:195 |

## Dynamic events

| Pattern | Source |
|---|---|
| `screen_<activity>` | `NavPressTracker` (activities outside the base class) |
| `<screen>_back_press`, `<screen>_home_press` | `NavPressTracker`, `StageActivity` / `CasingDeckActivity` |
| `Permission_<PERM>_Show / _Allow / _Deny / _Bypass` | `recordPermissionResult` and permission call sites |
| `PermissionSheet_Ask_<key>` | `ConsentDrawerModal` |
| `Back_Inter_Load_FAILED_<code>` | `TakeoverRear` |

## Named events

| Event (as sent) | Raw name in code | Call sites |
|---|---|---|
| `appopen_ad_dismissed` | `appopen_ad_dismissed` | CoreOpenSpotBook.kt:164 |
| `appopen_ad_fail` | `appopen_ad_fail` | CoreOpenSpotBook.kt:92 |
| `appopen_ad_loaded` | `appopen_ad_loaded` | CoreOpenSpotBook.kt:76 |
| `appopen_ad_show_fail` | `appopen_ad_show_fail` | CoreOpenSpotBook.kt:183 |
| `appopen_ad_shown` | `appopen_ad_shown` | CoreOpenSpotBook.kt:202 |
| `AppOpen_Loaded` | `AppOpen_Loaded` | SpotBeaconActivity.kt:709 |
| `Back_Inter_Loaded` | `Back_Inter_Loaded` | TakeoverRear.kt:61 |
| `BackLoad_AdsOFF` | `BackLoad:AdsOFF` | TakeoverRear.kt:36 |
| `BackLoad_BackAdsOFF` | `BackLoad:BackAdsOFF` | TakeoverRear.kt:46 |
| `BackLoad_InterAdsDisabled` | `BackLoad:InterAdsDisabled` | TakeoverRear.kt:41 |
| `Banner_fail_Load` | `Banner_fail_Load` | RibbonSpots.kt:197 |
| `Banner_Load` | `Banner_Load` | RibbonSpots.kt:174 |
| `CarrierCodes_Dial` | `CarrierCodes_Dial` | FrameDialsActivity.kt:138 |
| `custom_after_fb_fail` | `custom_after_fb_fail` | TakeoverStandard.kt:549 |
| `default_home_already` | `default_home_already` | HomeStream.kt:206 |
| `default_home_declined` | `default_home_declined` | MainPartPhaseActivity.kt:150 |
| `default_home_skip` | `default_home_skip` | MainPartPhaseActivity.kt:97 |
| `default_home_view` | `default_home_view` | MainPartPhaseActivity.kt:44 |
| `facebook_banner_load` | `facebook_banner_load` | RibbonSpots.kt:314 |
| `facebook_inter_clicked` | `facebook_inter_clicked` | TakeoverStandard.kt:532 |
| `facebook_inter_dismiss` | `facebook_inter_dismiss` | TakeoverStandard.kt:516 |
| `facebook_inter_displayed` | `facebook_inter_displayed` | TakeoverStandard.kt:524 |
| `facebook_inter_impression` | `facebook_inter_impression` | TakeoverStandard.kt:536 |
| `facebook_inter_load_start` | `facebook_inter_load_start` | TakeoverStandard.kt:485 |
| `facebook_inter_loaded` | `facebook_inter_loaded` | TakeoverStandard.kt:496 |
| `facebook_inter_show` | `facebook_inter_show` | TakeoverStandard.kt:501 |
| `facebook_inter_show_exception` | `facebook_inter_show_exception` | TakeoverStandard.kt:503 |
| `fb_fail_after_google_fail` | `fb_fail_after_google_fail` | TakeoverStandard.kt:461 |
| `fb_load_fail` | `fb_load_fail` | TakeoverStandard.kt:317 |
| `fb_preload_fail_fallback` | `fb_preload_fail_fallback` | TakeoverStandard.kt:304 |
| `fb_preloaded_show` | `fb_preloaded_show` | TakeoverStandard.kt:435 |
| `fb_preloaded_show_exception` | `fb_preloaded_show_exception` | TakeoverStandard.kt:437 |
| `game_icon_click` | `game_icon_click` | RingHomeSpots.kt:104 |
| `google_back_inter_show_attempt` | `google_back_inter_show_attempt` | TakeoverRear.kt:155 |
| `google_banner` | `google_banner` | RibbonSpots.kt:209 |
| `google_fail_fallback_chain` | `google_fail_fallback_chain` | TakeoverStandard.kt:466 |
| `google_fail_open_custom` | `google_fail_open_custom` | TakeoverStandard.kt:470 |
| `google_fail_start` | `google_fail_start` | TakeoverStandard.kt:449 |
| `google_fail_try_facebook` | `google_fail_try_facebook` | TakeoverStandard.kt:455 |
| `google_inter_dismiss` | `google_inter_dismiss` | TakeoverStandard.kt:361 |
| `google_inter_exception` | `google_inter_exception` | TakeoverStandard.kt:379 |
| `google_inter_load_start` | `google_inter_load_start` | TakeoverStandard.kt:213 |
| `google_inter_loaded` | `google_inter_loaded` | TakeoverStandard.kt:227 |
| `google_inter_null` | `google_inter_null` | TakeoverStandard.kt:340 |
| `google_inter_ondemand_load_start` | `google_inter_ondemand_load_start` | TakeoverStandard.kt:393 |
| `google_inter_ondemand_loaded` | `google_inter_ondemand_loaded` | TakeoverStandard.kt:403 |
| `google_inter_show_attempt` | `google_inter_show_attempt` | TakeoverStandard.kt:344 |
| `home_chip_carrier_codes` | `home_chip_carrier_codes` | MainHubFragment.kt:194 |
| `home_chip_dual_sim` | `home_chip_dual_sim` | MainHubFragment.kt:198 |
| `home_chip_my_number` | `home_chip_my_number` | MainHubFragment.kt:190 |
| `inter_ad_flow` | `inter_ad_flow` | TakeoverStandard.kt:277 |
| `inter_counter_skip` | `inter_counter_skip` | TakeoverStandard.kt:265 |
| `inter_counter_triggered` | `inter_counter_triggered` | TakeoverStandard.kt:269 |
| `inter_link_first` | `inter_link_first` | TakeoverStandard.kt:272 |
| `inter_request_start` | `inter_request_start` | TakeoverStandard.kt:245 |
| `inter_type_custom` | `inter_type_custom` | TakeoverStandard.kt:325 |
| `inter_type_facebook` | `inter_type_facebook` | TakeoverStandard.kt:298 |
| `inter_type_google` | `inter_type_google` | TakeoverStandard.kt:285 |
| `Interstitial_Splash_Loaded` | `Interstitial_Splash_Loaded` | SpotBeaconActivity.kt:759 |
| `MyNumber_Copy` | `MyNumber_Copy` | MineNumPlate.kt:121 |
| `MyNumber_Manage_Click` | `MyNumber_Manage_Click` | MineNumPlate.kt:35 |
| `MyNumber_Show_Click` | `MyNumber_Show_Click` | MineNumPlate.kt:73 |
| `MyNumberPage_Copy` | `MyNumberPage_Copy` | MineNumActivity.kt:273 |
| `MyNumberPage_Show_Click` | `MyNumberPage_Show_Click` | MineNumActivity.kt:251 |
| `NativeAds_Fail` | `NativeAds_Fail` | EmbeddedTeaser.kt:151 |
| `NativeAds_load` | `NativeAds_load` | EmbeddedTeaser.kt:132 |
| `NativeAds_showBigNative_FB_Load` | `NativeAds_showBigNative_FB_Load` | EmbeddedTeaser.kt:419 |
| `NativeAds_showBigNative_Google` | `NativeAds_showBigNative_Google` | EmbeddedTeaser.kt:241 |
| `NativeAds_showMid_FB` | `NativeAds_showMid_FB` | EmbeddedTeaser.kt:632 |
| `NativeAds_showMidNative2_Google` | `NativeAds_showMidNative2_Google` | EmbeddedTeaser.kt:836 |
| `NativeAds_showMidNative_Google` | `NativeAds_showMidNative_Google` | EmbeddedTeaser.kt:550 |
| `NativeBanner_fail` | `NativeBanner_fail` | EmbeddedTeaserRibbon.kt:71 |
| `NativeBAnner_FB` | `NativeBAnner_FB` | EmbeddedTeaserRibbon.kt:271 |
| `NativeBanner_Load` | `NativeBanner_Load` | EmbeddedTeaserRibbon.kt:59 |
| `NativeBanner_Show_Google` | `NativeBanner_Show_Google` | EmbeddedTeaserRibbon.kt:194 |
| `onboarding_completed` | `onboarding_completed` | BriefingActivity.kt:167 |
| `Permission_POST_NOTIFICATIONS_Bypass` | `Permission_POST_NOTIFICATIONS_Bypass` | MemoOpeningActivity.kt:88 |
| `Permission_POST_NOTIFICATIONS_Show` | `Permission_POST_NOTIFICATIONS_Show` | MemoOpeningActivity.kt:85 |
| `Permission_SYSTEM_ALERT_WINDOW_Bypass` | `Permission_SYSTEM_ALERT_WINDOW_Bypass` | PolicyPortalActivity.kt:111, PolicyPortalActivity.kt:121 |
| `Permission_SYSTEM_ALERT_WINDOW_Show` | `Permission_SYSTEM_ALERT_WINDOW_Show` | PolicyPortalActivity.kt:129 |
| `PermissionSheet_AllDone` | `PermissionSheet_AllDone` | ConsentDrawerModal.kt:282 |
| `PermissionSheet_AllowAll` | `PermissionSheet_AllowAll` | ConsentDrawerModal.kt:265 |
| `PermissionSheet_Show` | `PermissionSheet_Show` | ConsentDrawerModal.kt:115 |
| `PermissionSheet_Skip` | `PermissionSheet_Skip` | ConsentDrawerModal.kt:288 |
| `recent_playstore_open` | `recent_playstore_open` | FreshSpotSentinel.kt |
| `Reminder_Access_Settings` | `Reminder_Access_Settings` | MemoConsent.kt:54 |
| `Reminder_Alert_Ad` | `Reminder_Alert_Ad` | MemoSirenActivity.kt:160 |
| `Reminder_Alert_Call` | `Reminder_Alert_Call` | MemoSirenActivity.kt:183 |
| `Reminder_Alert_Done` | `Reminder_Alert_Done` | MemoSirenActivity.kt:145 |
| `Reminder_Alert_Open_List_Click` | `Reminder_Alert_Open_List_Click` | MemoSirenActivity.kt:191 |
| `Reminder_Alert_Show` | `Reminder_Alert_Show` | MemoSirenActivity.kt:124 |
| `Reminder_Delete` | `Reminder_Delete` | MemoListActivity.kt:265 |
| `Reminder_Intro_Allow` | `Reminder_Intro_Allow` | MemoOpeningActivity.kt:81 |
| `Reminder_Intro_Show` | `Reminder_Intro_Show` | MemoOpeningActivity.kt:76 |
| `Reminder_Intro_Skip` | `Reminder_Intro_Skip` | MemoOpeningActivity.kt:52 |
| `Reminder_Prompt_Allow` | `Reminder_Prompt_Allow` | MemoConsent.kt:92 |
| `Reminder_Prompt_Show` | `Reminder_Prompt_Show` | MemoConsent.kt:87 |
| `reward_cooldown_free` | `reward_cooldown_free` | BonusTeaser.kt:230 |
| `reward_cooldown_wait` | `reward_cooldown_wait` | BonusTeaser.kt:233 |
| `reward_nofill` | `reward_nofill` | BonusTeaser.kt:92 |
| `reward_retry_exhausted` | `reward_retry_exhausted` | BonusTeaser.kt:173 |
| `search_invalid_number` | `search_invalid_number` | MainHubFragment.kt:507 |
| `search_limit_reached` | `search_limit_reached` | MainHubFragment.kt:431 |
| `ShareNumber_Copy` | `ShareNumber_Copy` | SpreadNumActivity.kt:91 |
| `ShareNumber_SaveQr` | `ShareNumber_SaveQr` | SpreadNumActivity.kt:141 |

## Launcher module events (via `HomeGateway.onEvent`)

| Event | Call sites |
|---|---|
| `default_home_card_tap` | HomeDeck.kt:322 |
| `default_home_granted_later` | HomeDeck.kt:352 |
| `uninstall_flow_self_hidden` | HomeDeck.kt:1020 |
