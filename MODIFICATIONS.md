# Carrier IMS v3.9.1-ar1 - Arabic mod

Private Arabic modification of [Carrier IMS for Pixel (TurboIMS)](https://github.com/ryfineZ/carrier-ims-for-pixel) v3.9.1 (commit 4312b5a9, Apache-2.0). Built for Ahmed Qandil.

## What changed

### Arabic
- Completed and rewrote the Arabic translation: `values-ar` now covers all 180 user-facing strings (upstream had 116 partial machine translations).
- Translated the user-facing hardcoded Chinese text: the full 8-step network diagnostics report in `MainViewModel` (~48 strings), the network-exit risk assessment text, and 2 strings in `MainActivity`. Code comments and log tags were left as-is.
- Fixed the locale trap: `app/build.gradle.kts` `localeFilters` listed only `en` and `zh-rCN`, which silently strips Arabic from the APK. Now `en` + `ar`.
- Removed the other 16 machine-generated locales (de/es/fr/in/it/ja/ms/pt-rBR/pt-rPT/ru/th/tr/vi/zh-rCN/zh-rHK/zh-rTW). Only English (default) + Arabic remain.
- TalkBack: moved the logcat screen's hardcoded English icon descriptions (Back / Clear all / Export / Filter / Scroll Latest) into string resources with Arabic translations.

### Ads, payments, tracking - full removal
Every ad-related code path was removed:
- Remote ad service: `AD_API_BASE_URL` (default `https://leads.3jiezhiwai.com`) and both ad-slot endpoints (`/api/sources/carrier-ims/ad-slots`, `/api/project/public-ad-slots?project_id=carrier-ims`).
- Home-screen ad popup dialog and the cooperation ad cards, including the remote ad image loader.
- Ad state tracking: the private `ad_state` SharedPreferences (per-ad shown/dismissed timestamps, interval re-show logic).
- Pay-to-remove-ads: the entire DodoPay integration - `DODOPAY_SUPPORT_URL_TEMPLATE` / `DODOPAY_SUPPORT_FEED_URL` BuildConfig fields, payment WebView dialog, payment-proof verification (`/api/public/payment-proofs/<proof>`), order-cancel endpoint, `client_ref` UUID, and the `ad_free` flag.
- Business cooperation: `BUSINESS_INTENT_BASE_URL` (same leads.3jiezhiwai.com host), the `/api/sources/carrier-ims/intents` POST endpoint, `BUSINESS_CONTACT_TEXT` / `BUSINESS_CONTACT_URL` BuildConfig fields, and the COOPERATION tab UI.
- The SUPPORT tab ($) with donation form, payment channels and donor records feed.
- All related strings, drawables, model classes (`CommercialAd`, `AdPlacement`, `BusinessIntentType`, `SupportRecord`, `SupportPaymentChannel`, `PaymentProofVerification`) and the `SupportRulesTest` unit tests for this code.

Verification: a full endpoint sweep of the remaining code shows only functional network-check URLs (Google generate_204 variants for the captive-portal fix, ipapi.co for the network-exit feature, tiktok.com for the TikTok-fix reachability check). No analytics, no tracking, no ad fetches.

### Updater removed
- In-app update check against the GitHub releases API, the update dialog, and the DownloadManager-based APK download/install flow.
- `UpdateInstallReceiver`, `UpdateApkCleanup`, the `REQUEST_INSTALL_PACKAGES` permission, and the update-related strings/drawables.

### Developer junk removed
- GitHub repo and issue links in the About card, the "submit issue" action (clipboard + upstream issue page), the issue-failure log collection (`issue_failure_logs.txt` writer and its call sites), and the IssueReportHintCard. The system-info card itself (version, device, Android, patch, Shizuku status, logcat access) is kept.

### Build changes
- `versionName` is now `3.9.1-ar1` (upstream `3.9.1`); removed the release `versionNameSuffix`.
- Removed the signing config wiring (`signing.gradle`); release builds are unsigned.
- Deleted upstream CI workflows (push triggers); this repo builds only via manual `workflow_dispatch`.

## What was NOT changed
- `applicationId` remains `io.github.vvb2060.ims.mod`; app name stays "Carrier IMS".
- All IMS/Shizuku functionality: feature switches, IMS registration, APN check/apply, config backup/restore, network-exit check, captive-portal fix, diagnostics, logcat viewer, Quick Settings tiles.
- No default values of any user-toggleable setting were changed.
- ABI filter stays `arm64-v8a` (upstream default).
