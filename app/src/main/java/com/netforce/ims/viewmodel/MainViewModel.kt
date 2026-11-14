package com.netforce.ims.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netforce.ims.BuildConfig
import com.netforce.ims.R
import com.netforce.ims.ShizukuProvider
import com.netforce.ims.BootReceiver
import com.netforce.ims.model.BundleStore
import com.netforce.ims.model.Feature
import com.netforce.ims.model.FeatureConfigMapper
import com.netforce.ims.model.FeatureValue
import com.netforce.ims.model.FeatureValueType
import com.netforce.ims.model.ApnDraftConfig
import com.netforce.ims.model.ConfigBackupSnapshot
import com.netforce.ims.model.NetworkExitStatus
import com.netforce.ims.model.ShizukuStatus
import com.netforce.ims.model.SimSelection
import com.netforce.ims.model.SupportRules
import com.netforce.ims.model.SystemInfo
import com.netforce.ims.privileged.ImsModifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * 主界面的 ViewModel，负责管理 UI 状态和业务逻辑。
 * 包括 Shizuku 状态监听、系统信息加载、SIM 卡信息加载以及 IMS 配置的读写。
 */
class MainViewModel(private val application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val RUNTIME_PREFS = "runtime_state"
        private const val CONFIG_BACKUP_PREFS = "config_backups"
        private const val COUNTRY_MCC_PREF_KEY = "__country_mcc_override__"
        private const val TIKTOK_RANDOM_ISO_PREF_KEY = "__tiktok_random_iso__"
        private const val KEY_LAST_BOOT_COUNT = "last_boot_count"
        private const val CAPTIVE_PORTAL_CHECK_TIMEOUT_MS = 2_500
        private const val NETWORK_EXIT_CHECK_TIMEOUT_MS = 4_000
        private const val IMS_REGISTER_RETRY_COUNT = 4
        private const val IMS_REGISTER_RETRY_DELAY_MS = 2_000L
        private const val NETWORK_EXIT_API_URL = "https://ipapi.co/json/"
        private val DEFAULT_CAPTIVE_PORTAL_TEST_URLS = listOf(
            "http://connectivitycheck.gstatic.cn/generate_204",
            "https://www.google.cn/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.google.com/generate_204"
        )
        private val NETWORK_EXIT_SERVICE_URLS = linkedMapOf(
            "Google" to "https://www.google.com/generate_204",
            "TikTok" to "https://www.tiktok.com/",
            "التحقق" to "http://connectivitycheck.gstatic.cn/generate_204",
        )
    }

    private data class BootRestoreResult(
        val attempted: Int,
        val success: Int,
        val failed: Int,
    )

    data class ImsRegisterResult(
        val registered: Boolean?,
        val backendErrorMessage: String?,
    )

    enum class CaptivePortalFixMode {
        NEED_FIX,
        CAN_RESTORE,
        NORMAL,
    }

    data class CaptivePortalFixState(
        val mode: CaptivePortalFixMode,
        val httpUrl: String,
        val httpsUrl: String,
    )

    private var toast: Toast? = null
    private val runtimePrefs = application.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
    private val configBackupPrefs = application.getSharedPreferences(CONFIG_BACKUP_PREFS, Context.MODE_PRIVATE)
    private var pendingConfigRestoreAfterBoot = false
    private var restoringConfigAfterBoot = false
    private val canUsePersistentOverride by lazy {
        val flags = application.applicationInfo.flags
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun normalizeMcc(raw: String): String {
        return raw.filter { it.isDigit() }.take(3)
    }

    private fun normalizeIso(raw: String): String {
        return raw.trim().lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)
    }

    private fun nowShortTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun isChinaDomesticSim(selectedSim: SimSelection): Boolean {
        val iccId = selectedSim.iccId.trim()
        if (iccId.startsWith("8986")) return true
        return normalizeMcc(selectedSim.mcc) == "460"
    }

    private fun resolveIsoByMcc(mccRaw: String, fallbackIsoRaw: String): String? {
        val mcc = normalizeMcc(mccRaw)
        val fallbackIso = normalizeIso(fallbackIsoRaw)
        if (mcc.isBlank()) return fallbackIso.ifBlank { null }
        val mccInt = mcc.toIntOrNull()
        val iso = when {
            mcc == "460" -> "cn"
            mcc == "454" -> "hk"
            mcc == "466" -> "tw"
            mccInt != null && mccInt in 310..316 -> "us"
            mccInt != null && mccInt in 440..441 -> "jp"
            mccInt != null && mccInt in 234..235 -> "gb"
            mcc == "450" -> "kr"
            mcc == "525" -> "sg"
            else -> fallbackIso
        }
        return iso.ifBlank { null }
    }

    private fun loadOrCreateTikTokRandomIso(subId: Int): String {
        val prefs = application.getSharedPreferences("sim_config_$subId", Context.MODE_PRIVATE)
        val existing = prefs.getString(TIKTOK_RANDOM_ISO_PREF_KEY, "").orEmpty().filter { it.isDigit() }
        // Locale region 仅接受 2 位字母或 3 位数字，旧版本的 5 位数字会触发系统应用崩溃。
        if (existing.length == 3) return existing
        val generated = (1..999).random().toString().padStart(3, '0')
        prefs.edit { putString(TIKTOK_RANDOM_ISO_PREF_KEY, generated) }
        return generated
    }

    private fun clearTikTokRandomIso(subId: Int) {
        application.getSharedPreferences("sim_config_$subId", Context.MODE_PRIVATE).edit {
            remove(TIKTOK_RANDOM_ISO_PREF_KEY)
        }
    }

    private fun resolveCountryIsoOverrideForApply(
        selectedSim: SimSelection,
        enableTikTokFix: Boolean,
    ): String? {
        if (selectedSim.subId < 0) return null
        val linkedIso = resolveIsoByMcc(selectedSim.mcc, selectedSim.countryIso)
        if (!enableTikTokFix) {
            clearTikTokRandomIso(selectedSim.subId)
            return linkedIso
        }
        // 仅国内 SIM 启用随机数字 ISO；海外 SIM 维持正常国家 ISO。
        if (!isChinaDomesticSim(selectedSim)) {
            clearTikTokRandomIso(selectedSim.subId)
            return linkedIso
        }
        return loadOrCreateTikTokRandomIso(selectedSim.subId)
    }

    fun resolveCountryIsoOverridePreview(
        selectedSim: SimSelection,
        map: Map<Feature, FeatureValue>,
    ): String? {
        if (selectedSim.subId < 0) return null
        val enableTikTokFix = (map[Feature.TIKTOK_NETWORK_FIX]?.data as? Boolean) == true
        return resolveCountryIsoOverrideForApply(selectedSim, enableTikTokFix)
    }

    // 系统信息状态流
    private val _systemInfo = MutableStateFlow(SystemInfo())
    val systemInfo: StateFlow<SystemInfo> = _systemInfo.asStateFlow()

    // Shizuku 运行状态流
    private val _shizukuStatus = MutableStateFlow(ShizukuStatus.CHECKING)
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    // 所有可用 SIM 卡列表流
    private val _allSimList = MutableStateFlow<List<SimSelection>>(emptyList())
    val allSimList: StateFlow<List<SimSelection>> = _allSimList.asStateFlow()

    // 失败日志（用于 issue 提交）

    // Shizuku Binder 接收监听器（服务连接/授权后触发）
    private val binderListener = Shizuku.OnBinderReceivedListener { updateShizukuStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateShizukuStatus() }

    init {
        pendingConfigRestoreAfterBoot = checkAndMarkBootChanged()
        loadSimList()
        loadSystemInfo()
        updateShizukuStatus()
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    /**
     * 更新 Shizuku 的当前状态。
     * 检查服务是否运行、是否需要更新以及权限授予情况。
     */
    fun updateShizukuStatus() {
        viewModelScope.launch {
            val previousStatus = _shizukuStatus.value
            if (Shizuku.isPreV11()) {
                _shizukuStatus.value = ShizukuStatus.NEED_UPDATE
                return@launch
            }
            val status = when {
                !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
                else -> ShizukuStatus.READY
            }
            _shizukuStatus.value = status
            if (
                status == ShizukuStatus.READY &&
                (
                    previousStatus != ShizukuStatus.READY ||
                        _allSimList.value.isEmpty() ||
                        _allSimList.value.all { it.subId == -1 }
                    )
            ) {
                loadSimListInternal()
            }
            if (status == ShizukuStatus.READY) {
                maybeRestoreSavedConfigurationAfterBoot()
            }
        }
    }

    /**
     * 请求 Shizuku 授权。
     */
    fun requestShizukuPermission(requestCode: Int) {
        viewModelScope.launch {
            if (Shizuku.isPreV11()) {
                _shizukuStatus.value = ShizukuStatus.NEED_UPDATE
            } else {
                Shizuku.requestPermission(requestCode)
            }
        }
    }

    /**
     * 加载默认的功能配置。
     * 当没有保存的配置时使用此默认值。
     */
    fun loadDefaultPreferences(): Map<Feature, FeatureValue> {
        val featureSwitches = linkedMapOf<Feature, FeatureValue>()
        for (feature in Feature.entries) {
            featureSwitches.put(feature, FeatureValue(feature.defaultValue, feature.valueType))
        }
        return featureSwitches
    }

    /**
     * 通过 Shizuku 读取设备上的 SIM 卡信息。
     * 主 SIM 优先显示，“所有 SIM 卡”放在列表末尾。
     */
    fun loadSimList() {
        viewModelScope.launch {
            loadSimListInternal()
        }
    }

    /**
     * 手动刷新 SIM 列表并返回是否读取到了至少 1 张真实 SIM。
     * 返回 false 代表当前只剩“所有 SIM”占位项。
     */
    suspend fun refreshSimListNow(): Boolean = loadSimListInternal()

    private suspend fun loadSimListInternal(): Boolean {
        val shizukuReady =
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (!shizukuReady) {
            val title = application.getString(R.string.all_sim)
            _allSimList.value = listOf(SimSelection(-1, "", "", -1, title))
            return false
        }
        val retryCount = if (shizukuReady) 3 else 1
        var simInfoList: List<SimSelection> = emptyList()
        for (attempt in 0 until retryCount) {
            simInfoList = ShizukuProvider.readSimInfoList(application)
            if (simInfoList.isNotEmpty()) {
                break
            }
            if (attempt < retryCount - 1) {
                delay(250)
            }
        }

        val primarySubId = resolvePrimarySubId()
        val sortedSimList = simInfoList.sortedWith(
            compareByDescending<SimSelection> { it.subId == primarySubId }
                .thenBy { it.simSlotIndex }
                .thenBy { it.subId }
        )
        val resultList = sortedSimList.toMutableList()
        // 添加默认的 "所有 SIM 卡" 选项 (subId = -1) 到末尾
        val title = application.getString(R.string.all_sim)
        resultList.add(SimSelection(-1, "", "", -1, title))
        _allSimList.value = resultList
        return simInfoList.isNotEmpty()
    }

    private fun resolvePrimarySubId(): Int {
        val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return dataSubId
        val voiceSubId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (voiceSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return voiceSubId
        val smsSubId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (smsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return smsSubId
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * 加载当前应用和系统的基本信息。
     */
    private fun loadSystemInfo() {
        viewModelScope.launch {
            _systemInfo.value = SystemInfo(
                appVersionName = BuildConfig.VERSION_NAME,
                androidVersion = "Android ${Build.VERSION.RELEASE} / ${Build.DISPLAY}",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                systemVersion = Build.DISPLAY,
                securityPatchVersion = Build.VERSION.SECURITY_PATCH,
            )
        }
    }

    /**
     * 应用 IMS 配置到选定的 SIM 卡。
     * 此操作会调用 ShizukuProvider 进行特权操作，并保存当前配置到本地。
     */
    suspend fun onApplyConfiguration(
        selectedSim: SimSelection,
        map: Map<Feature, FeatureValue>,
        countryMccOverride: String? = null,
    ): String? {
        // 构建传递给底层 ImsModifier 的配置 Bundle
        val carrierName: String? = (map[Feature.CARRIER_NAME]?.data as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val enableTikTokFix = (map[Feature.TIKTOK_NETWORK_FIX]?.data ?: false) as Boolean
        val countryISO =
            if (selectedSim.subId == -1) null else resolveCountryIsoOverrideForApply(
                selectedSim,
                enableTikTokFix
            )
        val countryMcc = countryMccOverride
            ?.let { SupportRules.normalizeMcc(it) }
            ?.takeIf { it.length == 3 }
        val countryMnc =
            if (selectedSim.subId == -1) null else selectedSim.mnc
        val enableVoLTE = (map[Feature.VOLTE]?.data ?: true) as Boolean
        val enableVoWiFi = (map[Feature.VOWIFI]?.data ?: true) as Boolean
        val enableVT = (map[Feature.VT]?.data ?: true) as Boolean
        val enableVoNR = (map[Feature.VONR]?.data ?: true) as Boolean
        val enableCrossSIM = (map[Feature.CROSS_SIM]?.data ?: true) as Boolean
        val enableUT = (map[Feature.UT]?.data ?: true) as Boolean
        val enable5GNR = (map[Feature.FIVE_G_NR]?.data ?: true) as Boolean
        val enable5GThreshold = (map[Feature.FIVE_G_THRESHOLDS]?.data ?: true) as Boolean
        val enable5GPlusIcon = (map[Feature.FIVE_G_PLUS_ICON]?.data ?: true) as Boolean
        val enableShow4GForLTE = (map[Feature.SHOW_4G_FOR_LTE]?.data ?: false) as Boolean
        val enableVoWiFiRoaming = (map[Feature.VOWIFI_ROAMING]?.data ?: true) as Boolean
        val alwaysShowDataRatIcon =
            (map[Feature.ALWAYS_SHOW_DATA_RAT_ICON]?.data ?: false) as Boolean
        val imsUserAgent = map[Feature.IMS_USER_AGENT]?.data as? String
        val vowifiNameFormat =
            (map[Feature.VOWIFI_NAME_FORMAT]?.data as? Int) ?: -1
        val ussdMethod = (map[Feature.USSD_METHOD]?.data as? Int) ?: -1

        val bundle = ImsModifier.buildBundle(
            carrierName,
            countryISO,
            countryMcc,
            countryMnc,
            enableVoLTE,
            enableVoWiFi,
            enableVT,
            enableVoNR,
            enableCrossSIM,
            enableUT,
            enable5GNR,
            enable5GThreshold,
            enable5GPlusIcon,
            enableShow4GForLTE,
            enableVoWiFiRoaming,
            alwaysShowDataRatIcon,
            imsUserAgent,
            vowifiNameFormat,
            ussdMethod
        )
        bundle.putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, selectedSim.subId)
        bundle.putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistentOverride)

        // 调用 Shizuku 服务进行实际修改
        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)
        if (resultMsg == null) {
            // 仅在应用成功后保存配置，避免本地状态与系统状态不一致
            saveConfiguration(selectedSim.subId, map, countryMccOverride)
            saveLastAppliedBundle(bundle)
        }
        return resultMsg
    }

    // ---- Auto-restore after reboot (optional, default off) ----

    private val autoRestorePrefs =
        application.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    private fun saveLastAppliedBundle(bundle: Bundle) {
        autoRestorePrefs.edit {
            putString(BootReceiver.KEY_LAST_BUNDLE, BundleStore.serialize(bundle))
        }
    }

    fun isAutoRestoreEnabled(): Boolean =
        autoRestorePrefs.getBoolean(BootReceiver.KEY_ENABLED, false)

    fun setAutoRestoreEnabled(enabled: Boolean) {
        autoRestorePrefs.edit { putBoolean(BootReceiver.KEY_ENABLED, enabled) }
    }

    // ---- Persistent VoLTE (experimental) ----

    private val persistentVoltePrefs =
        application.getSharedPreferences("persistent_volte", Context.MODE_PRIVATE)

    suspend fun queryPersistentVolte(subId: Int): Pair<Int, Int>? {
        if (subId < 0) return null
        val result = ShizukuProvider.queryPersistentVolte(application, subId)
        if (result != null) {
            persistentVoltePrefs.edit {
                putInt("opt_in_$subId", result.first)
                putInt("user_enabled_$subId", result.second)
            }
        }
        return result
    }

    suspend fun enablePersistentVolte(subId: Int): String? {
        if (subId < 0) return "invalid subId"
        // 先备份原值再开启，失败时界面可据此恢复
        queryPersistentVolte(subId)
        return ShizukuProvider.enablePersistentVolte(application, subId)
    }

    suspend fun restorePersistentVolte(subId: Int): String? {
        if (subId < 0) return "invalid subId"
        val optIn = persistentVoltePrefs.getInt("opt_in_$subId", -1)
        val userEnabled = persistentVoltePrefs.getInt("user_enabled_$subId", -1)
        return ShizukuProvider.restorePersistentVolte(application, subId, optIn, userEnabled)
    }

    /**
     * 将配置保存到 SharedPreferences 中以便下次加载。
     */
    private fun saveConfiguration(
        subId: Int,
        map: Map<Feature, FeatureValue>,
        countryMccOverride: String?,
    ) {
        val prefs = application.getSharedPreferences("sim_config_$subId", Context.MODE_PRIVATE)
        val keepTikTokRandomIso = prefs.getString(TIKTOK_RANDOM_ISO_PREF_KEY, null)
        val tiktokEnabled = (map[Feature.TIKTOK_NETWORK_FIX]?.data as? Boolean) == true
        prefs.edit {
            clear() // 清除旧配置
            map.forEach { (feature, value) ->
                when (value.valueType) {
                    FeatureValueType.BOOLEAN -> putBoolean(feature.name, value.data as Boolean)
                    FeatureValueType.STRING -> putString(feature.name, value.data as String)
                    FeatureValueType.INTEGER -> putInt(feature.name, value.data as Int)
                }
            }
            if (tiktokEnabled && !keepTikTokRandomIso.isNullOrBlank()) {
                putString(TIKTOK_RANDOM_ISO_PREF_KEY, keepTikTokRandomIso)
            } else {
                remove(TIKTOK_RANDOM_ISO_PREF_KEY)
            }
            val normalizedCountryMcc = countryMccOverride
                ?.let { SupportRules.normalizeMcc(it) }
                ?.takeIf { it.length == 3 }
            if (normalizedCountryMcc != null) {
                putString(COUNTRY_MCC_PREF_KEY, normalizedCountryMcc)
            } else {
                remove(COUNTRY_MCC_PREF_KEY)
            }
        }
    }

    fun loadSavedCountryMccOverride(subId: Int): String {
        if (subId < 0) return ""
        return application
            .getSharedPreferences("sim_config_$subId", Context.MODE_PRIVATE)
            .getString(COUNTRY_MCC_PREF_KEY, "")
            .orEmpty()
    }

    /**
     * 加载指定 subId 的配置。如果不存在则返回 null。
     */
    fun loadConfiguration(subId: Int): Map<Feature, FeatureValue>? {
        val prefs = application.getSharedPreferences("sim_config_$subId", Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return null

        val map = linkedMapOf<Feature, FeatureValue>()
        Feature.entries.forEach { feature ->
            if (prefs.contains(feature.name)) {
                when (feature.valueType) {
                    FeatureValueType.BOOLEAN -> {
                        val data = prefs.getBoolean(feature.name, feature.defaultValue as Boolean)
                        map[feature] = FeatureValue(data, feature.valueType)
                    }

                    FeatureValueType.STRING -> {
                        val data =
                            prefs.getString(feature.name, feature.defaultValue as String) ?: ""
                        map[feature] = FeatureValue(data, feature.valueType)
                    }

                    FeatureValueType.INTEGER -> {
                        val data = prefs.getInt(feature.name, feature.defaultValue as Int)
                        map[feature] = FeatureValue(data, feature.valueType)
                    }
                }
            } else {
                map[feature] = FeatureValue(feature.defaultValue, feature.valueType)
            }
        }
        return map
    }

    suspend fun loadCurrentConfiguration(subId: Int): Map<Feature, FeatureValue>? {
        if (subId < 0) return null
        val bundle = ShizukuProvider.readCarrierConfig(
            application,
            subId,
            FeatureConfigMapper.readKeys
        ) ?: return null
        return FeatureConfigMapper.fromBundle(bundle)
    }

    suspend fun readImsRegistrationStatus(subId: Int): Boolean? {
        if (subId < 0) return null
        return ShizukuProvider.readImsRegistrationStatus(application, subId)
    }

    suspend fun registerIms(subId: Int): ImsRegisterResult {
        if (subId < 0) return ImsRegisterResult(null, "invalid subId")
        val resultMsg = ShizukuProvider.restartImsRegistration(application, subId)
        if (resultMsg != null) {
            toast(application.getString(R.string.ims_restart_failed, resultMsg), false)
            return ImsRegisterResult(null, resultMsg)
        }
        val status = waitForImsRegistrationStatus(subId)
        when (status) {
            true -> toast(application.getString(R.string.ims_register_success))
            false -> toast(application.getString(R.string.ims_register_pending), false)
            null -> toast(application.getString(R.string.ims_register_unknown), false)
        }
        return ImsRegisterResult(status, null)
    }

    private suspend fun waitForImsRegistrationStatus(subId: Int): Boolean? {
        var lastStatus: Boolean? = null
        repeat(IMS_REGISTER_RETRY_COUNT) {
            delay(IMS_REGISTER_RETRY_DELAY_MS)
            val status = readImsRegistrationStatus(subId)
            lastStatus = status
            if (status == true) return true
        }
        return lastStatus
    }

    suspend fun applyCaptivePortalCnUrls(): String? {
        val resultMsg = ShizukuProvider.applyCaptivePortalCnUrls(application)
        return resultMsg
    }

    suspend fun restoreCaptivePortalDefaultUrls(): String? {
        val resultMsg = ShizukuProvider.restoreCaptivePortalDefaultUrls(application)
        return resultMsg
    }

    suspend fun queryCaptivePortalFixState(): CaptivePortalFixState? {
        val config = ShizukuProvider.queryCaptivePortalConfig(application) ?: return null
        val currentConfigReachable = isPortalConfigReachable(config.httpUrl, config.httpsUrl)
        val mode = when {
            config.isOverridden && currentConfigReachable -> CaptivePortalFixMode.CAN_RESTORE
            config.isOverridden -> CaptivePortalFixMode.NEED_FIX
            isDefaultPortalCheckReachable() -> CaptivePortalFixMode.NORMAL
            else -> CaptivePortalFixMode.NEED_FIX
        }
        return CaptivePortalFixState(
            mode = mode,
            httpUrl = config.httpUrl,
            httpsUrl = config.httpsUrl
        )
    }

    suspend fun checkNetworkExit(): Result<NetworkExitStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJsonObject(NETWORK_EXIT_API_URL)
            val ip = json.optString("ip")
            val org = json.optString("org").ifBlank { json.optString("asn") }
            val googleReachable = isGeneralUrlReachable(NETWORK_EXIT_SERVICE_URLS.getValue("Google"))
            val tiktokReachable = isGeneralUrlReachable(NETWORK_EXIT_SERVICE_URLS.getValue("TikTok"))
            val captiveReachable = isPortalUrlReachable(NETWORK_EXIT_SERVICE_URLS.getValue("التحقق"))
            NetworkExitStatus(
                ip = ip.ifBlank { "N/A" },
                ipVersion = if (ip.contains(":")) "IPv6" else "IPv4",
                country = json.optString("country_name").ifBlank { json.optString("country") },
                region = json.optString("region"),
                city = json.optString("city"),
                org = org.ifBlank { "N/A" },
                risk = estimateIpRisk(org),
                googleReachable = googleReachable,
                tiktokReachable = tiktokReachable,
                captivePortalReachable = captiveReachable,
            )
        }
    }

    fun buildSuggestedApnConfig(selectedSim: SimSelection): ApnDraftConfig {
        val mcc = SupportRules.normalizeMcc(selectedSim.mcc)
        val mnc = SupportRules.normalizeMnc(selectedSim.mnc)
        val apn = when {
            mcc == "460" && mnc in setOf("00", "02", "04", "07", "08") -> "cmnet"
            mcc == "460" && mnc in setOf("01", "06", "09") -> "3gnet"
            mcc == "460" && mnc in setOf("03", "05", "11", "12") -> "ctnet"
            else -> "internet"
        }
        val name = selectedSim.carrierName.ifBlank { selectedSim.showTitle }.ifBlank { "NetForce APN" }
        return ApnDraftConfig(
            name = name,
            apn = apn,
            type = "default,supl,ims",
            mcc = mcc,
            mnc = mnc,
        )
    }

    suspend fun applyApnConfig(
        selectedSim: SimSelection,
        config: ApnDraftConfig,
    ): String? {
        if (selectedSim.subId < 0) return "invalid subId"
        SupportRules.validateApnDraft(config)?.let { return it }
        return ShizukuProvider.applyApnConfig(application, selectedSim.subId, config)
    }

    fun saveConfigBackup(
        selectedSim: SimSelection,
        featureMap: Map<Feature, FeatureValue>,
        name: String,
        countryMccOverride: String,
    ): ConfigBackupSnapshot {
        val snapshot = ConfigBackupSnapshot(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { selectedSim.showTitle.ifBlank { "SIM ${selectedSim.subId}" } },
            createdAtMillis = System.currentTimeMillis(),
            subId = selectedSim.subId,
            simTitle = selectedSim.showTitle,
            mcc = selectedSim.mcc,
            mnc = selectedSim.mnc,
            countryIso = selectedSim.countryIso,
            featureValues = Feature.entries.associateWith { feature ->
                featureMap[feature] ?: FeatureValue(feature.defaultValue, feature.valueType)
            },
            countryMccOverride = SupportRules.normalizeMcc(countryMccOverride),
        )
        configBackupPrefs.edit {
            putString(snapshot.id, snapshot.toJson().toString())
        }
        return snapshot
    }

    fun loadConfigBackups(): List<ConfigBackupSnapshot> {
        return configBackupPrefs.all.values
            .mapNotNull { raw -> (raw as? String)?.let { parseConfigBackup(it) } }
            .sortedByDescending { it.createdAtMillis }
    }

    fun deleteConfigBackup(id: String) {
        configBackupPrefs.edit { remove(id) }
    }

    private suspend fun isDefaultPortalCheckReachable(): Boolean {
        return withContext(Dispatchers.IO) {
            DEFAULT_CAPTIVE_PORTAL_TEST_URLS.any { url -> isPortalUrlReachable(url) }
        }
    }

    private suspend fun isPortalConfigReachable(httpUrl: String, httpsUrl: String): Boolean {
        val targets = buildList {
            if (httpUrl.isNotBlank()) add(httpUrl)
            if (httpsUrl.isNotBlank()) add(httpsUrl)
        }
        if (targets.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            targets.any { url -> isPortalUrlReachable(url) }
        }
    }

    private fun isPortalUrlReachable(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CAPTIVE_PORTAL_CHECK_TIMEOUT_MS
                readTimeout = CAPTIVE_PORTAL_CHECK_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            val code = connection.responseCode
            code == HttpURLConnection.HTTP_NO_CONTENT
        } catch (t: Throwable) {
            Log.w(TAG, "portal reachability check failed: $url, msg=${t.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isGeneralUrlReachable(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NETWORK_EXIT_CHECK_TIMEOUT_MS
                readTimeout = NETWORK_EXIT_CHECK_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
            }
            connection.responseCode in 200..399
        } catch (t: Throwable) {
            Log.w(TAG, "service reachability check failed: $url, msg=${t.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun estimateIpRisk(org: String): String {
        val lower = org.lowercase(Locale.US)
        val riskyWords = listOf("cloud", "hosting", "data center", "datacenter", "vpn", "proxy", "vps")
        return if (riskyWords.any { lower.contains(it) }) "غالبا مركز بيانات/VPN" else "لا مخاطر واضحة"
    }

    private fun fetchJsonObject(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_EXIT_CHECK_TIMEOUT_MS
            readTimeout = NETWORK_EXIT_CHECK_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NetForce/${BuildConfig.VERSION_NAME}")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun ConfigBackupSnapshot.toJson(): JSONObject {
        val features = JSONObject()
        featureValues.forEach { (feature, value) ->
            val item = JSONObject().apply {
                put("type", value.valueType.name)
                put("data", value.data)
            }
            features.put(feature.name, item)
        }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("created_at", createdAtMillis)
            put("sub_id", subId)
            put("sim_title", simTitle)
            put("mcc", mcc)
            put("mnc", mnc)
            put("country_iso", countryIso)
            put("country_mcc_override", countryMccOverride)
            put("features", features)
        }
    }

    private fun parseConfigBackup(raw: String): ConfigBackupSnapshot? {
        return runCatching {
            val json = JSONObject(raw)
            val featuresJson = json.optJSONObject("features") ?: JSONObject()
            val features = linkedMapOf<Feature, FeatureValue>()
            Feature.entries.forEach { feature ->
                val item = featuresJson.optJSONObject(feature.name)
                if (item == null) {
                    features[feature] = FeatureValue(feature.defaultValue, feature.valueType)
                } else {
                    val data: Any = when (feature.valueType) {
                        FeatureValueType.BOOLEAN -> item.optBoolean("data", feature.defaultValue as Boolean)
                        FeatureValueType.STRING -> item.optString("data", feature.defaultValue as String)
                        FeatureValueType.INTEGER -> item.optInt("data", feature.defaultValue as Int)
                    }
                    features[feature] = FeatureValue(data, feature.valueType)
                }
            }
            ConfigBackupSnapshot(
                id = json.optString("id"),
                name = json.optString("name"),
                createdAtMillis = json.optLong("created_at"),
                subId = json.optInt("sub_id", -1),
                simTitle = json.optString("sim_title"),
                mcc = json.optString("mcc"),
                mnc = json.optString("mnc"),
                countryIso = json.optString("country_iso"),
                featureValues = features,
                countryMccOverride = json.optString("country_mcc_override"),
            )
        }.getOrNull()
    }

    /**
     * 重置选中 SIM 卡的配置到运营商默认状态。
     */
    suspend fun onResetConfiguration(selectedSim: SimSelection): Boolean {
        val bundle = ImsModifier.buildResetBundle()
        bundle.putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, selectedSim.subId)
        bundle.putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistentOverride)
        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)
        if (resultMsg == null) {
            application.getSharedPreferences(
                "sim_config_${selectedSim.subId}",
                Context.MODE_PRIVATE
            ).edit {
                remove(COUNTRY_MCC_PREF_KEY)
                remove(TIKTOK_RANDOM_ISO_PREF_KEY)
            }
            toast(application.getString(R.string.config_success_reset_message))
            return true
        }
        toast(application.getString(R.string.config_failed, resultMsg), false)
        return false
    }

    fun runShizukuDiagnostics(
        selectedSim: SimSelection,
        visibleSimList: List<SimSelection>,
        appFeatureMap: Map<Feature, FeatureValue>,
    ): Flow<String> = flow {
        suspend fun emitLine(line: String) {
            emit("[${nowShortTime()}] $line")
        }

        fun formatSimHeadline(sim: SimSelection): String {
            val iccTail = sim.iccId.takeLast(4).ifBlank { "----" }
            return "${sim.showTitle} | subId=${sim.subId} | آخر ICCID=$iccTail"
        }

        fun toOnOff(value: Boolean?): String {
            return when (value) {
                true -> "ON"
                false -> "OFF"
                null -> "N/A"
            }
        }

        fun resolvePortalModeTag(httpUrl: String, httpsUrl: String): String {
            val lowerHttp = httpUrl.lowercase(Locale.ROOT)
            val lowerHttps = httpsUrl.lowercase(Locale.ROOT)
            return if (lowerHttp.contains(".cn/") || lowerHttps.contains(".cn/")) "cn" else "com"
        }

        fun networkTypeName(type: Int): String {
            return when (type) {
                TelephonyManager.NETWORK_TYPE_NR -> "NR(5G)"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE(4G)"
                19 -> "LTE_CA(4G+)"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS(3G)"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE(2G)"
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS(2G)"
                TelephonyManager.NETWORK_TYPE_GSM -> "GSM(2G)"
                TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
                else -> "TYPE_$type"
            }
        }

        emitLine("=== بدء التشخيص: قدرات الشبكة وIMS للمشغل ===")
        emitLine(formatSimHeadline(selectedSim))

        val binderReady = Shizuku.pingBinder()
        val permissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        emitLine("[1/8] فحص Shizuku: binder=${if (binderReady) "متصل" else "غير متصل"}, permission=${if (permissionGranted) "مصرح" else "غير مصرح"}")
        if (!binderReady || !permissionGranted) {
            emitLine("❌ توقف التشخيص: Shizuku غير جاهز.")
            emitLine("=== انتهى التشخيص ===")
            return@flow
        }

        val realSims = visibleSimList.filter { it.subId >= 0 }
        emitLine("[2/8] شرائح SIM: تم العثور على ${realSims.size} شريحة")
        realSims.forEach { sim ->
            emitLine(
                "- ${formatSimHeadline(sim)} | MCC/MNC=${sim.mcc}/${sim.mnc}, ISO=${sim.countryIso.ifBlank { "-" }}"
            )
        }

        emitLine("[3/8] فحص حالة تسجيل IMS")
        val imsStatusBySubId = linkedMapOf<Int, Boolean?>()
        realSims.forEach { sim ->
            val status = runCatching { readImsRegistrationStatus(sim.subId) }.getOrNull()
            imsStatusBySubId[sim.subId] = status
            val text = when (status) {
                true -> "مسجل"
                false -> "غير مسجل"
                null -> "فشل القراءة/غير معروف"
            }
            emitLine("- subId=${sim.subId}: IMS $text")
        }

        emitLine("[4/8] قراءة قدرات CarrierConfig الأساسية (الشريحة المحددة)")
        val keyToLabel = linkedMapOf(
            "carrier_volte_available_bool" to "VoLTE",
            "carrier_wfc_ims_available_bool" to "VoWiFi",
            "carrier_vt_available_bool" to "مكالمات فيديو ViLTE",
            "carrier_supports_ss_over_ut_bool" to "خدمات UT التكميلية",
            "carrier_cross_sim_ims_available_bool" to "مكالمات عبر الشرائح",
            "enable_cross_sim_calling_on_opportunistic_data_bool" to "مكالمات عبر الشرائح مع البيانات المؤقتة",
            "vonr_enabled_bool" to "VoNR",
            "vonr_setting_visibility_bool" to "إظهار مفتاح VoNR",
            "sim_country_iso_override_string" to "تجاوز ISO للشريحة",
        )
        val diagReadKeys = linkedSetOf<String>().apply {
            addAll(keyToLabel.keys)
            addAll(FeatureConfigMapper.readKeys)
        }.toTypedArray()
        val configBundle = runCatching {
            ShizukuProvider.readCarrierConfig(
                application,
                selectedSim.subId,
                diagReadKeys
            )
        }.getOrNull()
        if (configBundle == null) {
            emitLine("❌ فشلت قراءة CarrierConfig")
        } else {
            keyToLabel.forEach { (key, label) ->
                val value = when {
                    configBundle.containsKey(key) -> configBundle.get(key)
                    else -> null
                }
                emitLine("- $label ($key) = ${value ?: "N/A"}")
            }
        }

        emitLine("[5/8] حالة الشبكة الفعلية (الشريحة المحددة)")
        val telephony = application.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(selectedSim.subId)
        val dataTypeResult = runCatching {
            telephony?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val voiceTypeResult = runCatching {
            telephony?.voiceNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val dataType = dataTypeResult.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val voiceType = voiceTypeResult.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val realtime5g = dataType == TelephonyManager.NETWORK_TYPE_NR ||
            voiceType == TelephonyManager.NETWORK_TYPE_NR
        emitLine("- نوع شبكة البيانات الحالي = ${networkTypeName(dataType)}")
        emitLine("- نوع شبكة الصوت الحالي = ${networkTypeName(voiceType)}")
        emitLine("- حالة 5G الفعلية = ${if (realtime5g) "ON" else "OFF"}")
        val realtimeReadError = dataTypeResult.exceptionOrNull() ?: voiceTypeResult.exceptionOrNull()
        if (realtimeReadError != null) {
            emitLine("- قراءة الشبكة الفعلية محدودة: ${realtimeReadError.javaClass.simpleName} (${realtimeReadError.message ?: "no message"})")
        }
        emitLine("- ملاحظة: حالة التسجيل الفعلية على الشبكة تختلف في المعنى عن مفاتيح CarrierConfig.")

        emitLine("[6/8] مفاتيح التطبيق مقابل قراءة CarrierConfig (الشريحة المحددة)")
        if (configBundle == null) {
            emitLine("❌ تعذر مطابقة الميزات بسبب فشل قراءة CarrierConfig")
        } else {
            val mapped = FeatureConfigMapper.fromBundle(configBundle)
            val rows = listOf(
                Feature.VOLTE to "VoLTE",
                Feature.VOWIFI to "VoWiFi",
                Feature.VT to "ViLTE",
                Feature.CROSS_SIM to "مكالمات عبر الشرائح",
                Feature.UT to "خدمات UT التكميلية",
                Feature.VONR to "VoNR",
                Feature.FIVE_G_NR to "5G NR",
                Feature.FIVE_G_PLUS_ICON to "أيقونة 5GA/5G+",
                Feature.SHOW_4G_FOR_LTE to "إظهار LTE كـ 4G",
                Feature.TIKTOK_NETWORK_FIX to "إصلاح TikTok",
            )
            rows.forEach { (feature, label) ->
                val appEnabled = appFeatureMap[feature]?.data as? Boolean
                val systemEnabled = mapped[feature]?.data as? Boolean
                val mismatch = if (appEnabled != null && systemEnabled != null && appEnabled != systemEnabled) " ⚠️ غير متطابق" else ""
                emitLine("- $label | App=${toOnOff(appEnabled)} | CarrierConfig=${toOnOff(systemEnabled)}$mismatch")
            }
        }

        emitLine("[7/8] حالة التحقق من الشبكة وتجاوز رمز الدولة")
        val captiveState = runCatching { queryCaptivePortalFixState() }.getOrNull()
        if (captiveState == null) {
            emitLine("- فشلت قراءة حالة التحقق من الشبكة")
        } else {
            emitLine("- وضع التحقق من الشبكة = ${resolvePortalModeTag(captiveState.httpUrl, captiveState.httpsUrl)}")
            emitLine("- عنوان HTTP للتحقق = ${captiveState.httpUrl}")
            emitLine("- عنوان HTTPS للتحقق = ${captiveState.httpsUrl}")
        }
        val savedMcc = loadSavedCountryMccOverride(selectedSim.subId)
        emitLine("- تجاوز MCC المحفوظ محليا = ${savedMcc.ifBlank { "(فارغ)" }}")

        emitLine("[8/8] الخلاصة")
        val selectedImsStatus = imsStatusBySubId[selectedSim.subId]
        when (selectedImsStatus) {
            true -> emitLine("✅ الشريحة المحددة مسجلة حاليا في IMS والمسار الأساسي سليم.")
            false -> emitLine("⚠️ الشريحة المحددة غير مسجلة حاليا في IMS. يُنصح بفحص VoLTE وVoWiFi وAPN (ims) وحالة التفعيل لدى المشغل أولا.")
            null -> emitLine("⚠️ فشلت قراءة حالة IMS للشريحة المحددة. أعد المحاولة وتحقق من صلاحية Shizuku.")
        }
        emitLine("=== انتهى التشخيص ===")
    }

    private fun toast(msg: String, short: Boolean = true) {
        toast?.cancel()
        toast =
            Toast.makeText(application, msg, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
        toast?.show()
    }

    private fun checkAndMarkBootChanged(): Boolean {
        val currentBootCount = try {
            Settings.Global.getInt(
                application.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1
            )
        } catch (t: Throwable) {
            Log.w(TAG, "failed to read boot count", t)
            -1
        }
        if (currentBootCount < 0) return false
        val lastBootCount = runtimePrefs.getInt(KEY_LAST_BOOT_COUNT, -1)
        runtimePrefs.edit { putInt(KEY_LAST_BOOT_COUNT, currentBootCount) }
        return lastBootCount != -1 && lastBootCount != currentBootCount
    }

    private suspend fun maybeRestoreSavedConfigurationAfterBoot() {
        if (!pendingConfigRestoreAfterBoot || restoringConfigAfterBoot) return
        restoringConfigAfterBoot = true
        try {
            val result = restoreSavedConfigurationAfterBoot()
            pendingConfigRestoreAfterBoot = false
            if (result.attempted > 0) {
                Log.i(
                    TAG,
                    "auto restore saved config after boot finished: attempted=${result.attempted}, success=${result.success}, failed=${result.failed}"
                )
            }
        } finally {
            restoringConfigAfterBoot = false
        }
    }

    private suspend fun restoreSavedConfigurationAfterBoot(): BootRestoreResult {
        var attempted = 0
        var success = 0
        var failed = 0
        val simList = _allSimList.value.filter { it.subId >= 0 }
        for (sim in simList) {
            val saved = loadConfiguration(sim.subId) ?: continue
            attempted++
            val resultMsg = onApplyConfiguration(
                sim,
                saved,
                countryMccOverride = loadSavedCountryMccOverride(sim.subId)
            )
            if (resultMsg == null) {
                success++
            } else {
                failed++
                Log.w(
                    TAG,
                    "auto restore saved config failed for subId=${sim.subId}, msg=$resultMsg"
                )
            }
        }
        return BootRestoreResult(attempted, success, failed)
    }
}
