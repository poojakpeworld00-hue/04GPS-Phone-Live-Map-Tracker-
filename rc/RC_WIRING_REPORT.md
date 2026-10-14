# Remote Config — wiring report (GPS Phone Live Map Tracker clone)

**Import file for the new Firebase project:** `rc/remote_config_template_GPSPhoneLiveMapTracker.json`
(5 params: GET_DATA_LIST_1, DEBUG_GET_DATA_LIST_1, feature_config, lighthouse, lighthouse_disclosure;
per-param pretty copies: `rc/*.clone.json`). Built from the live reference template (v100) with:
screen names → clone class names, reference package → clone package in ad-unit paths, brand text updated,
marketing `launcher_ads.reminders` moved to `launcher_ads.onboarding.reminders` (where the code reads it).

**Fixed in code after the audit:** `HomeStageActivity` (no such class) → `HomeDeck` in the permission_engine
default and legacy map; banner debug log read the wrong pref key.

**Still needs you:** create the 7 real ad units for the new package (googleS_Inter, googleInter, googleBackInter,
googleAppopen, googleNative, googleBanner, googleRewarded), new PrivacyPolicy/TermLink URLs, the new app's
LightHouse key, and the real google-services.json.

---
# Remote Config wiring audit — GPS Phone Live Map Tracker clone

Project: `/Users/pooja/Documents/Pooja_Apps/10_OCT/Clone_GPS/04GPSPhoneLiveMapTracker`
Template audited: `live_rc.txt` (template v100, 5 params) — split into `scratchpad/rcsplit/*.json`.
Path prefixes below: `A:` = `app/src/main/java/com/find/phone/gps/live/location/tracker/`, `L:` = `launcher/src/main/java/com/livemap/homedeck/`.

> Line numbers are as of the audit. Other agents were stripping comments from Kotlin files during the
> audit (`HomeSeatSpots.kt`, `SpotProfileAbsorb.kt`, `HomeSpotsProfile.kt` and others shifted mid-run), so treat a line
> number as approximate. The quoted key strings are exact; grep for them.

Helper artifacts in the scratchpad: `rcsplit/*.json` (template split per param), `gdl_paths_full.txt` / `dbg_paths_full.txt`
(every nested key, marketing vs organic, with `ONLY_MKT` / `ONLY_ORG` / `DIFF` flags), `screen_resolve.txt` (screen-name
resolution), `key_refs.txt` / `linerefs.txt` (grep hits), plus the scripts `split.py`, `paths.py`, `resolve.py`, `adids.py`.

## How config flows (verified)

1. `SpotBeaconActivity.setResponceInPref` (A:monetize/render/SpotBeaconActivity.kt ~255-320) runs `fetchAndActivate` and then calls
   `SpotProfileAbsorb.pullBlob` (A:monetize/logic/SpotProfileAbsorb.kt:16-35). That reads `GET_DATA_LIST_1` (release) or
   `DEBUG_GET_DATA_LIST_1` (debug), falling back to the legacy `GET_DATA_LIST` / `DEBUG_GET_DATA_LIST`. The raw blob is saved as the
   `GET_DATA_RAW` pref.
2. `audienceRoot` picks `marketing` or `organic` from the `OnMaketing` pref. `ingest()` then copies an **explicit key list** into
   `SpotsLocker` (SharedPreferences `My_Bank_Balance_App`):
   - booleans: optBoolean, lines ~37-50
   - aliases: ~52-55
   - strings and nested JSON as text: ~57-84
   - placement keys: ~86-88
   - ints: ~90-100
   - `launcher_enabled`: ~102
   - `NativeTheme`: ~149
   - `custom_ads` → `CUSTOM_ADS`: ~106
   - `FbAppId` / `FbClientToken`: returned to the caller, ~115
3. After the install referrer settles `OnMaketing`, `funOnAdsLoad` re-ingests from `GET_DATA_RAW` (SpotBeaconActivity ~300-310).
   `InstantProfileSentinel` (A:monetize/logic/InstantProfileSentinel.kt) re-ingests on a real-time RC update. It only does this when
   the update touches the blob, `permission_engine` or `feature_config` (line ~40), and it also runs a backstop fetch every
   `Config_Sync_Hrs`, default 6h (line ~23, ~102-104).
4. Readers outside the prefs path:
   - `ConsentFeed` (permission_engine)
   - `MemoProfile` (feature_config)
   - `RingHomeGateway.configString` (launcher_config, ads_config, home_profile_*)
   - the launcher module, through the gateway
5. There are no in-app RC defaults. No `setDefaultsAsync` call and no RC defaults XML exist, so every value below falls back to its
   **code** default.

---

## 1. Top-level Remote Config parameters read by code

