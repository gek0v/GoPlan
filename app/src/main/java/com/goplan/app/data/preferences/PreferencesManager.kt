package com.goplan.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class RecentExpansion(
    val workspaceId: String,
    val ideaId: String,
    val timestampMillis: Long
)

class PreferencesManager(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val ACTIVE_WORKSPACE = stringPreferencesKey("active_workspace")
        val RECENT_EXPANSION_WORKSPACE = stringPreferencesKey("recent_expansion_workspace")
        val RECENT_EXPANSION_IDEA_ID = stringPreferencesKey("recent_expansion_idea_id")
        val RECENT_EXPANSION_TIMESTAMP = longPreferencesKey("recent_expansion_timestamp")
    }

    suspend fun saveActiveWorkspace(workspaceId: String) {
        dataStore.edit { preferences ->
            preferences[ACTIVE_WORKSPACE] = workspaceId
        }
    }

    fun getActiveWorkspace(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[ACTIVE_WORKSPACE]
        }
    }

    suspend fun recordRecentExpansion(
        workspaceId: String,
        ideaId: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dataStore.edit { preferences ->
            preferences[RECENT_EXPANSION_WORKSPACE] = workspaceId
            preferences[RECENT_EXPANSION_IDEA_ID] = ideaId
            preferences[RECENT_EXPANSION_TIMESTAMP] = timestamp
        }
    }

    suspend fun getRecentExpansion(
        maxAgeMinutes: Long = 30,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): RecentExpansion? {
        val preferences = dataStore.data.first()
        val workspaceId = preferences[RECENT_EXPANSION_WORKSPACE]
        val ideaId = preferences[RECENT_EXPANSION_IDEA_ID]
        val timestamp = preferences[RECENT_EXPANSION_TIMESTAMP]

        if (workspaceId != null && ideaId != null && timestamp != null) {
            val ageMillis = currentTimeMillis - timestamp
            val maxAgeMillis = maxAgeMinutes * 60 * 1000L
            if (ageMillis in 0..maxAgeMillis) {
                return RecentExpansion(
                    workspaceId = workspaceId,
                    ideaId = ideaId,
                    timestampMillis = timestamp
                )
            }
        }
        return null
    }

    suspend fun clearRecentExpansion() {
        dataStore.edit { preferences ->
            preferences.remove(RECENT_EXPANSION_WORKSPACE)
            preferences.remove(RECENT_EXPANSION_IDEA_ID)
            preferences.remove(RECENT_EXPANSION_TIMESTAMP)
        }
    }
}
