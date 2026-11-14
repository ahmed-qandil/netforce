package com.netforce.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Persistent VoLTE (experimental): query / enable / restore the system IMS
 * opt-in so VoLTE stays active across reboots without Shizuku.
 * Runs as an Instrumentation under Shizuku shell permission delegation.
 */
class PersistentVolteModifier : Instrumentation() {
    companion object {
        private const val TAG = "PersistentVolte"
        const val BUNDLE_SUB_ID = "sub_id"
        const val BUNDLE_ACTION = "action"
        const val ACTION_QUERY = "query"
        const val ACTION_ENABLE = "enable"
        const val ACTION_RESTORE = "restore"
        const val BUNDLE_OPT_IN = "opt_in"
        const val BUNDLE_USER_ENABLED = "user_enabled"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val result = Bundle()
        try {
            if (arguments == null) error("missing arguments")
            if (!waitForShizukuBinderReady()) error("shizuku binder is not ready")

            val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
            val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            try {
                val subId = arguments.getInt(BUNDLE_SUB_ID, -1)
                require(subId >= 0) { "invalid subId" }
                val settings = PersistentVolteSettings(targetContext, subId)
                when (arguments.getString(BUNDLE_ACTION)) {
                    ACTION_QUERY -> {
                        val original = settings.readOriginal()
                        result.putInt(BUNDLE_OPT_IN, original.optIn)
                        result.putInt(BUNDLE_USER_ENABLED, original.userSetting)
                    }
                    ACTION_ENABLE -> {
                        settings.enable()
                        result.putInt(BUNDLE_OPT_IN, 1)
                        result.putInt(BUNDLE_USER_ENABLED, 1)
                    }
                    ACTION_RESTORE -> {
                        val values = VolteOriginalValues(
                            arguments.getInt(BUNDLE_OPT_IN, -1),
                            arguments.getInt(BUNDLE_USER_ENABLED, -1),
                        )
                        settings.restore(values)
                        result.putInt(BUNDLE_OPT_IN, values.optIn)
                        result.putInt(BUNDLE_USER_ENABLED, values.userSetting)
                    }
                    else -> error("unknown action")
                }
                result.putBoolean(BUNDLE_RESULT, true)
            } finally {
                am.stopDelegateShellPermissionIdentityCompat()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "persistent volte operation failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, result)
    }
}
