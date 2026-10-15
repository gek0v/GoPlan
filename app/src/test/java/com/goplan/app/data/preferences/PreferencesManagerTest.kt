package com.goplan.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tempFolder.newFile("test.preferences_pb") }
        )
        preferencesManager = PreferencesManager(dataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun getActiveWorkspace_initiallyReturnsNull() = runTest {
        val activeWorkspace = preferencesManager.getActiveWorkspace().first()
        assertNull(activeWorkspace)
    }

    @Test
    fun saveActiveWorkspace_persistsAndReturnsWorkspaceId() = runTest {
        preferencesManager.saveActiveWorkspace("workspace_123")

        val activeWorkspace = preferencesManager.getActiveWorkspace().first()
        assertEquals("workspace_123", activeWorkspace)
    }

    @Test
    fun getRecentExpansion_initiallyReturnsNull() = runTest {
        val recentExpansion = preferencesManager.getRecentExpansion()
        assertNull(recentExpansion)
    }

    @Test
    fun recordRecentExpansion_within30Minutes_returnsValidRecentExpansion() = runTest {
        val baseTime = 1_700_000_000_000L
        preferencesManager.recordRecentExpansion(
            workspaceId = "workspace_alpha",
            ideaId = "idea_001",
            timestamp = baseTime
        )

        // 10 minutes later (well within 30 min maxAge)
        val checkTime = baseTime + (10 * 60 * 1000L)
        val result = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = checkTime
        )

        assertNotNull(result)
        assertEquals("workspace_alpha", result?.workspaceId)
        assertEquals("idea_001", result?.ideaId)
        assertEquals(baseTime, result?.timestampMillis)
    }

    @Test
    fun getRecentExpansion_olderThan30Minutes_returnsNullDueToExpiration() = runTest {
        val baseTime = 1_700_000_000_000L
        preferencesManager.recordRecentExpansion(
            workspaceId = "workspace_alpha",
            ideaId = "idea_001",
            timestamp = baseTime
        )

        // 31 minutes later (older than 30 min limit)
        val checkTime = baseTime + (31 * 60 * 1000L)
        val result = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = checkTime
        )

        assertNull(result)
    }

    @Test
    fun getRecentExpansion_atExact30MinutesBoundary_returnsValidItem() = runTest {
        val baseTime = 1_700_000_000_000L
        preferencesManager.recordRecentExpansion(
            workspaceId = "workspace_beta",
            ideaId = "idea_002",
            timestamp = baseTime
        )

        // Exactly 30 minutes later (<= 30 minutes)
        val boundaryTime = baseTime + (30 * 60 * 1000L)
        val result = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = boundaryTime
        )

        assertNotNull(result)
        assertEquals("workspace_beta", result?.workspaceId)
        assertEquals("idea_002", result?.ideaId)
        assertEquals(baseTime, result?.timestampMillis)
    }

    @Test
    fun getRecentExpansion_at30MinutesPlusOneMillisecond_returnsNull() = runTest {
        val baseTime = 1_700_000_000_000L
        preferencesManager.recordRecentExpansion(
            workspaceId = "workspace_beta",
            ideaId = "idea_002",
            timestamp = baseTime
        )

        // 30 minutes + 1 ms later
        val overBoundaryTime = baseTime + (30 * 60 * 1000L) + 1L
        val result = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = overBoundaryTime
        )

        assertNull(result)
    }

    @Test
    fun clearRecentExpansion_removesExpansionKeysAndReturnsNull() = runTest {
        val baseTime = 1_700_000_000_000L
        preferencesManager.recordRecentExpansion(
            workspaceId = "workspace_gamma",
            ideaId = "idea_003",
            timestamp = baseTime
        )

        val beforeClear = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = baseTime
        )
        assertNotNull(beforeClear)

        preferencesManager.clearRecentExpansion()

        val afterClear = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = baseTime
        )
        assertNull(afterClear)
    }

    @Test
    fun recordRecentExpansion_overwritesPreviousExpansion() = runTest {
        val time1 = 1_700_000_000_000L
        val time2 = 1_700_000_060_000L

        preferencesManager.recordRecentExpansion("ws_1", "idea_1", time1)
        preferencesManager.recordRecentExpansion("ws_2", "idea_2", time2)

        val result = preferencesManager.getRecentExpansion(
            maxAgeMinutes = 30,
            currentTimeMillis = time2
        )

        assertNotNull(result)
        assertEquals("ws_2", result?.workspaceId)
        assertEquals("idea_2", result?.ideaId)
        assertEquals(time2, result?.timestampMillis)
    }
}
