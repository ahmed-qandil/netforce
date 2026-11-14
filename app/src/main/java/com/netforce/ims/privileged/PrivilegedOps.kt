package com.netforce.ims.privileged

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.provider.Settings
import android.provider.Telephony
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ITelephony

/**
 * Privileged operations, ported from the old instrumentation classes, running in
 * the Shizuku UserService process (shell uid). No ShizukuBinderWrapper and no
 * shell-permission delegation are needed here: this process IS shell.
 *
 * Every function returns a result Bundle and never throws:
 * BUNDLE_RESULT (boolean) + BUNDLE_RESULT_MSG on failure, plus op-specific data.
 */
internal object PrivilegedOps {
    private const val TAG = "PrivilegedOps"

    const val BUNDLE_RESULT = "result"
    const val BUNDLE_RESULT_MSG = "result_msg"
    const val BUNDLE_SELECT_SIM_ID = "select_sim_id"

    // sim.read
    const val BUNDLE_SIM_LIST = "sim_list"
    const val SIM_SUB_ID = "sub_id"
    const val SIM_DISPLAY_NAME = "display_name"
    const val SIM_CARRIER_NAME = "carrier_name"
    const val SIM_SLOT_INDEX = "slot_index"
    const val SIM_COUNTRY_ISO = "country_iso"
    const val SIM_MCC = "mcc"
    const val SIM_MNC = "mnc"
    const val SIM_ICC_ID = "icc_id"

    // config.read / config.dump
    const val BUNDLE_KEYS = "keys"
    const val BUNDLE_VALUES = "values"
    const val BUNDLE_DUMP_TEXT = "dump_text"

    // config.override
    const val BUNDLE_RESET = "reset"
    const val BUNDLE_PREFER_PERSISTENT = "prefer_persistent"
    private const val BUNDLE_COUNTRY_MCC_OVERRIDE = "country_mcc_override"
    private const val BUNDLE_COUNTRY_MNC_HINT = "country_mnc_hint"

    // ims.status
    const val BUNDLE_STATUS_VALUE = "status_value"

    // captive
    const val BUNDLE_ACTION = "action"
    const val CAPTIVE_ACTION_APPLY_CN = "apply_cn"
    const val CAPTIVE_ACTION_QUERY = "query"
    const val CAPTIVE_ACTION_RESTORE_DEFAULT = "restore_default"
    const val BUNDLE_HTTP_URL = "http_url"
    const val BUNDLE_HTTPS_URL = "https_url"
    const val BUNDLE_IS_CN_URL = "is_cn_url"
    const val BUNDLE_IS_OVERRIDDEN = "is_overridden"
    private const val KEY_CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url"
    private const val KEY_CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url"
    const val CN_HTTP_URL = "http://connectivitycheck.gstatic.cn/generate_204"
    const val CN_HTTPS_URL = "https://www.google.cn/generate_204"

    // apn.apply
    const val BUNDLE_NAME = "name"
    const val BUNDLE_APN = "apn"
    const val BUNDLE_TYPE = "type"
    const val BUNDLE_MCC = "mcc"
    const val BUNDLE_MNC = "mnc"
    private val APN_URI: Uri = Telephony.Carriers.CONTENT_URI
    private const val PREFER_APN_URI_PREFIX = "content://telephony/carriers/preferapn/subId/"

    // persistent_volte
    const val BUNDLE_SUB_ID = "sub_id"
    const val PV_ACTION_QUERY = "query"
    const val PV_ACTION_ENABLE = "enable"
    const val PV_ACTION_RESTORE = "restore"
    const val BUNDLE_OPT_IN = "opt_in"
    const val BUNDLE_USER_ENABLED = "user_enabled"

    private fun ok(): Bundle = Bundle().apply { putBoolean(BUNDLE_RESULT, true) }

    private fun fail(t: Throwable): Bundle = Bundle().apply {
        putBoolean(BUNDLE_RESULT, false)
        putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
    }

