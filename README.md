# NetForce

NetForce is Ahmed's private universal IMS enabler for Android: one app that merges the
feature sets of Turbo IMS, Carrier IMS and TensorIMS into a single, chipset-independent
tool with a full Arabic UI.

- Enable carrier features: VoLTE, Wi-Fi Calling (incl. roaming + name format), video
  calling (VT), VoNR, Cross-SIM calling, UT supplementary services, USSD method,
  5G NR (thresholds, 5G+ icon), enhanced-4G visibility, show-LTE-as-4G,
  always-show-network-type, carrier name override, MCC/MNC/country override,
  IMS User-Agent.
- Tools: SIM selector (all or per SIM), current-config reader, apply/reset, IMS status
  snapshot, IMS restart, persistent VoLTE (experimental), re-apply after reboot
  (optional), per-SIM config history + backup/restore, captive-portal editor, APN
  editor, logcat diagnostics, quick-settings tiles, quick presets (Recommended /
  Egypt LTE), Egyptian carrier name shortcuts (WE, Vodafone, Orange, Etisalat).

## Requirements and honest limits

- **Shizuku is required** for all writes (Shizuku with root, or wireless debugging on
  Android 11+). On Android 10 there is no wireless debugging: Shizuku must be started
  from a computer after every reboot unless the device is rooted.
- minSdk 29 (Android 10). VoNR needs Android 11+, some 5G-advanced keys Android 13+,
  SIM country ISO override Android 14+; the app hides or skips what the system lacks.
- Works on all chipsets/vendors as far as the platform allows: setting the carrier
  config overrides is universal, but whether VoLTE/VoWiFi actually registers depends
  on the carrier and the device vendor's IMS stack (Samsung is the least predictable).
- Not affiliated with Google or any vendor. Built from the open-source Turbo IMS /
  Carrier IMS / TensorIMS projects (upstream credits inside the source tree).

## Build

GitHub Actions (`.github/workflows/build.yml`, manual dispatch only) produces an
unsigned release APK and a source ZIP. Sign the APK before installing.
