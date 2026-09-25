package com.moontvplus.nativetv

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

class WatchStore(context: Context, private val api: MoonApi) {
    private val prefs = context.getSharedPreferences("watch_data", Context.MODE_PRIVATE)
    var storageType: String = "localstorage"
    private fun namespace(): String {
        val raw = "${api.baseUrl}|${api.username}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private fun prefKey(kind: String) = "${namespace()}:$kind"
    private fun local(kind: String): JSONObject = try { JSONObject(prefs.getString(prefKey(kind), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun setLocal(kind: String, value: JSONObject) { prefs.edit().putString(prefKey(kind), value.toString()).apply() }
    private val serverMode get() = storageType != "localstorage"

    suspend fun records(): JSONObject = if (serverMode) api.playRecords() else local("records")
    suspend fun favorites(): JSONObject = if (serverMode) api.favorites() else local("favorites")

    suspend fun saveRecord(key: String, value: JSONObject) {
        if (serverMode) api.saveRecord(key, value)
        else setLocal("records", local("records").put(key, value))
    }

    suspend fun saveFavorite(key: String, value: JSONObject) {
        if (serverMode) api.saveFavorite(key, value)
        else setLocal("favorites", local("favorites").put(key, value))
    }

    suspend fun deleteFavorite(key: String) {
        if (serverMode) api.deleteFavorite(key)
        else {
            val values = local("favorites")
            values.remove(key)
            setLocal("favorites", values)
        }
    }

    fun clearCurrentLocal() {
        prefs.edit().remove(prefKey("records")).remove(prefKey("favorites")).apply()
    }
}