| # | RC parameter | Reader (file:line) | In-code default when absent | In live template? |
|---|---|---|---|---|
| 1 | `GET_DATA_LIST_1` (release builds) | A:monetize/logic/SpotProfileAbsorb.kt:16 (`blobKey`), :30 `getString(blobKey)`; ConsentFeed.kt:~60 via `pullBlob` | → legacy key, else cached prefs / pref defaults (all ads off) | **yes** |
| 2 | `DEBUG_GET_DATA_LIST_1` (debug builds) | same as #1 (`BuildConfig.DEBUG`) | same | **yes** |
| 3 | `GET_DATA_LIST` (legacy, release fallback) | SpotProfileAbsorb.kt:18 `legacyBlobKey`, :32 | "" | no (fallback only, OK) |
| 4 | `DEBUG_GET_DATA_LIST` (legacy, debug fallback) | SpotProfileAbsorb.kt:18, :32 | "" | no (fallback only, OK) |
| 5 | `permission_engine` (dedicated param, checked **before** the blob copy) | A:consent/ConsentFeed.kt:12 `RC_KEY`, :56 `rc.getString(RC_KEY)`; InstantProfileSentinel.kt:17 (update listener) | → `permission_engine` inside the blob audience (ConsentFeed.kt:63-64), else `DEFAULT_CONFIG` (ConsentFeed.kt:~17) | no (served from inside the blob, which is fine) |
| 6 | `feature_config` | A:memo/MemoProfile.kt:55 `RC_KEY`, :64 `getString(RC_KEY)`; InstantProfileSentinel.kt:40 | `MemoProfile.DEFAULT_CONFIG` (identical to live template content) | **yes** |
| 7 | `launcher_config` (top-level, used as the merge base) | A:homeshell/RingHomeGateway.kt:33, :41 `topLevel(LAUNCHER_CONFIG)`; L:contract/HomeBook.kt:54 | `{}`. The overlay comes from the blob copy stored in prefs (`launcher_config` key, RingHomeGateway.kt:44) | no (blob copy is used, OK) |
| 8 | `ads_config` | L:teaser/HomeSpotsProfile.kt:127 asks for it, but A:homeshell/RingHomeGateway.kt:29-31 **intercepts** it and synthesises `{"screenWiseAds":{"app_drawer":{"ad_row_position":<launcher_ads.app_drawer.bottom_native.position>}}}` | Never fetched from RC | no (correctly never read) |
| 9 | `home_profile_rules` | L:preset/MainPresetSettler.kt:9,13; L:preset/MainPresetEnforcer.kt:41 → RingHomeGateway.kt:34 `topLevel(key)` | Built-in `MainPresetLaws` table | no (optional override) |
| 10 | `home_profile_probes` | L:preset/MainPresetSettler.kt:10,21 | `BundlePresetScanner.DEFAULT_PROBES_JSON` | no (optional override) |
| 11 | `lighthouse` | **No reader.** The LightHouse SDK is configured from `BuildConfig.LH_API_KEY` / `LH_BASE_URL` (A:NavTracerCore.kt:~150-151, app/build.gradle.kts:16-17). The `io.lighthouse:light-house(-extended):0.7.7` AARs contain no `remoteconfig` reference. | — | yes → **UNUSED by the app** |
| 12 | `lighthouse_disclosure` | **No reader.** `LightHouse.ensureDataDisclosure()` (A:screens/boot/BootActivity.kt:~143) is SDK-internal, and the SDK has no Firebase RC reference. It may be consumed server-side by LightHouse (rc_sync); check with the backend owner. | — | yes → **UNUSED by the app** |

Template params never read: `lighthouse` and `lighthouse_disclosure`.
Code params absent from the template (all fine, they have fallbacks):
- `GET_DATA_LIST` / `DEBUG_GET_DATA_LIST`
- top-level `permission_engine`
- top-level `launcher_config`
- `home_profile_rules` / `home_profile_probes`

---

## 2. Nested keys per JSON parameter

Legend:
- **USED**: in template and read by code.
- **UNUSED**: in template, never read or never effective.
- **MISSING**: read by code, absent from template, so the code default applies.
- **MKT only / ORG only**: present in one audience branch only.

### 2.1 `GET_DATA_LIST_1` / `DEBUG_GET_DATA_LIST_1` — flat keys of the audience block

The two params share the same key set except where noted. The release blob carries the real ad units; the debug blob carries Google test units.

