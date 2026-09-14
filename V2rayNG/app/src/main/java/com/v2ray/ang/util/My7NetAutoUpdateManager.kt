package com.v2ray.ang.util

import android.content.Context
import android.util.Log
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

object My7NetAutoUpdateManager {
    private const val TAG = "My7NetVPN"
    const val KEY_CONFIG_URL = "my7net_config_url"
    const val DEFAULT_CONFIG_URL = "https://gist.githubusercontent.com/tharukanavod12345678-dev/938e791af1a182faa9ac1a0fc0dd2048/raw/300f244d3e298aa169c20b5b9f345bfd770de102/configs.json"
    const val KEY_LAST_UPDATE = "my7net_last_update"
    const val KEY_AUTO_UPDATE_ENABLED = "my7net_auto_update_enabled"

    fun getConfigUrl(): String = MmkvManager.decodeSettingsString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL) ?: DEFAULT_CONFIG_URL
    fun setConfigUrl(url: String) = MmkvManager.encodeSettings(KEY_CONFIG_URL, url)
    fun isAutoUpdateEnabled(): Boolean = MmkvManager.decodeSettingsBool(KEY_AUTO_UPDATE_ENABLED, true)
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
                val result = fetchConfigsFromServer()
                if (result.isSuccess) {
                    val configs = result.getOrNull() ?: emptyList()
                    if (configs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            try {
                                var imported = 0
                                for (config in configs) {
                                    try {
                                        val count = AngConfigManager.importBatchConfig(config, "", false)
                                        if (count != null && count > 0) imported += count else imported++
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Import failed: ${e.message}")
                                    }
                                }
                                setLastUpdateTime()
                                Log.i(TAG, "My7Net: Auto-updated $imported servers")
                                Utils.showToastShort(context, "My7Net: Updated $imported servers")
                            } catch (e: Exception) {
                                Log.e(TAG, "Apply failed: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-update failed: ${e.message}")
            }
        }
    }

    suspend fun fetchConfigsFromServer(url: String = getConfigUrl()): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", "My7NetVPN/1.0").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty"))
            val configs = mutableListOf<String>()
            val trimmed = body.trim()
            try {
                when {
                    trimmed.startsWith("[") -> {
                        val arr = JSONArray(trimmed)
                        for (i in 0 until arr.length()) {
                            val item = arr.get(i)
                            if (item is String && item.contains("://")) configs.add(item)
                        }
                    }
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        val keys = listOf("servers", "configs", "data", "list")
                        for (key in keys) {
                            if (obj.has(key)) {
                                val arr = obj.optJSONArray(key)
                                if (arr != null) {
                                    for (i in 0 until arr.length()) {
                                        val it = arr.get(i)
                                        if (it is String && it.contains("://")) configs.add(it)
                                    }
                                    if (configs.isNotEmpty()) break
                                }
                            }
                        }
                    }
                    else -> {
                        trimmed.lines().forEach { line -> if (line.contains("://")) configs.add(line.trim()) }
                    }
                }
            } catch (e: Exception) {
                trimmed.lines().forEach { line -> if (line.contains("://")) configs.add(line.trim()) }
            }
            Result.success(configs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
