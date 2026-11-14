package com.netforce.ims

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.netforce.ims.model.SimSelection
import com.netforce.ims.model.ApnDraftConfig
import com.netforce.ims.privileged.ImsModifier
import com.netforce.ims.privileged.PrivilegedOps
import com.netforce.ims.privileged.PrivilegedService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.LSPass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class ShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        // Best-effort hidden API exemption; never crash process start on devices
        // where LSPass (LSPosed daemon) is unavailable.
        try {
            LSPass.setHiddenApiExemptions("")
        } catch (t: Throwable) {
            Log.w(TAG, "LSPass.setHiddenApiExemptions failed, trying HiddenApiBypass", t)
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t2: Throwable) {
                Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions failed", t2)
            }
        }
        return super.onCreate()
    }

    companion object {
        private const val TAG = "ShizukuProvider"
        private const val BIND_TIMEOUT_MS = 10_000L

        private val serviceMutex = Mutex()

        // Cached live binder to the privileged UserService process; null forces a rebind.
        @Volatile
        private var cachedService: INetForceService? = null

        @Volatile
        private var pendingBind: CompletableDeferred<INetForceService?>? = null

        data class CaptivePortalConfig(
            val httpUrl: String,
            val httpsUrl: String,
            val isCnUrl: Boolean,
            val isOverridden: Boolean,
        )

        // ---------------------------------------------------------- transport

        /**
         * Runs one op in the privileged UserService process (shell uid).
         *
         * On Android 10, self-instrumentation always force-stops this UI process
         * (INSTR_FLAG_NO_RESTART does not exist there), so all privileged work is
         * delegated to a Shizuku UserService instead. Returns the result Bundle,
         * or null when the service cannot be reached.
         */
        private suspend fun executePrivileged(
            context: Context,
            op: String,
            args: Bundle?,
        ): Bundle? {
            val service = bindPrivilegedService(context) ?: return null
            return withContext(Dispatchers.IO) {
                try {
                    service.execute(op, args)?.apply {
                        classLoader = PrivilegedOps::class.java.classLoader
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "privileged op $op failed at transport level", t)
                    cachedService = null
                    null
                }
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                val service = if (binder != null && binder.pingBinder()) {
                    INetForceService.Stub.asInterface(binder)
                } else null
                if (service == null) {
                    Log.w(TAG, "privileged service connected with dead binder")
                } else {
                    Log.i(TAG, "privileged service connected")
                }
                cachedService = service
                pendingBind?.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "privileged service disconnected")
                cachedService = null
            }
        }

        private fun newUserServiceArgs(context: Context): Shizuku.UserServiceArgs =
            Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PrivilegedService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)

        private suspend fun bindPrivilegedService(context: Context): INetForceService? {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "bindPrivilegedService: shizuku binder is not ready")
                return null
            }
            return serviceMutex.withLock {
                cachedService?.takeIf { it.asBinder().pingBinder() }?.let { return it }

                val deferred = CompletableDeferred<INetForceService?>()
                pendingBind = deferred
                try {
                    Shizuku.bindUserService(newUserServiceArgs(context), connection)
                } catch (t: Throwable) {
                    pendingBind = null
                    Log.e(TAG, "bindUserService failed to launch", t)
                    return null
                }
                val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                pendingBind = null
                if (service == null) {
                    Log.w(TAG, "bindUserService timed out or returned no binder")
                    runCatching {
                        Shizuku.unbindUserService(newUserServiceArgs(context), connection, true)
                    }
                }
                service
            }
        }

        // ------------------------------------------------------------ ops

        /** Returns null on success, an error message on failure. */
        suspend fun overrideImsConfig(context: Context, data: Bundle): String? {
            val args = Bundle(data)
            val result = executePrivileged(context, PrivilegedService.OP_OVERRIDE_CONFIG, args)
                ?: return "privileged service unavailable"
            if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                return null
            }
            return result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
        }

        suspend fun readSimInfoList(context: Context): List<SimSelection> {
            val result = executePrivileged(context, PrivilegedService.OP_READ_SIM_LIST, Bundle())
            if (result == null || !result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                Log.w(TAG, "readSimInfoList failed: ${result?.getString(PrivilegedOps.BUNDLE_RESULT_MSG)}")
                return emptyList()
            }
            @Suppress("DEPRECATION")
            val entries: ArrayList<Bundle> =
                result.getParcelableArrayList(PrivilegedOps.BUNDLE_SIM_LIST) ?: return emptyList()
            return entries.map { entry ->
                SimSelection(
                    entry.getInt(PrivilegedOps.SIM_SUB_ID),
                    entry.getString(PrivilegedOps.SIM_DISPLAY_NAME).orEmpty(),
                    entry.getString(PrivilegedOps.SIM_CARRIER_NAME).orEmpty(),
                    entry.getInt(PrivilegedOps.SIM_SLOT_INDEX),
                    countryIso = entry.getString(PrivilegedOps.SIM_COUNTRY_ISO).orEmpty(),
                    mcc = entry.getString(PrivilegedOps.SIM_MCC).orEmpty(),
                    mnc = entry.getString(PrivilegedOps.SIM_MNC).orEmpty(),
                    iccId = entry.getString(PrivilegedOps.SIM_ICC_ID).orEmpty(),
                )
            }
        }

        suspend fun readCarrierConfig(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): Bundle? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SELECT_SIM_ID, subId)
                putStringArray(PrivilegedOps.BUNDLE_KEYS, keys)
            }
            val result = executePrivileged(context, PrivilegedService.OP_READ_CONFIG, args)
            if (result == null || !result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                return null
            }
            return result.getBundle(PrivilegedOps.BUNDLE_VALUES)
        }

        suspend fun dumpCarrierConfig(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = executePrivileged(context, PrivilegedService.OP_DUMP_CONFIG, args)
            if (result == null || !result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                return null
            }
            return result.getString(PrivilegedOps.BUNDLE_DUMP_TEXT)
        }

        suspend fun restartImsRegistration(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = executePrivileged(context, PrivilegedService.OP_RESET_IMS, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun readImsRegistrationStatus(context: Context, subId: Int): Boolean? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = executePrivileged(context, PrivilegedService.OP_IMS_STATUS, args)
                ?: return null
            if (!result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                Log.w(
                    TAG,
                    "readImsRegistrationStatus failed for subId=$subId: " +
                        result.getString(PrivilegedOps.BUNDLE_RESULT_MSG)
                )
                return null
            }
            val value = result.getBoolean(PrivilegedOps.BUNDLE_STATUS_VALUE)
            Log.i(TAG, "readImsRegistrationStatus: subId=$subId registered=$value")
            return value
        }

        suspend fun updateCarrierConfigBoolean(
            context: Context,
            subId: Int,
            key: String,
            value: Boolean,
        ): String? {
            val appInfo = context.applicationInfo
            val canUsePersistentOverride =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val bundle = Bundle().apply {
                putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(key, value)
                putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistentOverride)
            }
            return overrideImsConfig(context, bundle)
        }

        suspend fun queryCaptivePortalConfig(context: Context): CaptivePortalConfig? {
            val args = Bundle().apply {
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.CAPTIVE_ACTION_QUERY)
            }
            val result = executePrivileged(context, PrivilegedService.OP_CAPTIVE_PORTAL, args)
            if (result == null || !result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                return null
            }
            return CaptivePortalConfig(
                httpUrl = result.getString(PrivilegedOps.BUNDLE_HTTP_URL).orEmpty(),
                httpsUrl = result.getString(PrivilegedOps.BUNDLE_HTTPS_URL).orEmpty(),
                isCnUrl = result.getBoolean(PrivilegedOps.BUNDLE_IS_CN_URL, false),
                isOverridden = result.getBoolean(PrivilegedOps.BUNDLE_IS_OVERRIDDEN, false)
            )
        }

        suspend fun applyCaptivePortalCnUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.CAPTIVE_ACTION_APPLY_CN)
            }
            val result = executePrivileged(context, PrivilegedService.OP_CAPTIVE_PORTAL, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun restoreCaptivePortalDefaultUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.CAPTIVE_ACTION_RESTORE_DEFAULT)
            }
            val result = executePrivileged(context, PrivilegedService.OP_CAPTIVE_PORTAL, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun applyApnConfig(
            context: Context,
            subId: Int,
            config: ApnDraftConfig,
        ): String? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SELECT_SIM_ID, subId)
                putString(PrivilegedOps.BUNDLE_NAME, config.name)
                putString(PrivilegedOps.BUNDLE_APN, config.apn)
                putString(PrivilegedOps.BUNDLE_TYPE, config.type)
                putString(PrivilegedOps.BUNDLE_MCC, config.mcc)
                putString(PrivilegedOps.BUNDLE_MNC, config.mnc)
            }
            val result = executePrivileged(context, PrivilegedService.OP_APPLY_APN, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        /** Returns Pair(optIn, userSetting) on success, null on failure. */
        suspend fun queryPersistentVolte(context: Context, subId: Int): Pair<Int, Int>? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SUB_ID, subId)
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.PV_ACTION_QUERY)
            }
            val result = executePrivileged(context, PrivilegedService.OP_PERSISTENT_VOLTE, args)
            if (result == null || !result.getBoolean(PrivilegedOps.BUNDLE_RESULT, false)) {
                return null
            }
            return result.getInt(PrivilegedOps.BUNDLE_OPT_IN, -1) to
                result.getInt(PrivilegedOps.BUNDLE_USER_ENABLED, -1)
        }

        suspend fun enablePersistentVolte(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SUB_ID, subId)
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.PV_ACTION_ENABLE)
            }
            val result = executePrivileged(context, PrivilegedService.OP_PERSISTENT_VOLTE, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun restorePersistentVolte(
            context: Context,
            subId: Int,
            optIn: Int,
            userEnabled: Int,
        ): String? {
            val args = Bundle().apply {
                putInt(PrivilegedOps.BUNDLE_SUB_ID, subId)
                putString(PrivilegedOps.BUNDLE_ACTION, PrivilegedOps.PV_ACTION_RESTORE)
                putInt(PrivilegedOps.BUNDLE_OPT_IN, optIn)
                putInt(PrivilegedOps.BUNDLE_USER_ENABLED, userEnabled)
            }
            val result = executePrivileged(context, PrivilegedService.OP_PERSISTENT_VOLTE, args)
                ?: return "privileged service unavailable"
            return if (result.getBoolean(PrivilegedOps.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(PrivilegedOps.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }
    }
}