    private fun telephony(): ITelephony =
        ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE))
            ?: throw IllegalStateException("ITelephony unavailable")

    private fun activeSubIds(context: Context, selectedSubId: Int): IntArray {
        if (selectedSubId >= 0) return intArrayOf(selectedSubId)
        val sm = context.getSystemService(SubscriptionManager::class.java)
        return sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
    }

    // ---------------------------------------------------------------- sim.read

    @SuppressLint("MissingPermission")
    fun readSimList(context: Context): Bundle = try {
        // SubscriptionManager works for shell without extra delegation (proven on
        // EMUI 10 / Android 10); the ISub path is tried first on API 30+ where
        // TelephonyFrameworkInitializer exists.
        val resultList = readByISub() ?: run {
            val sm =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList ?: emptyList()
        }
        Log.i(TAG, "read sim info list size: ${resultList.size}")
        val entries = ArrayList<Bundle>(resultList.size)
        for (info in resultList) {
            entries.add(Bundle().apply {
                putInt(SIM_SUB_ID, info.subscriptionId)
                putString(SIM_DISPLAY_NAME, info.displayName?.toString() ?: "")
                putString(SIM_CARRIER_NAME, info.carrierName?.toString() ?: "")
                putInt(SIM_SLOT_INDEX, info.simSlotIndex)
                putString(SIM_COUNTRY_ISO, info.countryIso ?: "")
                putString(SIM_MCC, info.mccString ?: "")
                putString(SIM_MNC, info.mncString ?: "")
                putString(SIM_ICC_ID, info.iccId ?: "")
            })
        }
        ok().apply { putParcelableArrayList(BUNDLE_SIM_LIST, entries) }
    } catch (t: Throwable) {
        Log.e(TAG, "readSimList failed", t)
        fail(t)
    }

    /**
     * TelephonyFrameworkInitializer only exists on API 30+, so it is resolved
     * purely by reflection; on Android 10 this returns null and the
     * SubscriptionManager fallback is used.
     */
    private fun readByISub(): List<android.telephony.SubscriptionInfo>? {
        return try {
            val initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            val serviceManager = initializer.getMethod("getTelephonyServiceManager").invoke(null)
                ?: return null
            val registerer = serviceManager.javaClass
                .getMethod("getSubscriptionServiceRegisterer")
                .invoke(serviceManager) ?: return null
            val binder = registerer.javaClass.getMethod("get").invoke(registerer)
                as? android.os.IBinder ?: return null
            val subClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val sub = subClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return null
            @Suppress("UNCHECKED_CAST")
            sub.javaClass.getMethod(
                "getActiveSubscriptionInfoList",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(sub, null, null, true) as? List<android.telephony.SubscriptionInfo>
        } catch (t: Throwable) {
            Log.w(TAG, "readByISub failed, fallback to SubscriptionManager", t)
            null
        }
    }

    // ------------------------------------------------------------ config.read

    fun readCarrierConfig(context: Context, args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val cm = context.getSystemService(CarrierConfigManager::class.java)
        val config = cm.getConfigForSubId(subId)
        val values = Bundle()
        if (config != null) {
            val keys = args.getStringArray(BUNDLE_KEYS) ?: emptyArray()
            for (key in keys) {
                putValue(values, key, config.get(key))
            }
        }
        ok().apply { putBundle(BUNDLE_VALUES, values) }
    } catch (t: Throwable) {
        Log.e(TAG, "readCarrierConfig failed", t)
        fail(t)
    }

    fun dumpCarrierConfig(context: Context, args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val cm = context.getSystemService(CarrierConfigManager::class.java)
        val config = cm.getConfigForSubId(subId)
        val text = if (config == null) "" else buildDumpText(config)
        ok().apply { putString(BUNDLE_DUMP_TEXT, text) }
    } catch (t: Throwable) {
        Log.e(TAG, "dumpCarrierConfig failed", t)
        fail(t)
    }

    private fun putValue(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is IntArray -> bundle.putIntArray(key, value)
            is LongArray -> bundle.putLongArray(key, value)
            is DoubleArray -> bundle.putDoubleArray(key, value)
            is BooleanArray -> bundle.putBoolean(key, value)
            is Array<*> -> if (value.isArrayOf<String>()) {
                @Suppress("UNCHECKED_CAST")
                bundle.putStringArray(key, value as Array<String>)
            }
        }
    }

    private fun buildDumpText(config: PersistableBundle): String {
        val fields =
            listOf(CarrierConfigManager::class.java, *CarrierConfigManager::class.java.declaredClasses)
                .flatMap { it.declaredFields.asList() }
                .filter { it.name != "KEY_PREFIX" && it.name.startsWith("KEY_") }
                .sortedBy { it.name }

        val lines = fields.map { field ->
            val key = try {
                field.get(field) as String
            } catch (_: Throwable) {
                null
            }
            if (key == null) {
                "${field.name}: (invalid)"
            } else {
                val value = config.get(key)
                "${field.name}: ${valueToString(value)}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun valueToString(value: Any?): String {
        return when (value) {
            null -> "null"
            is IntArray -> value.joinToString(",")
            is LongArray -> value.joinToString(",")
            is DoubleArray -> value.joinToString(",")
            is BooleanArray -> value.joinToString(",")
            is Array<*> -> value.joinToString(",")
            else -> value.toString()
        }
    }

    // -------------------------------------------------------- config.override

    fun overrideConfig(context: Context, args: Bundle): Bundle = try {
        val cm = context.getSystemService(CarrierConfigManager::class.java)

        val valuesBundle = Bundle(args)
        val selectedSubId = valuesBundle.getInt(BUNDLE_SELECT_SIM_ID, -1)
        valuesBundle.remove(BUNDLE_SELECT_SIM_ID)
        val reset = valuesBundle.getBoolean(BUNDLE_RESET, false)
        valuesBundle.remove(BUNDLE_RESET)
        val preferPersistent = valuesBundle.getBoolean(BUNDLE_PREFER_PERSISTENT, false)
        valuesBundle.remove(BUNDLE_PREFER_PERSISTENT)
        val countryMccOverride = valuesBundle.getString(BUNDLE_COUNTRY_MCC_OVERRIDE)
        valuesBundle.remove(BUNDLE_COUNTRY_MCC_OVERRIDE)
        val countryMncHint = valuesBundle.getString(BUNDLE_COUNTRY_MNC_HINT)
        valuesBundle.remove(BUNDLE_COUNTRY_MNC_HINT)

        val subIds = activeSubIds(context, selectedSubId)
        val baseValues = if (reset) null else toPersistableBundle(valuesBundle)
        for (subId in subIds) {
            val values = baseValues?.let { PersistableBundle(it) }
            Log.i(TAG, "overrideConfig for subId $subId with values $values")
            applyOverrideConfig(cm, subId, values, preferPersistent)
            if (reset) {
                clearCarrierTestOverride(context, subId)
            } else if (!countryMccOverride.isNullOrBlank()) {
                applyCarrierTestMccOverride(context, subId, countryMccOverride, countryMncHint)
            }
        }
        ok()
    } catch (t: Throwable) {
        Log.e(TAG, "overrideConfig failed", t)
        fail(t)
    }

    @Throws(Exception::class)
    private fun applyOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        preferPersistent: Boolean,
    ) {
        if (!preferPersistent) {
            invokeOverrideConfig(cm, subId, values, persistent = false)
            return
        }
        try {
            invokeOverrideConfig(cm, subId, values, persistent = true)
            Log.i(TAG, "overrideConfig persistent success for subId $subId")
        } catch (persistentError: Throwable) {
            Log.w(
                TAG,
                "overrideConfig persistent failed for subId $subId, fallback to non-persistent",
                persistentError
            )
            try {
                invokeOverrideConfig(cm, subId, values, persistent = false)
                Log.i(TAG, "overrideConfig fallback non-persistent success for subId $subId")
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(persistentError)
                throw fallbackError
            }
        }
    }

    @Throws(Exception::class)
    private fun invokeOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
    ) {
        try {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(cm, subId, values, persistent)
        } catch (_: NoSuchMethodException) {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java
            ).invoke(cm, subId, values)
        }
    }

    @Throws(Exception::class)
    private fun applyCarrierTestMccOverride(
        context: Context,
        subId: Int,
        mccOverrideRaw: String,
        mncHintRaw: String?,
    ) {
        val normalizedMcc = mccOverrideRaw.filter { it.isDigit() }.take(3)
        if (normalizedMcc.length != 3) {
            Log.w(TAG, "skip carrier test override: invalid MCC=$mccOverrideRaw")
            return
        }
        val normalizedMnc = mncHintRaw
            ?.filter { it.isDigit() }
            ?.take(3)
            ?.takeIf { it.length in 2..3 }
            ?: resolveCurrentMnc(context, subId)
            ?: throw IllegalStateException("unable to resolve MNC for subId=$subId")
        val mccmnc = normalizedMcc + normalizedMnc
        Log.i(TAG, "setCarrierTestOverride for subId=$subId mccmnc=$mccmnc")
        telephony().setCarrierTestOverride(subId, mccmnc, "", "", "", "", "", "", null, null)
    }

    @Throws(Exception::class)
    private fun clearCarrierTestOverride(context: Context, subId: Int) {
        val telephony = telephony()

        val clearMethod = runCatching {
            telephony.javaClass.getMethod("clearCarrierTestOverride", Int::class.javaPrimitiveType)
        }.getOrNull()

        if (clearMethod != null) {
            Log.i(TAG, "clearCarrierTestOverride for subId=$subId")
            clearMethod.invoke(telephony, subId)
            return
        }

        val currentMccMnc = resolveActiveSubscriptionMccMnc(context, subId)
        if (currentMccMnc.isNullOrBlank()) {
            Log.w(
                TAG,
                "clearCarrierTestOverride unavailable and unable to resolve MCCMNC for subId=$subId; skip fallback to avoid empty operator override"
            )
            return
        }

        Log.i(
            TAG,
            "clearCarrierTestOverride unavailable, fallback setCarrierTestOverride(current=$currentMccMnc) for subId=$subId"
        )
        telephony.setCarrierTestOverride(subId, currentMccMnc, "", "", "", "", "", "", null, null)
    }

    @SuppressLint("MissingPermission")
    private fun resolveActiveSubscriptionMccMnc(context: Context, subId: Int): String? {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val info =
            sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId } ?: return null

        val mcc = info.mccString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length == 3 }
            ?: info.mcc.takeIf { it in 0..999 }?.toString()?.padStart(3, '0')

        val mnc = info.mncString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length in 2..3 }
            ?: info.mnc.takeIf { it in 0..999 }?.toString()?.padStart(2, '0')

        return if (mcc != null && mnc != null) mcc + mnc else null
    }

    private fun resolveCurrentMnc(context: Context, subId: Int): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        val bySub = telephony.createForSubscriptionId(subId).simOperator.orEmpty()
        val operator = bySub.filter { it.isDigit() }
        if (operator.length >= 5) {
            return operator.substring(3)
        }
        val fallback = telephony.simOperator.orEmpty().filter { it.isDigit() }
        return if (fallback.length >= 5) fallback.substring(3) else null
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun toPersistableBundle(bundle: Bundle): PersistableBundle {
        val pb = PersistableBundle()
        for (key in bundle.keySet()) {
            when (val value = bundle.get(key)) {
                is Int -> pb.putInt(key, value)
                is Long -> pb.putLong(key, value)
                is Double -> pb.putDouble(key, value)
                is String -> pb.putString(key, value)
                is Boolean -> pb.putBoolean(key, value)
                is IntArray -> pb.putIntArray(key, value)
                is LongArray -> pb.putLongArray(key, value)
                is DoubleArray -> pb.putDoubleArray(key, value)
                is BooleanArray -> pb.putBooleanArray(key, value)
                is Array<*> -> if (value.isArrayOf<String>()) {
                    pb.putStringArray(key, value as Array<String>)
                } else {
                    Log.i(TAG, "toPersistableBundle: unsupported type for key $key")
                }
                else -> Log.i(TAG, "toPersistableBundle: unsupported type for key $key")
            }
        }
        return pb
    }

    // ------------------------------------------------------------- ims.status

    fun readImsStatus(args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SELECT_SIM_ID, -1)
        require(subId >= 0) { "invalid subId" }
        val registered = telephony().isImsRegistered(subId)
        Log.i(TAG, "IMS registration status: subId=$subId registered=$registered")
        ok().apply { putBoolean(BUNDLE_STATUS_VALUE, registered) }
    } catch (t: Throwable) {
        Log.e(TAG, "readImsStatus failed", t)
        fail(t)
    }

    // -------------------------------------------------------------- ims.reset

    fun resetIms(context: Context, args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val subIds = activeSubIds(context, subId)
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val telephony = telephony()
        for (id in subIds) {
            val slotIndex = sm.javaClass
                .getMethod("getSlotIndex", Int::class.javaPrimitiveType)
                .invoke(sm, id) as Int
            Log.i(TAG, "resetIms for subId $id slot $slotIndex")
            telephony.resetIms(slotIndex)
        }
        ok()
    } catch (t: Throwable) {
        Log.e(TAG, "resetIms failed", t)
        fail(t)
    }

    // ---------------------------------------------------------------- captive

    fun captivePortal(context: Context, args: Bundle): Bundle = try {
        val resolver = context.contentResolver
        val result = ok()
        when (args.getString(BUNDLE_ACTION)?.takeIf { it.isNotBlank() }
            ?: CAPTIVE_ACTION_APPLY_CN) {
            CAPTIVE_ACTION_APPLY_CN -> {
                val setHttp =
                    Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL, CN_HTTP_URL)
                val setHttps =
                    Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL, CN_HTTPS_URL)
                val currentHttp =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                val currentHttps =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                fillUrlState(result, currentHttp, currentHttps)
                val verified =
                    setHttp && setHttps && currentHttp == CN_HTTP_URL && currentHttps == CN_HTTPS_URL
                if (!verified) {
                    throw IllegalStateException(
                        "verify failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                    )
                }
            }

            CAPTIVE_ACTION_RESTORE_DEFAULT -> {
                val setHttp = Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL, null)
                val setHttps =
                    Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL, null)
                val currentHttp =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                val currentHttps =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                fillUrlState(result, currentHttp, currentHttps)
                val verified = setHttp && setHttps && currentHttp.isBlank() && currentHttps.isBlank()
                if (!verified) {
                    throw IllegalStateException(
                        "restore failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                    )
                }
            }

            CAPTIVE_ACTION_QUERY -> {
                val currentHttp =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                val currentHttps =
                    Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                fillUrlState(result, currentHttp, currentHttps)
            }

            else -> throw IllegalArgumentException("unsupported captive action")
        }
        result
    } catch (t: Throwable) {
        Log.e(TAG, "captivePortal failed", t)
        fail(t)
    }

    private fun fillUrlState(result: Bundle, currentHttp: String, currentHttps: String) {
        result.putString(BUNDLE_HTTP_URL, currentHttp)
        result.putString(BUNDLE_HTTPS_URL, currentHttps)
        result.putBoolean(BUNDLE_IS_CN_URL, currentHttp == CN_HTTP_URL && currentHttps == CN_HTTPS_URL)
        result.putBoolean(BUNDLE_IS_OVERRIDDEN, currentHttp.isNotBlank() || currentHttps.isNotBlank())
    }

    // -------------------------------------------------------------- apn.apply

    fun applyApn(context: Context, args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val name = args.getString(BUNDLE_NAME).orEmpty().trim()
        val apn = args.getString(BUNDLE_APN).orEmpty().trim()
        val type = args.getString(BUNDLE_TYPE).orEmpty().trim().ifBlank { "default,supl,ims" }
        val mcc = args.getString(BUNDLE_MCC).orEmpty().filter { it.isDigit() }.take(3)
        val mnc = args.getString(BUNDLE_MNC).orEmpty().filter { it.isDigit() }.take(3)
        require(subId >= 0) { "invalid subId" }
        require(name.isNotBlank()) { "APN name is blank" }
        require(apn.isNotBlank()) { "APN is blank" }
        require(mcc.length == 3) { "MCC must be 3 digits" }
        require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }

        val numeric = mcc + mnc
        val values = ContentValues().apply {
            put("name", name)
            put("apn", apn)
            put("type", type)
            put("mcc", mcc)
            put("mnc", mnc)
            put("numeric", numeric)
            put("protocol", "IPV4V6")
            put("roaming_protocol", "IPV4V6")
            put("carrier_enabled", 1)
            put("edited", 1)
            put("sub_id", subId)
        }

        val existingId = findExistingApnId(context, subId, numeric, apn, type)
        val apnId = if (existingId != null) {
            val updated = context.contentResolver.update(
                Uri.withAppendedPath(APN_URI, existingId.toString()),
                values,
                null,
                null
            )
            if (updated <= 0) throw IllegalStateException("update APN failed")
            existingId
        } else {
            val inserted = context.contentResolver.insert(APN_URI, values)
                ?: throw IllegalStateException("insert APN failed")
            inserted.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("invalid APN id: $inserted")
        }
        val preferValues = ContentValues().apply { put("apn_id", apnId) }
        val preferredUpdated = context.contentResolver.update(
            Uri.parse("$PREFER_APN_URI_PREFIX$subId"),
            preferValues,
            null,
            null
        )
        if (preferredUpdated <= 0) throw IllegalStateException("set preferred APN failed")
        Log.i(TAG, "APN applied for subId=$subId id=$apnId name=$name apn=$apn")
        ok()
    } catch (t: Throwable) {
        Log.e(TAG, "applyApn failed", t)
        fail(t)
    }

    private fun findExistingApnId(
        context: Context,
        subId: Int,
        numeric: String,
        apn: String,
        type: String,
    ): Long? {
        val queries = listOf(
            "numeric=? AND apn=? AND type=? AND sub_id=?" to arrayOf(numeric, apn, type, subId.toString()),
            "numeric=? AND apn=? AND type=?" to arrayOf(numeric, apn, type),
        )
        for ((selection, args) in queries) {
            val existing = runCatching {
                context.contentResolver.query(APN_URI, arrayOf("_id"), selection, args, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            }.getOrNull()
            if (existing != null) return existing
        }
        return null
    }

    // ------------------------------------------------------- persistent_volte

    fun persistentVolte(context: Context, args: Bundle): Bundle = try {
        val subId = args.getInt(BUNDLE_SUB_ID, -1)
        require(subId >= 0) { "invalid subId" }
        val settings = PersistentVolteSettings(context, subId)
        val result = ok()
        when (args.getString(BUNDLE_ACTION)) {
            PV_ACTION_QUERY -> {
                val original = settings.readOriginal()
                result.putInt(BUNDLE_OPT_IN, original.optIn)
                result.putInt(BUNDLE_USER_ENABLED, original.userSetting)
            }

            PV_ACTION_ENABLE -> {
                settings.enable()
                result.putInt(BUNDLE_OPT_IN, 1)
                result.putInt(BUNDLE_USER_ENABLED, 1)
            }

            PV_ACTION_RESTORE -> {
                val values = VolteOriginalValues(
                    args.getInt(BUNDLE_OPT_IN, -1),
                    args.getInt(BUNDLE_USER_ENABLED, -1),
                )
                settings.restore(values)
                result.putInt(BUNDLE_OPT_IN, values.optIn)
                result.putInt(BUNDLE_USER_ENABLED, values.userSetting)
            }

            else -> throw IllegalArgumentException("unknown persistent_volte action")
        }
        result
    } catch (t: Throwable) {
        Log.e(TAG, "persistentVolte failed", t)
        fail(t)
    }
}