| Key | Ingest (SpotProfileAbsorb.kt) | Reader (file:line) | Status / notes |
|---|---|---|---|
| IsAdsON | bool | NavTracerCore.kt:~262, HomeSeatSpots, HomeSpotsProfile.kt:~185, RibbonSpots.kt:~55, CoreOpenSpotBook.kt:108, DisplayTeaserProfile.kt:133, BonusTeaser.kt:58 | USED (MKT true / ORG false) |
| IsAdType | str | CoreOpenSpotBook.kt:63,67,210; EmbeddedTeaser.kt:215; SpotBeaconActivity (splash) | USED |
| is_splash_inter_show | bool | SpotBeaconActivity.kt:~510 | USED |
| is_splash_ads | bool | SpotBeaconActivity.kt:~445 | USED |
| IsFail_FB | bool | RibbonSpots.kt:355, EmbeddedTeaser.kt:257,566, EmbeddedTeaserRibbon.kt:153, TakeoverRear/Standard, SpotBeaconActivity | USED (false, so the FB path is dormant) |
| IsCustomADS | bool | HomeSeatSpots.kt:104,116; BespokeSpotsBook.kt:52; BonusTeaser.kt:110; TakeoverRear/Standard; SpotUtils.kt | USED |
| isLoaderForFB | bool | A:monetize/render/fullscreen/WholeDisplayWheel.kt:22 (legacy fallback when `Inter_Loader_Show` is absent) | USED (only effective in ORG, which lacks Inter_Loader_Show) |
| IsBack | bool | TakeoverRear.kt:44,98 | USED |
| NativeBanner | bool | EmbeddedTeaserRibbon.kt:49,109 | USED |
| NativeAd | bool | EmbeddedTeaser.kt:99,184,504,792 | USED |
| BannerAds | bool | RibbonSpots.kt:55 | USED. The debug log at SpotProfileAbsorb.kt:122 reads `getBoolean("RibbonTeaser")`, a key renamed by mistake in the clone that should be `BannerAds`. Log only, but it always prints false. |
| InterAds | bool | TakeoverRear.kt:39,97; TakeoverStandard.kt:206,260 | USED |
| AppopenAds | bool | CoreOpenSpotBook.kt:59,112 | USED |
| is_rateus | bool | A:screens/dashboard/MainSliderOptions.kt:99 (default **true**) | USED |
| is_share | bool | MainSliderOptions.kt:100 (default true) | USED |
| is_preload_ads | bool | TakeoverStandard.kt:208,281,365,372 | USED |
| Inter_Loader_Show | bool | WholeDisplayWheel.kt:21 (absent → `isLoaderForFB`) | USED — **MKT only** (ORG falls back to isLoaderForFB=false) |
| Overlay_Permission_Show | bool | A:screens/policy/DriftToolbox.kt:19,~46 (default true) | USED |
| In_App_Update_Show | bool | MainCasingDirector.kt:79 | USED |
| In_App_Update_Force_Show | bool | MainCasingDirector.kt:81 | USED |
| In_App_Update_Link | str | SpotUtils.kt:91; BootActivity.kt:123 | USED |
| contacts_base_url | str | A:store/inquiry/InquiryEndpoint.kt:62; A:store/peoplesync/BuddySendEndpoint.kt:98 (fallback = compiled FALLBACK_ORIGIN) | USED |
| DirectLink | str | HomeSeatSpots.kt:84 (`resolve`), HomeSpotsProfile.kt:212 + DrawerAdType fallback, SpotUtils.kt, TakeoverStandard.kt, SpotBeaconActivity.kt:579,588 | USED |
| DirectLinkType | str | A:monetize/render/StraightHrefOpener.kt:15 `MODE_KEY` | USED ("webview" is valid) |
| MarketLink | str | SpotBeaconActivity.kt:413 only, and only when `isMarketingOn && !isSplitConfig` | **UNUSED**: the template is a split config, so this branch never runs |
| PrivacyPolicy | str | A:helpers/ActivityUtils.kt:112 | USED (value is the reference app's URL, see §5) |
| TermLink | str | ActivityUtils.kt:135 | USED (value is the reference app's URL, see §5) |
| Iscountry_Counter | bool | SpotBeaconActivity.kt:334 | USED |
| CountryList_Counter_NShow | str | SpotBeaconActivity.kt:335 (matched against country / region / **city**) | USED ("Indore" is a city, which is allowed) |
| googleS_Inter | str | SpotBeaconActivity.kt:621 | USED |
| googleInter | str | HomeSeatSpots.kt:~98,172; TakeoverStandard.kt:214,391; HomeSpotsProfile.kt:248 | USED |
| googleBackInter | str | TakeoverRear.kt:49 | USED |
| googleAppopen | str | CoreOpenSpotBook.kt:68; SpotBeaconActivity.kt:630; HomeSpotsProfile.kt:249 | USED |
| googleNative | str | DisplayTeaserProfile.kt:32,100; EmbeddedTeaserRibbon.kt:53; HomeSeatSpots.kt:75; PluggedConditionActivity | USED |
| googleBanner | str | DisplayTeaserProfile.kt:31; RibbonSpots.kt:141 | USED |
| googleRewarded | str | BonusTeaser.kt:63,164; HomeSeatSpots; HomeSpotsProfile.kt:251 | USED |
| faceB_InterAds | str | TakeoverRear.kt:213; TakeoverStandard; SpotBeaconActivity.kt:532,574,648 | USED (placeholder `IMG_16_9_APP_INSTALL#YOUR_PLACEMENT_ID`; dormant because IsFail_FB=false) |
| faceB_NativeAds | str | EmbeddedTeaser.kt:374,610 | USED (placeholder) |
| faceB_NativeBannerAds | str | EmbeddedTeaserRibbon.kt:253 | USED (placeholder) |
| faceB_BannerAds | str | RibbonSpots.kt:278 | USED (placeholder) |
| InterCounter | int | TakeoverStandard.kt:262; PluggedConditionActivity.kt:195 | USED |
| InterBackCounter | int | TakeoverRear.kt:100 | USED |
| NativeCounter | int | EmbeddedTeaser.kt:197 | USED |
| MidNativeCounter | int | EmbeddedTeaser.kt:516,804; EmbeddedTeaserRibbon.kt:120 | USED |
| BannerCounter | int | RibbonSpots.kt:61 | USED |
| AppopenCounter | int | **none** (only written at SpotBeaconActivity.kt:~411) | **UNUSED**: app-open has no counter gate |
| FbAppId | (returned) | SpotProfileAbsorb.kt:115 → FB SDK init in SpotBeaconActivity.ingestConfig | USED ("") |
| FbClientToken | (returned) | SpotProfileAbsorb.kt:116 | USED ("") |
| rewarded | str(JSON) | BonusProfile (§2.2) | USED |
| intro_display | str(JSON) | OpeningUnveilProfile (§2.3) | USED |
| NativeTheme | object | SpotProfileAbsorb.kt:149 → NativeAdTheme.kt (§2.4) | USED |
| permission_engine | (not ingested) | ConsentFeed.kt:63-64 reads it from the raw blob | USED (§2.5) |
| screen_wise_ad | bool | DisplayTeaserProfile.kt:33,101 | USED (false in both) |
| screen_wise_default | bool | DisplayTeaserProfile.kt:34 | USED |
| ScreenAds | str(JSON) | DisplayTeaserProfile.kt:36,110 (§2.6, §3) | USED |
| launcher_ads | str(JSON) | HomeSpotsProfile.kt:23 `CONFIG_KEY` (§2.7) | USED |
| custom_ads | array → `CUSTOM_ADS` | BespokeSpotsBook.kt:60 | USED ([] in both) |
| launcher_config | str(JSON) | RingHomeGateway.kt:44 overlay → HomeBootstrap.parse (§2.8) | USED |
| link_first_then | str (placement global) | HomeSeatSpots.kt:113 | USED — **MKT only** |
| link_first_show_all | str | HomeSeatSpots.kt:118 | USED — **MKT only** |
| link_open_in | str | HomeSeatSpots.kt:106,117; RingHomeSpots.kt:100 | USED |
| RewardedAds | str | HomeSeatSpots.kt:96 (only `"false"` disables) | USED |
| inter_fallback | str | HomeSeatSpots.kt:130 | USED — **MKT only** (release "reward", debug "full_native") |
| launcher_enabled | str | A:homeshell/HomeFlip.kt:14 (`!= "false"`, absent → on) | USED |
| onboarding_home | str | A:induction/HomeStream.kt:85 (`== "app"`) | USED |
| recent_playstore | bool | A:monetize/render/FreshSpotSentinel.kt:57 | USED |
| recent_playstore_window_sec | int | FreshSpotSentinel.kt:58 | USED (MKT 6000 / ORG 180) |
| placements | str(JSON) | HomeSeatSpots.kt:55 `PLACEMENTS_KEY`, `own()` (§2.9) | USED (partly ineffective, see §2.9) |
| premium | str(JSON) | PlusProfile (§2.10) | USED |

**Flat keys the code reads that are MISSING from the template.** In each case the code default applies.

| Key | Reader | Default when absent | Impact |
|---|---|---|---|
| `contacts_upload` (`{"enabled":bool}`) | SpotProfileAbsorb.kt:71 → A:store/peoplesync/BuddyRelay.kt:55 `optBoolean("enabled", false)` | **false** | The one-time phonebook upload is **OFF**. This contradicts `lighthouse_disclosure`, which says contacts are uploaded. Add the key if the upload is intended. |
| `Config_Sync_Hrs` | InstantProfileSentinel.kt:21,102 | 6 h | Backstop fetch every 6 h |
| `Inter_Loader_Ms` | WholeDisplayWheel.kt:24 | -1 → clamped to 0 | No loader beat before a preloaded interstitial |
| `googleFullNative` | HomeSeatSpots.kt:75 | → `googleNative` | none |
| `ad_flow` / `ad_flow_show_all` (global) | HomeSeatSpots.kt:133,136 | none / false | Placements keep their default chains |
| `Iscountry_Marketing_Counter`, `CountryList_Marketing_Counter_NShow` | ingested (SpotProfileAbsorb ~40,~59) | — | Ingested but **never read anywhere**. Dead keys; SpotBeaconActivity.kt:334 uses the un-prefixed pair for both audiences. |
| `Perm_Sheet_Show`, `Perm_Sheet_Mode`, `Perm_Sheet_Interval_Days` | ingested | — | Ingested, **never read**. Superseded by `intro_display.permission_sheet`. |
| `MarketInterCounter`, `MarketBackCounter`, `MarketNativeCounter`, `MarketBannerCounter`, `MarketAppopenCounter` | SpotBeaconActivity.kt:~406-411 | 0 | Only used for a legacy flat (unsplit) config; no effect here |
| `NativeBgColor`, `NativebtnColor`, `NativebtnColorEnd`, `NativetxtColor`, `NativebtntxtColor` (flat) | ingested; readers in EmbeddedTeaser / BespokeSpotsBook / SpotButtonTheme / EmbeddedSpotGuise | — | Overwritten from `NativeTheme` by NativeAdTheme.kt:41-45. Fine. |
| `BannerAdPresenter`, `NativeBannerPresenter` (aliases) | SpotProfileAbsorb.kt:52 | — | Not needed |
| `<placement>_*` flat keys (`drawer_ads_on`, `leftPanel_googleInter`, …) | SpotProfileAbsorb.kt:86-88 / HomeSeatSpots.isPlacementKey | — | Legacy flat form; the `placements` object is used instead |

### 2.2 `rewarded` (BonusProfile, A:monetize/logic/BonusProfile.kt)

| Key | Reader | Status |
|---|---|---|
| nofill_mode | :26 (`"wait"` = WAIT, anything else = FREE) | USED |
| nofill_cooldown_sec | :24 | USED |
| nofill_backoff | :28 | USED |
| nofill_cooldown_max_sec | :29 | USED |
| max_retries | :30 | USED |

Nothing missing. Both branches carry the same values: release `wait/900/false/900/2`, debug `free/120/true/900/2`.

### 2.3 `intro_display` (A:screens/opening/OpeningUnveilProfile.kt)

| Key | Reader | Status |
|---|---|---|
| language.{enabled,prompt_frequency,prompt_interval} | :41 → load :68,70,72 | USED |
| terms.{…} | :44 | USED |
| onboarding.{…} | :47 | USED |
| permission_sheet.{…} | :50 | USED (MKT always/0, ORG every_days/3) |
| **reminders_intro** | :38 | **MISSING**. Default enabled=true, frequency=once. It is still gated by `feature_config.is_intro_screen` (OpeningUnveilCharter.kt:~61-64). |

### 2.4 `NativeTheme` (SpotProfileAbsorb.kt:149 → A:helpers/NativeAdTheme.kt)

| Key | Reader | Status |
|---|---|---|
| NativeLight / NativeDark | NativeAdTheme.kt:20-29 | USED |
| .btnColor | :41 | USED |
| .btnColorEnd | :42 | USED |
| .btnText | :43 | USED |
| .bgColor | :44 | USED |
| .textColor | :45 | USED |

### 2.5 `permission_engine` (in the blob; A:consent/ConsentFeed.kt → FbConsentReader.kt)

| Key | Reader (FbConsentReader.kt) | Status |
|---|---|---|
| notification (rule key) | ConsentDriver / SpotBeaconActivity.kt:485 `prime("notification")` | USED |
| .enabled | :31 | USED |
| .activities | :23 → DisplayMatcher.rulesFor / matches | USED (see §3) |
| .delay | :33 | USED |
| .priority | :34 | USED |
| .show_once / .showOnce | :35 | **MISSING** (default false) |
| phone_state (rule) | ConsentFeed doc / ConsentDriver | **MISSING**. phone_state is never asked; this matches the ConsentFeed design. |

Branch difference in `notification.activities`:
- MKT: `[HomeRoleStepActivity, AppCoreActivity, LauncherPanel, GreetingStepActivity]`
- ORG: `[StartupActivity, AppCoreActivity, LauncherPanel, GreetingStepActivity]`

So ORG asks for notification permission on the splash (BootActivity) but not on the default-home step. MKT does the reverse.

Code default (`ConsentFeed.DEFAULT_CONFIG`, ConsentFeed.kt:~17) lists `HomeStageActivity`, which **does not exist**. The launcher home is now `HomeDeck`, so before the first fetch the default never targets the launcher home.

### 2.6 `ScreenAds` (A:monetize/logic/DisplayTeaserProfile.kt)

| Key | Reader | Status |
|---|---|---|
| `<screen>` / `default` | :41-45 via `screenEntry` → DisplayMatcher.keyFor | USED |
| .show | :46 (honoured even when screen_wise_ad=false), :68 | USED |
| .banner | :63 (only when screen_wise_ad=true) | USED. **Dormant**: `screen_wise_ad=false` in both branches, so the per-screen ids are ignored and the global `googleBanner` / `googleNative` are used. |
| .bannerType | :70 | USED (screen_wise only) |
| .native | :64, :112 | USED (screen_wise only, dormant) |
| .nativeType | :48 (global mode too), :72; consumed by PinpointingActivity.kt:~193 | USED |

Nothing missing. Screens that have no entry fall back to `default`; see §3.

### 2.7 `launcher_ads` (A:monetize/logic/HomeSpotsProfile.kt)

`config()` (~980) deep-merges the optional `defaultHome` / `notDefaultHome` variants. Both variants are **MISSING**, so the base block is used.

| Template key | Reader | Status |
|---|---|---|
| app_click.{enabled,counter,sequence,show_all,directlink_urls,on_demand,loader_timeout_ms} | :48 surfaceBlock; decodeFlow :313-347; :73 urls; :296 timeout | USED |
| app_close.{enabled,counter,sequence,show_all,directlink_urls} | :49 | USED |
| home_back.{enabled,counter,sequence,show_all,always_start_first,on_demand} | :54; :317 | USED |
| recent_click.{…,return_window_sec} | :55; :64 | USED |
| home_press.{enabled,counter,sequence,show_all} | :56 (HomePlane.HOME_PRESS) | USED — **MKT only**. In ORG the block is absent, so the surface is off. |
| **home_open.{enabled,counter,sequence,show_all,directlink_urls}** | — no `HomePlane` and no reader (`grep home_open` = 0 hits) | **UNUSED** — MKT only |
| left_panel.enabled | :119 panelEnabled → RingHomeGateway `panels.apps` | USED |
| left_panel.open_ad.{…} | :50 | USED |
| left_panel.native_mid.{enabled,ad_type,native_type,banner_type} | :142 → slot :484-497 | USED |
| left_panel.native_bottom.{…} | :138 | USED |
| right_panel.enabled / .open_ad / .native | :119, :51, :146 | USED |
| app_drawer.bottom_native.{enabled,ad_type,native_type,banner_type,ad_unit_id,position} | :154 appDrawerSlot; `position` → RingHomeGateway.kt:30 `ad_row_position` | USED — **ORG only**. In MKT the slot is off and the row is 0. |
| default_home_screen.{enabled,skip_if_default,skip_rest_on_grant} | :665-669 | USED |
| onboarding.order | :626 (unknown names dropped; `reminders` auto-inserted after `welcome`) | USED |
| onboarding.{set_default,language,welcome,intro}.{inter_enabled,ads_counter,skip_enabled,back_action} | :599, :607, :591, :592 | USED |
| onboarding.*.slot.{enabled,ad_type,native_type,banner_type,ad_unit_id} | :581 → :484-497 (`ad_unit_id` is honoured for banner only) | USED |
| onboarding.set_default.{auto_next,auto_next_delay_ms} | :650-651 | USED |
| onboarding.intro.* | | USED — **MKT only**. ORG lists `intro` in `order` but has no block, so the defaults apply: inter_enabled=**true**, mid2, skip on. Harmless while ORG IsAdsON=false and placements.onboarding.ads_on=false. |
| onboarding.reminders.{inter_enabled,slot.*} | :656 (OnboardScreen.REMINDERS) | USED — **ORG only** |
| **reminders.{inter_enabled,slot.*}** (directly under launcher_ads) | — code reads `onboarding.reminders`, not `launcher_ads.reminders` | **UNUSED, misplaced** — MKT only. MKT then gets the defaults (inter off, mid native), which happen to match the intent. |
| system_ads.{charge,discharge}.{enabled,native,close_ad,min_gap_sec} | :196 → :203-206; PluggedConditionActivity / JuiceHappeningSentinel | USED (`native:"MediumNative"` is not a recognised token and falls to the mid native, PluggedConditionActivity.kt:~183-187) |
| package_result.{enabled,min_gap_ms,body_native} | :167-171 | USED |

Keys the code reads that are **MISSING** from launcher_ads:

| Key | Default when absent |
|---|---|
| `home_click` / `drawer_click` (:52-53) | Fall back to `app_click` (clickSurface) |
| `home_hint.{enabled,swipeHints,show_mode,interval,auto_hide_sec}` (:531-549) | DEFAULT_HINT |
| `recent_ad.*` (:192) | Off. The reader `recentAdSettings()` is **never called**, so the key is dead even if added. |
| `app_drawer.promo.{enabled,items[]}` (:221-237) | Empty. The reader `drawerPromoItems()` is **never called**, so the key is dead. |
| `defaultHome` / `notDefaultHome` variants | Base block used |
| Per-flow aliases: `ads`, `ad_counter`, `show_all_ads`, `restart_sequence`, `fallback_enabled`, `on_demand_timeout_ms`, per-format `{enabled,ad_unit_id,url,open_type,urls}` | Defaults; fine |

### 2.8 `launcher_config` (blob copy, merged over the top-level param by RingHomeGateway → L:setup/HomeBootstrap.kt)

| Key | Reader | Status |
|---|---|---|
| os_style | HomeBootstrap.kt:140 | USED |
| search_widget | :141 | USED |
| default_launcher_prompt | :145 | USED |
| guide.enabled / guide.<step> (swipe_right_contacts, swipe_left_apps, swipe_up_drawer) | :123, :147 | USED |
| drawerAppAdEnabled | :152 (forced false when IsAdsON=false, RingHomeGateway.kt:84) | USED |
| drawerAppAdEvery | :153 | USED |
| drawerAppAds[].{logo,landing_url,label} (30 tiles) | :154, :163 | USED |
| dock_host_app | A:homeshell/RingHomeGateway.kt:89 (default true) | USED |
| unlock_ads.{enabled,start_after_hours,gap_minutes,max_per_day,format,countries} | :150 → A:homeshell/UnlatchSpotSentinel.kt:83-112 | USED (`countries` is `{}`) |
| should_show_time_widget / should_show_search_widget | :143-144 | **MISSING** (default true; present in launcher/remote-config/launcher_config.json) |
| panels.{apps,host} / gestures.* | :116, :127 | Not needed. RingHomeGateway.enactLauncherAds (~50-81) always overwrites them from `launcher_ads`. |

### 2.9 `placements` (A:monetize/logic/HomeSeatSpots.kt, `own()` / `placementEnabled` :77-79)

| Key | Reader | Status |
|---|---|---|
| onboarding.ads_on | HomeSpotsProfile `onboarding_<screen>` → parent `onboarding` | USED |
| unlock.ads_on | UnlatchSpotSentinel `placementEnabled("unlock")` | USED |
| install.{ads_on,ad_flow} / uninstall.{ads_on,ad_flow} | ParcelVerdictActivity.kt:~312-316 (`hasOwnFlow` is true because ad_flow is set) | USED |
| **splash.ads_on, back.ads_on, appOpen.ads_on, charge.ads_on, discharge.ads_on** | `placementEnabled` is consulted **only inside** `if (hasOwnFlow(...))` (SpotBeaconActivity.kt:552, TakeoverRear.kt:~125, NavTracerCore.kt:~261, PluggedConditionActivity.kt:~196). `hasOwnFlow` needs a per-placement `ad_flow` or `link_first_then`, which these entries do not have. | **UNUSED / ineffective**. For example, ORG `back.ads_on=false` does **not** stop the back interstitial. Those ads are governed by IsBack / InterAds / AppopenAds / system_ads instead. |

Missing: there is no `placements.app` entry for the `"app"` placement used by TakeoverStandard.kt:40; the global keys apply.
Note: `"app"` is not in the `PLACEMENTS` list (HomeSeatSpots.kt:19-20), so the flat `app_*` keys would not be ingested either.

### 2.10 `premium` (A:store/plus/PlusProfile.kt)

| Key | Reader | Status |
|---|---|---|
| enabled | :61 | USED |
| show_after_intro | :62 | USED |
| close_delay_sec | :63 | USED (MKT 10 / ORG 5) |
| free_search_limit | :65 | USED (MKT 1 / ORG 3) |
| rewarded_extra_searches | :66 | USED |
| **products[]** `{id,type,period,tag,default,trial_days}` | :45-56 | **MISSING in release** GET_DATA_LIST_1 (present in DEBUG). Falls back to `DEFAULT_PLANS`: gps_premium_weekly (3-day trial) / monthly / lifetime. The ids are the same, so the impact is low. `products[].price` (debug) is never read. |
| close_button_opacity | :67 | **MISSING** (default 1.0) |

### 2.11 `feature_config` (A:memo/MemoProfile.kt, Gson)

All 17 keys are mapped (MemoProfile.kt:19-46) and consumed:

| Key | Consumer |
|---|---|
| feature_title / desc / action | MemoConsent.kt:~121-123 |
| action_delay_options | MemoSirenActivity.kt:215 |
| is_default_config / default_config_list | MemoShelf.kt:74,79 |
| is_feature_prompt | MemoConsent.kt:115 |
| is_enhanced_mode | MemoLaborer.kt:71 |
| is_intro_screen | OpeningUnveilCharter.kt:61, HomeStream.kt:359 |
| prompt_frequency / prompt_interval | CuePortal.kt:42-44 |
| is_country_filter / excluded_countries | MemoProfile.isCountryAllowed |
| is_settings_row / settings_row_excluded_countries | MemoProfile.isSettingsRowAllowed → MainSliderOptions.kt:113 |
| intro_title / intro_desc | MemoOpeningActivity.kt:59-60 |

Every key is **USED**, nothing is missing, and both branches carry the same key set. The only value that differs is `is_intro_screen`: MKT true, ORG false.
`default_config_list[]` item fields (`title`, `note`, `daysAhead`) are mapped, but the list is empty.

### 2.12 `lighthouse` / `lighthouse_disclosure`

Every nested key is **UNUSED by the app**:
- `lighthouse`: api_key, base_url, bot_signature.{enabled, signatures[].utm_campaign/gclid, ip.{enabled,endpoint,field,match}, gclid_min_length}
- `lighthouse_disclosure`: show, title, intro, collectHeading, bullets, footer, links, ctaText, checkbox.*, colors.primary, organic.show, marketing.show

The SDK takes its credentials from BuildConfig.

### 2.13 Launcher repo templates (`launcher/remote-config/`)

- `ads_config_launcher_entries.json` (`screenWiseAds.apps_panel / suggested_recent_panel / app_drawer`): **not consumed** by this app. RingHomeGateway intercepts `ads_config` and only synthesises `app_drawer.ad_row_position`. Panel slots come from `launcher_ads.left_panel / right_panel / app_drawer` and `ScreenAds.LauncherApps*`. The README's instruction to merge the file into `ads_config` does not apply to this host.
- `launcher_config.json`: its keys are read through HomeBootstrap. Its `panels` / `gestures` are overridden by `launcher_ads`, as described in §2.8.

---

## 3. Screen-name keyed config → Activity resolution

Matching uses the Activity simple name, case-insensitively, either directly or through `DisplayMatcher.LEGACY_NAMES` (A:consent/DisplayMatcher.kt:4-~130, `matches` :139, `keyFor` :144). Activities were taken from both AndroidManifests (34 entries).

### Live template (both blobs, both branches)

| Context | Name in template | Resolves to | OK? |
|---|---|---|---|
| ScreenAds | default | fallback entry | OK |
| ScreenAds | LangChooserActivity | TongueSelectorActivity | OK |
| ScreenAds | AppCoreActivity | CoreHubActivity (also MainHubFragment `HOME_BANNER_KEY="CoreHubActivity"`) | OK |
| ScreenAds | LauncherAppsPanel / LauncherAppsPanelMid / LauncherAppDrawer | synthetic slot keys, A:homeshell/RingHomeSpots.kt:116-118 | OK |
| ScreenAds | TermsGateActivity | PolicyPortalActivity | OK |
| ScreenAds | PrimerActivity | BriefingActivity | OK |
| ScreenAds | NumberDetailActivity | NumProfile2Activity | OK |
| ScreenAds | LocatingActivity | PinpointingActivity (`ADS_SCREEN`) | OK |
| ScreenAds | ContactPickerActivity | BuddyChooserActivity | OK |
| ScreenAds | PremiumActivity | PlusActivity | OK |
| ScreenAds | HistoryActivity | LiveHistoryActivity | OK |
| ScreenAds | GreetingStepActivity | HelloPhaseActivity | OK |
| ScreenAds | HomeRoleStepActivity | MainPartPhaseActivity | OK |
| permission_engine | HomeRoleStepActivity (MKT) | MainPartPhaseActivity | OK |
| permission_engine | StartupActivity (ORG) | BootActivity | OK |
| permission_engine | AppCoreActivity | CoreHubActivity | OK |
| permission_engine | LauncherPanel | HomeDeck (a launcher Activity in the manifest) | OK |
| permission_engine | GreetingStepActivity | HelloPhaseActivity | OK |

**Every screen name in the live template resolves. None resolves to nothing.**

### Repo `rc/*.json` (already renamed)

All 9 files parse. They resolve **directly**: BriefingActivity, BuddyChooserActivity, CoreHubActivity, HelloPhaseActivity, LiveHistoryActivity, MainPartPhaseActivity, NumProfile2Activity, PinpointingActivity, PlusActivity, PolicyPortalActivity, TongueSelectorActivity, and permission_engine BootActivity / CoreHubActivity / HelloPhaseActivity / HomeDeck / MainPartPhaseActivity. The synthetic `LauncherApps*` keys and `default` are also fine. **All OK.**

### Names that resolve to nothing

- `HomeStageActivity` in `ConsentFeed.DEFAULT_CONFIG` (code default, not template): no such class.
- `launcher/remote-config/ads_config_launcher_entries.json` keys `apps_panel`, `suggested_recent_panel`, `app_drawer`: not Activities and never read by this host (§2.13).
- 26 `LEGACY_NAMES` **targets** are not Activities in this project: BeamToolActivity, BlockVaultActivity, CallReportActivity, CasingDeckActivity (abstract base), ChargeToolActivity, CountryDeckActivity, FsiPortalActivity, GadgetsActivity, HomeBaseDeck (base class), HomeStageActivity, IncomingRingActivity, LapTimerActivity, LumenToolActivity, NeedleToolActivity, NumPadActivity, PaceToolActivity, PlumbToolActivity, PrefsHubActivity, SandTimerActivity, SearchBriefActivity, SearchLogActivity, SimHubActivity, SoundToolActivity, StageActivity (base), StageSettingsActivity, VeiledAppsActivity. These are stale entries and harmless, with one exception: **`"MainActivity"` and `"HomeDeckActivity"` → `HomeStageActivity`** (DisplayMatcher.kt:44,64) no longer reach the launcher home, whose class is now `HomeDeck`. A config written with `MainActivity` now matches only CoreHubActivity. The live template uses `LauncherPanel`, which maps correctly.

### Screens that call `showAd` without their own ScreenAds entry

These StageActivity subclasses (StageActivity.kt:94) fall back to `ScreenAds.default`:
- BootActivity
- ChartWholeActivity
- DoubleChipOverviewActivity
- FrameDialsActivity
- MemoListActivity
- MemoOpeningActivity
- MineNumActivity
- ParcelVerdictActivity
- SpreadNumActivity

In ORG, `default.show=false` hides the banner on all of them. This may be intended; flag it if not.

---

## 4. Type mismatches and suspicious keys

**Types.**
- A scan of every ingested key found no boolean or int carried as a string in GET_DATA_LIST_1, DEBUG_GET_DATA_LIST_1 or feature_config. In any case, `optBoolean` / `optInt` coerce `"true"` and `"12"`.
- `link_first_show_all`, `RewardedAds` and `launcher_enabled` are JSON booleans that the code stores via `optString`, giving `"true"` / `"false"`. They are compared as text, which works.
- The only string-typed number is `lighthouse.bot_signature.signatures[0].gclid = "123456789"`, which is unused.

**SharedPreferences type safety.** No key is written with one type and read with another. All ints use putInt/getInt, and placement keys always go through putString.

**Suspicious keys and values:**
- `OnMaketing` (sic): an internal pref key, not an RC key. It is spelled consistently in SpotBeaconActivity, ConsentFeed, MemoProfile, OpeningUnveilProfile and SpotProfileAbsorb (`enactNativeTheme`), so it works. Do not "fix" one site alone.
- `getBoolean("RibbonTeaser")` in the SpotProfileAbsorb debug log (:122): a casualty of the clone rename that should be `"BannerAds"`. Log only.
- `launcher_ads.reminders` (MKT) is misplaced; it should be `launcher_ads.onboarding.reminders`.
- `launcher_ads.home_open` (MKT) has no reader.
- `placements.{splash,back,appOpen,charge,discharge}.ads_on` are ineffective (§2.9).
- `system_ads.*.native: "MediumNative"` is not a known token and falls to mid native. Known tokens: big, mid2, native_banner; anything else gives mid.
- `CountryList_Counter_NShow: "Indore"` is a city, not a country. It is valid because the matcher compares city too.
- `faceB_*` values are the Facebook placeholder `IMG_16_9_APP_INSTALL#YOUR_PLACEMENT_ID`, and `FbAppId` / `FbClientToken` are `""`.
- `inter_fallback` differs between builds: release "reward", debug "full_native".
- `Iscountry_Counter` and `isLoaderForFB` have inconsistent casing, but they match the code.

---

## 5. Values that embed the reference app package `com.find.my.phone.gps.location.tracker`

The clone's package is `com.find.phone.gps.live.location.tracker`. Everything below needs new values.
GET_DATA_LIST_1 (release) carries these in **both** marketing and organic. DEBUG_GET_DATA_LIST_1 uses Google test units (`ca-app-pub-3940256099942544/...`), which are fine for debug.

**Ad units** (Ad Manager network `/23343433547/`):

| Key | Value |
|---|---|
| googleS_Inter | `/23343433547/com.find.my.phone.gps.location.tracker_googleS_Inter` |
| googleInter | `/23343433547/com.find.my.phone.gps.location.tracker_googleInter` |
| googleBackInter | `/23343433547/com.find.my.phone.gps.location.tracker_googleBackInter` |
| googleAppopen | `/23343433547/com.find.my.phone.gps.location.tracker_googleAppopen` |
| googleNative | `/23343433547/com.find.my.phone.gps.location.tracker_googleNative` |
| googleBanner | `/23343433547/com.find.my.phone.gps.location.tracker_googleBanner` |
| googleRewarded | `/23343433547/com.find.my.phone.gps.location.tracker_googleRewarded` |
| ScreenAds.*.banner (11 entries per branch) | `…tracker_googleBanner`. Entries: default, LangChooser, AppCore, TermsGate, Primer, NumberDetail, ContactPicker, History, plus the native-only ones. |
| ScreenAds.*.native (14 entries per branch) | `…tracker_googleNative` |

The ScreenAds ids are dormant while `screen_wise_ad=false`, but they should still be replaced.

That makes 7 distinct unit names (googleS_Inter, googleInter, googleBackInter, googleAppopen, googleNative, googleBanner, googleRewarded) that need new units for the clone package.

**Non-ad URLs that also carry the reference package:**
- `PrivacyPolicy` = `https://privacypolicyportal.com/policy/com.find.my.phone.gps.location.tracker` (release, both branches)
- `TermLink` = `https://privacypolicyportal.com/terms/com.find.my.phone.gps.location.tracker`
- `lighthouse_disclosure.links[0].url` / `links[1].url`: the same two URLs. Unused by the app, but they are the text users see if LightHouse serves it.
- The debug blob uses `sites.google.com/view/gps-phone-location-tracker/...` instead.

---

## 6. Summary

Counts cover GET_DATA_LIST_1 at the level of the tables above: flat keys plus the nested keys of each JSON sub-object. Debug is equivalent.

| Bucket | Count | Items |
|---|---|---|
| Top-level params read by code | 10 | 3 present in the template: GET_DATA_LIST_1, DEBUG_GET_DATA_LIST_1, feature_config. 7 absent but with fallbacks. |
| Top-level params in the template, unread | 2 | lighthouse, lighthouse_disclosure |
| Blob flat keys: USED | 67 | Out of 69 flat keys (union of both branches). Every flat key is listed in §2.1. |
| Blob flat keys: UNUSED | 2 | MarketLink (split config), AppopenCounter (no reader) |
| Nested: UNUSED | 4 | `launcher_ads.home_open` (no reader), `launcher_ads.reminders` (misplaced), `placements.{splash,back,appOpen,charge,discharge}.ads_on` (ineffective, counted as 1 group), all lighthouse / lighthouse_disclosure sub-keys (counted as 1 group) |
| MISSING with **user-visible effect** | 4 | `contacts_upload` (contact upload OFF); `premium.products` (release falls to code DEFAULT_PLANS); `intro_display.reminders_intro` (default once); organic `launcher_ads.onboarding.intro` (defaults inter_enabled=true, muted by ORG IsAdsON=false) |
| MISSING, benign defaults | many | Config_Sync_Hrs, Inter_Loader_Ms, googleFullNative, ad_flow*, show_once, close_button_opacity, should_show_time/search_widget, home_click/drawer_click, home_hint, defaultHome/notDefaultHome, MKT app_drawer.bottom_native |
| Dead code paths (keys would be ignored even if added) | | `recent_ad`, `app_drawer.promo` (readers never called); `Iscountry_Marketing_Counter`, `CountryList_Marketing_Counter_NShow`, `Perm_Sheet_*` (ingested, never read) |
| Branch-asymmetric keys (release) | | **MKT only**: Inter_Loader_Show, link_first_then, link_first_show_all, inter_fallback, launcher_ads.home_press, home_open, onboarding.intro, launcher_ads.reminders. **ORG only**: launcher_ads.app_drawer.bottom_native, onboarding.reminders. permission_engine activities also differ (ORG StartupActivity vs MKT HomeRoleStepActivity). |

**Not wired or not effective, needing action:**
1. `lighthouse` and `lighthouse_disclosure` are never read by the app. The SDK uses BuildConfig credentials, and `lighthouse_disclosure` still names the reference package and an upload behaviour the config disables.
2. `contacts_upload` is absent, so BuddyRelay's contact upload is OFF.
3. `placements.{splash,back,appOpen,charge,discharge}.ads_on` have no effect without a per-placement `ad_flow` / `link_first_then`.
4. `launcher_ads.home_open` has no reader. `launcher_ads.reminders` sits at the wrong level and should be `onboarding.reminders`.
5. `MarketLink` and `AppopenCounter` are never effective.
6. `ScreenAds` per-screen unit ids are dormant (`screen_wise_ad=false`).
7. `ads_config_launcher_entries.json` (screenWiseAds) is not consumed by this host.
8. Code-side issues:
   - `ConsentFeed.DEFAULT_CONFIG` and `LEGACY_NAMES` `MainActivity` / `HomeDeckActivity` point at the non-existent `HomeStageActivity`; it should be `HomeDeck`.
   - The debug log reads `"RibbonTeaser"` instead of `"BannerAds"`.
   - `Iscountry_Marketing_Counter`, `CountryList_Marketing_Counter_NShow` and `Perm_Sheet_*` are ingested but never read.
   - `recentAdSettings()` and `drawerPromoItems()` are never called.
9. Seven ad unit names plus the PrivacyPolicy / TermLink URLs in release `GET_DATA_LIST_1` embed `com.find.my.phone.gps.location.tracker` and must be re-created for `com.find.phone.gps.live.location.tracker`.
10. Every screen name in the live template and in the repo `rc/*.json` resolves to an existing Activity, either directly or through LEGACY_NAMES. No dangling screen names were found.
