package com.jmtditech.groupme.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Prefs(private val ctx: Context) {
    companion object {
        const val MODE_INSTANT = "instant"
        const val MODE_PERIODIC = "periodic"
        const val MODE_OFF = "off"
    }

    private object K {
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val clientId = stringPreferencesKey("client_id")
        val notifications = booleanPreferencesKey("notifications")
        val service = booleanPreferencesKey("service")
        val bgMode = stringPreferencesKey("bg_mode")
        val updates = booleanPreferencesKey("auto_updates")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
    }

    // In-memory copies so the HTTP interceptor and receivers can read them synchronously.
    @Volatile var token: String? = null
        private set
    @Volatile var userId: String = ""
        private set
    @Volatile var userName: String = ""
        private set

    init {
        runBlocking {
            val p = ctx.dataStore.data.first()
            token = p[K.token]
            userId = p[K.userId] ?: ""
            userName = p[K.userName] ?: ""
        }
    }

    val signedIn: Flow<Boolean> = ctx.dataStore.data
        .map { !it[K.token].isNullOrBlank() && !it[K.userId].isNullOrBlank() }
        .distinctUntilChanged()
    val notificationsEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[K.notifications] ?: true }
    /**
     * How messages arrive while the app is closed: [MODE_INSTANT] (foreground service with a persistent
     * notification), [MODE_PERIODIC] (WorkManager, about every 15 minutes, no persistent notification) or [MODE_OFF].
     * Falls back to the older on/off flag if the user had set it.
     */
    val bgMode: Flow<String> = ctx.dataStore.data.map { p ->
        p[K.bgMode] ?: when (p[K.service]) {
            false -> MODE_OFF
            true -> MODE_INSTANT
            else -> MODE_PERIODIC
        }
    }
    val serviceEnabled: Flow<Boolean> = bgMode.map { it == MODE_INSTANT }
    val updatesEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[K.updates] ?: true }
    val clientId: Flow<String> = ctx.dataStore.data.map { it[K.clientId] ?: "" }

    fun setTokenTemp(t: String?) { token = t }

    suspend fun saveSession(t: String, id: String, name: String) {
        ctx.dataStore.edit {
            it[K.token] = t
            it[K.userId] = id
            it[K.userName] = name
        }
        token = t; userId = id; userName = name
    }

    suspend fun clearSession() {
        ctx.dataStore.edit {
            it.remove(K.token); it.remove(K.userId); it.remove(K.userName)
        }
        token = null; userId = ""; userName = ""
    }

    suspend fun setNotifications(v: Boolean) { ctx.dataStore.edit { it[K.notifications] = v } }
    suspend fun setBgMode(mode: String) { ctx.dataStore.edit { it[K.bgMode] = mode } }
    suspend fun bgModeNow(): String = bgMode.first()
    suspend fun setUpdatesEnabled(v: Boolean) { ctx.dataStore.edit { it[K.updates] = v } }
    suspend fun updatesEnabledNow(): Boolean = updatesEnabled.first()
    suspend fun lastUpdateCheck(): Long = ctx.dataStore.data.first()[K.lastUpdateCheck] ?: 0L
    suspend fun setLastUpdateCheck(v: Long) { ctx.dataStore.edit { it[K.lastUpdateCheck] = v } }
    suspend fun setClientId(v: String) { ctx.dataStore.edit { it[K.clientId] = v } }
    suspend fun notificationsEnabledNow(): Boolean = notificationsEnabled.first()
    suspend fun serviceEnabledNow(): Boolean = serviceEnabled.first()
}
