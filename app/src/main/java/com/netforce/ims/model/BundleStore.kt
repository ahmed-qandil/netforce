package com.netforce.ims.model

import android.os.Bundle

/**
 * Minimal serializer for the flat apply-Bundle (bool/int/string/int-array values only),
 * used to persist the last applied configuration for auto-restore after reboot.
 */
object BundleStore {
    fun serialize(bundle: Bundle): String {
        val sb = StringBuilder()
        for (key in bundle.keySet()) {
            when (val v = bundle.get(key)) {
                is Boolean -> sb.append(key).append("|B|").append(v)
                is Int -> sb.append(key).append("|I|").append(v)
                is String -> sb.append(key).append("|S|").append(
                    android.util.Base64.encodeToString(v.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                )
                is IntArray -> sb.append(key).append("|A|").append(v.joinToString(","))
                else -> continue
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun deserialize(text: String): Bundle {
        val bundle = Bundle()
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            val parts = line.split("|", limit = 3)
            if (parts.size != 3) continue
            val (key, type, raw) = parts
            try {
                when (type) {
                    "B" -> bundle.putBoolean(key, raw.toBooleanStrict())
                    "I" -> bundle.putInt(key, raw.toInt())
                    "S" -> bundle.putString(
                        key,
                        String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP), Charsets.UTF_8)
                    )
                    "A" -> bundle.putIntArray(
                        key,
                        raw.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toIntArray()
                    )
                }
            } catch (_: Exception) {
                // skip malformed entry
            }
        }
        return bundle
    }
}
