package com.netforce.ims

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.netforce.ims.model.BundleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Auto-restore (optional, default off): after a reboot, once Shizuku is running and
 * authorized, re-apply the last saved IMS configuration.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS = "netforce_auto_restore"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_BUNDLE = "last_bundle"
        private const val WAIT_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 2_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val saved = prefs.getString(KEY_LAST_BUNDLE, null) ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait for the Shizuku binder to come up after boot.
                var waited = 0L
                while (!Shizuku.pingBinder() && waited < WAIT_TIMEOUT_MS) {
                    delay(POLL_MS)
                    waited += POLL_MS
                }
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "Shizuku not available after boot; skipping auto-restore")
                    return@launch
                }
                if (Shizuku.checkSelfPermission() != Shizuku.PERMISSION_GRANTED) {
                    Log.w(TAG, "Shizuku permission not granted; skipping auto-restore")
                    return@launch
                }
                val bundle = BundleStore.deserialize(saved)
                if (bundle.isEmpty) {
                    Log.w(TAG, "saved bundle empty; skipping auto-restore")
                    return@launch
                }
                val error = ShizukuProvider.overrideImsConfig(context, bundle)
                if (error == null) {
                    Log.i(TAG, "auto-restore applied successfully")
                } else {
                    Log.e(TAG, "auto-restore failed: $error")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "auto-restore crashed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
