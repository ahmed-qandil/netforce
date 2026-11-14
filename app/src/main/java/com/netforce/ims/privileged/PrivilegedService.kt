package com.netforce.ims.privileged

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import com.netforce.ims.INetForceService

/**
 * Shizuku UserService entry point. Runs in a standalone process started by the
 * Shizuku server with shell uid, so privileged calls need no instrumentation and
 * no shell-permission delegation. This is what makes NetForce work on Android 10,
 * where INSTR_FLAG_NO_RESTART does not exist and self-instrumentation force-stops
 * the app's UI process.
 *
 * Constructor contract (rikka Shizuku UserService): a public no-arg constructor is
 * required; a Context-taking constructor (annotated @Keep so R8 does not strip it)
 * is used when available and receives a context of this app's package.
 */
class PrivilegedService() : INetForceService.Stub() {

    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context
    }

    companion object {
        private const val TAG = "PrivilegedService"

        const val OP_READ_SIM_LIST = "sim.read"
        const val OP_READ_CONFIG = "config.read"
        const val OP_DUMP_CONFIG = "config.dump"
        const val OP_OVERRIDE_CONFIG = "config.override"
        const val OP_IMS_STATUS = "ims.status"
        const val OP_RESET_IMS = "ims.reset"
        const val OP_CAPTIVE_PORTAL = "captive"
        const val OP_APPLY_APN = "apn.apply"
        const val OP_PERSISTENT_VOLTE = "persistent_volte"

        init {
            // This process directly invokes hidden framework members (ServiceManager,
            // ITelephony/ISub stubs, CarrierConfigManager internals). Lift hidden API
            // enforcement for it; best-effort, failures only degrade to op errors.
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
            }.onFailure { Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions failed", it) }
        }
    }

    private fun requireContext(): Context {
        serviceContext?.let { return it }
        // Fallback when the host Shizuku only uses the no-arg constructor: build a
        // package context ourselves so CarrierConfigManager overrides are recorded
        // under this app's package name, exactly like the app process would do.
        val atClass = Class.forName("android.app.ActivityThread")
        val activityThread = atClass.getMethod("systemMain").invoke(null)
        val systemContext = atClass.getMethod("getSystemContext").invoke(activityThread) as Context
        return systemContext.createPackageContext("com.netforce.ims", 0).also {
            serviceContext = it
        }
    }

    override fun execute(op: String?, args: Bundle?): Bundle {
        return try {
            val context = requireContext()
            val safeArgs = args ?: Bundle()
            when (op) {
                OP_READ_SIM_LIST -> PrivilegedOps.readSimList(context)
                OP_READ_CONFIG -> PrivilegedOps.readCarrierConfig(context, safeArgs)
                OP_DUMP_CONFIG -> PrivilegedOps.dumpCarrierConfig(context, safeArgs)
                OP_OVERRIDE_CONFIG -> PrivilegedOps.overrideConfig(context, safeArgs)
                OP_IMS_STATUS -> PrivilegedOps.readImsStatus(safeArgs)
                OP_RESET_IMS -> PrivilegedOps.resetIms(context, safeArgs)
                OP_CAPTIVE_PORTAL -> PrivilegedOps.captivePortal(context, safeArgs)
                OP_APPLY_APN -> PrivilegedOps.applyApn(context, safeArgs)
                OP_PERSISTENT_VOLTE -> PrivilegedOps.persistentVolte(context, safeArgs)
                else -> errorResult(IllegalArgumentException("unknown op: $op"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "execute failed for op=$op", t)
            errorResult(t)
        }
    }

    private fun errorResult(t: Throwable): Bundle = Bundle().apply {
        putBoolean(PrivilegedOps.BUNDLE_RESULT, false)
        putString(PrivilegedOps.BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }
}
