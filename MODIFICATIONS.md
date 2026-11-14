# NetForce v1.0.0 - build notes

Base: Carrier IMS for Pixel v3.9.1 (ryfineZ/carrier-ims-for-pixel, Apache-2.0), already
fully Arabized and de-monetized in the ahmed-qandil/carrierims-arabic v3.9.1-ar1 mod.
Features ported from TensorIMS v4.0.2 (Mystery00) and Turbo IMS (vvb2060).

## Identity
- New app: applicationId com.netforce.ims, package renamed io.github.vvb2060.ims ->
  com.netforce.ims, app name "NetForce" (English name kept, UI fully Arabic).
- versionName 1.0.0, versionCode 1.

## Platform
- minSdk 33 -> 29 (Android 10). Guards: VoNR keys API 30+, SIM country ISO override
  API 34+ (pre-existing guards kept), persistent 3-arg overrideConfig falls back to
  non-persistent 2-arg via reflection (pre-existing), 5G-advanced keys are plain
  carrier-config keys (harmless on older systems). Persistent VoLTE is reflection-based
  and fails gracefully where unsupported.
- locales_config.xml trimmed to en+ar (was 18 locales listed while gradle already
  stripped them); gradle localeFilters stays en+ar.

## Feature ports (from TensorIMS)
- Persistent VoLTE (experimental): new PersistentVolteModifier instrumentation +
  PersistentVolteSettings (ProvisioningManager KEY_VOIMS_OPT_IN_STATUS + ImsMmTelManager
  user switch + SubscriptionManager property backup/restore). UI card in Extra tab:
  check status / enable / restore, originals backed up per SIM.
- Re-apply after reboot: BootReceiver (BOOT_COMPLETED) waits for Shizuku, re-applies
  the last successfully applied bundle (serialized via BundleStore). Off by default,
  toggle in Extra tab.
- New IMS toggles: VoWiFi roaming (carrier_default_wfc_ims_roaming_enabled_bool),
  Wi-Fi Calling name format (wfc_spn_format_idx_int + data + flight-mode trio),
  USSD method (carrier_ussd_method_int: CS/IMS preferred/only), IMS User-Agent
  (ims.ims_user_agent_string), always-show-network-type
  (always_show_data_rat_icon_bool). INTEGER feature type added end-to-end
  (model, mapper, save/load, backup, apply, UI as labeled radio rows).
- Quick presets row (Recommended / Egypt LTE) applying a full toggle set at once.
- Egyptian carrier name chips (WE / Vodafone EG / Orange EG / Etisalat EG) and Egypt
  country option (eg, MCC 602).
- Carrier name override made live: base app hardcoded carrierName=null and excluded
  STRING features from the UI; both fixed, so carrier name / country ISO / IMS
  User-Agent rows render and commit.

## From the Carrier IMS ar1 base (already done there)
- Complete Arabic UI (180 strings), logcat TalkBack labels in Arabic.
- All ads/monetization removed (ad popup API, DodoPay, business intents, support /
  cooperation tabs), updater and issue reporter removed, About keeps only the
  system-info card. Only functional URLs remain.
- localeFilters en+ar, 16 locale dirs removed, signing removed (unsigned release).

## Accessibility
- Every control is a labeled switch, radio row, text field or chip - no gesture-only
  interactions. TalkBack: all icon buttons have Arabic content descriptions; radio
  rows use selectable+Role.RadioButton; preset/choice chips carry text labels.

## Not ported (deferred)
- TensorIMS embedded backend (wireless-ADB / root server): Shizuku covers the same
  privilege; would be the v2 candidate.
- TensorIMS draft-mode apply flow: NetForce keeps Carrier IMS' per-control immediate
  apply, which is simpler and already verified end-to-end.
