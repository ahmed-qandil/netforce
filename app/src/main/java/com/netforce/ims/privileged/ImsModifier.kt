package com.netforce.ims.privileged

import android.os.Build
import android.os.Bundle
import android.telephony.CarrierConfigManager

/**
 * Bundle builders for IMS carrier-config overrides. Pure value objects used by the
 * app process (MainActivity/MainViewModel/BootReceiver); the privileged execution
 * lives in PrivilegedOps inside the Shizuku UserService process.
 */
object ImsModifier {
    private const val KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ = "nr_advanced_threshold_bandwidth_khz_int"
    private const val KEY_ADDITIONAL_NR_ADVANCED_BANDS = "additional_nr_advanced_bands_int_array"
    private const val KEY_5G_ICON_CONFIGURATION = "5g_icon_configuration_string"
    private const val KEY_NR_ADVANCED_CAPABLE_PCO_ID = "nr_advanced_capable_pco_id_int"
    private const val KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH =
        "include_lte_for_nr_advanced_threshold_bandwidth_bool"
    private const val NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA = 110_000
    private const val NR_ICON_CONFIGURATION_5GA =
        "connected_mmwave:5G_Plus,connected:5G,connected_rrc_idle:5G,not_restricted_rrc_idle:5G,not_restricted_rrc_con:5G"
    private const val BUNDLE_COUNTRY_MCC_OVERRIDE = "country_mcc_override"
    private const val BUNDLE_COUNTRY_MNC_HINT = "country_mnc_hint"
    private val NR_ADVANCED_BANDS_FOR_CHINA = intArrayOf(
        1, 3, 8, 28, 41, 78, 79
    )
    const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
    const val BUNDLE_RESET = "reset"
    const val BUNDLE_PREFER_PERSISTENT = "prefer_persistent"
    const val BUNDLE_RESULT = "result"
    const val BUNDLE_RESULT_MSG = "result_msg"

    fun buildResetBundle(): Bundle = Bundle().apply {
        putBoolean(BUNDLE_RESET, true)
    }

    fun buildBundle(
        carrierName: String?,
        countryISO: String?,
        countryMcc: String?,
        countryMncHint: String?,
        enableVoLTE: Boolean,
        enableVoWiFi: Boolean,
        enableVT: Boolean,
        enableVoNR: Boolean,
        enableCrossSIM: Boolean,
        enableUT: Boolean,
        enable5GNR: Boolean,
        enable5GThreshold: Boolean,
        enable5GPlusIcon: Boolean,
        enableShow4GForLTE: Boolean,
        enableVoWiFiRoaming: Boolean,
        alwaysShowDataRatIcon: Boolean,
        imsUserAgent: String?,
        vowifiNameFormat: Int,
        ussdMethod: Int,
    ): Bundle {
        val bundle = Bundle()
        if (carrierName?.isNotBlank() == true) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
            bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
            bundle.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, ":3")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (countryISO?.isNotBlank() == true) {
                bundle.putString(
                    CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                    countryISO
                )
            }
        }
        normalizeMccForOverride(countryMcc)?.let {
            bundle.putString(BUNDLE_COUNTRY_MCC_OVERRIDE, it)
        }
        normalizeMncForOverride(countryMncHint)?.let {
            bundle.putString(BUNDLE_COUNTRY_MNC_HINT, it)
        }
        if (enableVoLTE) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false)
        }

        if (enableShow4GForLTE) {
            bundle.putBoolean("show_4g_for_lte_data_icon_bool", true)
        }

        if (enableVT) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
        }

        if (enableUT) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true)
        }

        if (enableCrossSIM) {
            bundle.putBoolean(
                CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                true
            )
            bundle.putBoolean(
                CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                true
            )
        }

        if (enableVoWiFi) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
            bundle.putBoolean(
                CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL,
                true
            )
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true)
            bundle.putBoolean("show_wifi_calling_icon_in_status_bar_bool", true)
            if (enableVoWiFiRoaming) {
                bundle.putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true)
            }
        }

        if (vowifiNameFormat >= 0) {
            bundle.putInt("wfc_spn_format_idx_int", vowifiNameFormat)
            bundle.putInt("wfc_data_spn_format_idx_int", vowifiNameFormat)
            bundle.putInt("wfc_flight_mode_spn_format_idx_int", -1)
        }

        if (ussdMethod >= 0) {
            bundle.putInt("carrier_ussd_method_int", ussdMethod)
        }

        if (imsUserAgent?.isNotBlank() == true) {
            bundle.putString("ims.ims_user_agent_string", imsUserAgent)
        }

        if (alwaysShowDataRatIcon) {
            bundle.putBoolean("always_show_data_rat_icon_bool", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (enableVoNR) {
                bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true)
            }
        }

        if (enable5GNR) {
            bundle.putIntArray(
                CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                intArrayOf(
                    CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                    CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
                )
            )
            if (enable5GPlusIcon) {
                bundle.putInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA)
                bundle.putBoolean(
                    KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH,
                    false
                )
                bundle.putIntArray(KEY_ADDITIONAL_NR_ADVANCED_BANDS, NR_ADVANCED_BANDS_FOR_CHINA)
                bundle.putString(KEY_5G_ICON_CONFIGURATION, NR_ICON_CONFIGURATION_5GA)
                bundle.putInt(KEY_NR_ADVANCED_CAPABLE_PCO_ID, 0)
            }
            if (enable5GThreshold) {
                bundle.putIntArray(
                    CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                    intArrayOf(
                        -128,
                        -118,
                        -108,
                        -98,
                    )
                )
            }
        }
        return bundle
    }

    private fun normalizeMccForOverride(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val firstPart = raw.trim().substringBefore('-')
        val digits = firstPart.filter { it.isDigit() }.take(3)
        return digits.takeIf { it.length == 3 }
    }

    private fun normalizeMncForOverride(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }.take(3)
        return digits.takeIf { it.length in 2..3 }
    }
}
