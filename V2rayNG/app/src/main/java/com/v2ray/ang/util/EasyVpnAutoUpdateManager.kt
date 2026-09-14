package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Easy VPN - Auto Update Manager
 * App open වෙනකොටම background thread එකෙන් server configs auto-download කරනවා
 */
object EasyVpnAutoUpdateManager {
    private const val TAG = "EasyVPN"
    const val KEY_CONFIG_URL = "easy_vpn_config_url"
    const val DEFAULT_CONFIG_URL = "https://gist.githubusercontent.com/tharukanavod12345678-dev/938e791af1a182faa9ac1a0fc0dd2048/raw/300f244d3e298aa169c20b5b9f345bfd770de102/configs.json"
    const val KEY_LAST_UPDATE = "easy_vpn_last_update"
    const val KEY_AUTO_UPDATE_ENABLED = "easy_vpn_auto_update_enabled"

    fun getConfigUrl(): String = MmkvManager.decodeSettingsString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL) ?: DEFAULT_CONFIG_URL
    fun setConfigUrl(url: String) = MmkvManager.encodeSettings(KEY_CONFIG_URL, url)
    fun isAutoUpdateEnabled(): Boolean = MmkvManager.decodeSettingsBool(KEY_AUTO_UPDATE_ENABLED, true)
    fun setAutoUpdateEnabled(enabled: Boolean) = MmkvManager.encodeSettings(KEY_AUTO_UPDATE_ENABLED, enabled)
    fun getLastUpdateTime(): Long = MmkvManager.decodeSettingsLong(KEY_LAST_UPDATE, 0L)
    fun setLastUpdateTime(time: Long = System.currentTimeMillis()) = MmkvManager.encodeSettings(KEY_LAST_UPDATE, time)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun autoUpdateOnAppStart(context: Context) {
        if (!isAutoUpdateEnabled()) {
            Log.i(TAG, "Auto-update disabled")
            return
        }
        val lastUpdate = getLastUpdateTime()
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 3600000 && lastUpdate != 0L) {
            Log.i(TAG, "Skipping auto-update, recent")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Starting auto-update from ${getConfigUrl()}")
                val result = fetchConfigsFromServer()
                if (result.isSuccess) {
                    val configs = result.getOrNull() ?: emptyList()
                    if (configs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            try {
                                var imported = 0
                                for (config in configs) {
                                    try {
                                        val pair = AngConfigManager.importBatchConfig(config, "", false)
                                        // pair is Pair<Int,Int> -> first is count
                                        val count = pair.first
                                        if (count > 0) imported += count
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Import failed: ${e.message}")
                                    }
                                }
                                setLastUpdateTime()
                                Log.i(TAG, "Easy VPN: Auto-updated $imported servers from ${configs.size} configs")
                                try {
                                    context.toast("Easy VPN: Updated $imported servers")
                                } catch (_: Exception) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Apply failed: ${e.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "No configs found in server response")
                    }
                } else {
                    Log.e(TAG, "Auto-update fetch failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-update failed: ${e.message}")
            }
        }
    }

    suspend fun fetchConfigsFromServer(url: String = getConfigUrl()): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Fetching from $url")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EasyVPN/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            Log.i(TAG, "Downloaded ${body.length} chars")

            val configs = mutableListOf<String>()
            val trimmed = body.trim()

            try {
                when {
                    trimmed.startsWith("[") -> {
                        val jsonArray = JSONArray(trimmed)
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.get(i)
                            when (item) {
                                is String -> if (item.contains("://")) configs.add(item)
                                is JSONObject -> {
                                    val cfg = item.optString("config").takeIf { it.isNotEmpty() }
                                        ?: item.optString("url").takeIf { it.isNotEmpty() }
                                        ?: item.optString("link").takeIf { it.isNotEmpty() }
                                        ?: ""
                                    if (cfg.contains("://")) configs.add(cfg)
                                }
                            }
                        }
                    }
                    trimmed.startsWith("{") -> {
                        val jsonObj = JSONObject(trimmed)
                        val possibleKeys = listOf("servers", "configs", "data", "list", "items", "proxies", "v2ray")
                        var found = false
                        for (key in possibleKeys) {
                            if (jsonObj.has(key)) {
                                val arr = jsonObj.optJSONArray(key)
                                if (arr != null) {
                                    for (i in 0 until arr.length()) {
                                        val item = arr.get(i)
                                        if (item is String && item.contains("://")) {
                                            configs.add(item)
                                        } else if (item is JSONObject) {
                                            val cfg = item.optString("config").takeIf { it.isNotEmpty() }
                                                ?: item.optString("url").takeIf { it.isNotEmpty() }
                                                ?: item.optString("link").takeIf { it.isNotEmpty() }
                                                ?: ""
                                            if (cfg.contains("://")) configs.add(cfg)
                                        }
                                    }
                                    if (configs.isNotEmpty()) {
                                        found = true
                                        break
                                    }
                                }
                            }
                        }
                        if (!found) {
                            trimmed.split("\"").forEach { part ->
                                if (part.contains("://") && (part.startsWith("vless://") || part.startsWith("vmess://") || part.startsWith("trojan://") || part.startsWith("ss://"))) {
                                    configs.add(part)
                                }
                            }
                        }
                    }
                    else -> {
                        trimmed.lines().forEach { line ->
                            val l = line.trim()
                            if (l.contains("://")) configs.add(l)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error, trying plain text: ${e.message}")
                trimmed.lines().forEach { line ->
                    val l = line.trim()
                    if (l.contains("://")) configs.add(l)
                }
            }

            Log.i(TAG, "Parsed ${configs.size} configs")
            Result.success(configs)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            Result.failure(e)
        }
    }
}
