package com.sj.bkgtracker.data.local

import android.content.Context
import com.sj.bkgtracker.domain.model.LocationRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class AppSettingsTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var testDir: File
    private val json = Json { prettyPrint = false }

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        testDir = kotlin.io.path.createTempDirectory("test_cache").toFile()
        whenever(mockContext.filesDir).thenReturn(testDir)
    }

    @Test
    fun `test append and load single record`() {
        val record = createTestRecord(1)
        
        AppSettings.appendRecord(mockContext, record)
        val loaded = AppSettings.loadCache(mockContext)
        
        assertEquals(1, loaded.size)
        assertRecordsEqual(record, loaded[0])
    }

    @Test
    fun `test append multiple records individually`() {
        val records = (1..10).map { createTestRecord(it) }
        
        records.forEach { record ->
            AppSettings.appendRecord(mockContext, record)
        }
        
        val loaded = AppSettings.loadCache(mockContext)
        
        assertEquals(10, loaded.size)
        records.zip(loaded).forEach { (original, loadedRecord) ->
            assertRecordsEqual(original, loadedRecord)
        }
    }

    @Test
    fun `test data integrity after multiple appends`() {
        val record1 = LocationRecord(
            latitude = 18.5608443,
            longitude = 73.8009268,
            timestampMs = 1716533400000L,
            accuracyM = 18.2f,
            speedKmh = 25.5f,
            altitudeM = 506.3,
            bearingDeg = 45f
        )
        
        val record2 = LocationRecord(
            latitude = 18.561,
            longitude = 73.801,
            timestampMs = 1716533401000L,
            accuracyM = 15.5f,
            speedKmh = 30f,
            altitudeM = 510.0,
            bearingDeg = null  // Test null handling
        )
        
        AppSettings.appendRecord(mockContext, record1)
        AppSettings.appendRecord(mockContext, record2)
        
        val loaded = AppSettings.loadCache(mockContext)
        
        assertEquals(2, loaded.size)
        assertRecordsEqual(record1, loaded[0])
        assertRecordsEqual(record2, loaded[1])
        assertNull(loaded[1].bearingDeg)
    }

    @Test
    fun `test load from empty cache returns empty list`() {
        val loaded = AppSettings.loadCache(mockContext)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `test clear cache removes all records`() {
        val records = (1..5).map { createTestRecord(it) }
        records.forEach { AppSettings.appendRecord(mockContext, it) }
        
        assertEquals(5, AppSettings.loadCache(mockContext).size)
        
        AppSettings.clearCache(mockContext)
        
        assertTrue(AppSettings.loadCache(mockContext).isEmpty())
    }

    @Test
    fun `test saveCache with empty list clears file`() {
        val records = (1..3).map { createTestRecord(it) }
        records.forEach { AppSettings.appendRecord(mockContext, it) }
        
        AppSettings.saveCache(mockContext, emptyList())
        
        assertTrue(AppSettings.loadCache(mockContext).isEmpty())
    }

    @Test
    fun `test ndjson format - each record on separate line`() {
        val record = createTestRecord(1)
        AppSettings.appendRecord(mockContext, record)
        
        val cacheFile = File(testDir, "location_cache.ndjson")
        val content = cacheFile.readText()
        
        assertTrue(content.contains("\n"))
        assertTrue(content.trim().startsWith("{"))
        assertTrue(content.trim().endsWith("}"))
    }

    @Test
    fun `test handles corrupted lines gracefully`() {
        // Create file with one valid and one invalid line
        val cacheFile = File(testDir, "location_cache.ndjson")
        // Write proper NDJSON with one corrupted line in middle
        cacheFile.writeText(
            """{"lat":18.56,"lon":73.80,"timestampMs":123456,"accuracyM":5.0,"speedKmh":10.0,"altitudeM":500.0,"bearingDeg":45.0}
this is not valid json
{"lat":18.57,"lon":73.81,"timestampMs":123457,"accuracyM":6.0,"speedKmh":15.0,"altitudeM":510.0,"bearingDeg":null}
""".trimEnd()
        )
        
        val loaded = AppSettings.loadCache(mockContext)
        
        // Should load 2 valid records, skip 1 invalid
        assertEquals(2, loaded.size)
    }

    @Test
    fun `test getCacheFileSize returns correct size`() {
        assertEquals(0, AppSettings.getCacheFileSize(mockContext))
        
        val record = createTestRecord(1)
        AppSettings.appendRecord(mockContext, record)
        
        val size = AppSettings.getCacheFileSize(mockContext)
        assertTrue(size > 0)
    }

    private fun createTestRecord(index: Int): LocationRecord {
        return LocationRecord(
            latitude = 18.56 + index * 0.001,
            longitude = 73.80 + index * 0.001,
            timestampMs = 1716533400000L + index * 1000,
            accuracyM = 5f + index,
            speedKmh = 10f + index,
            altitudeM = 500.0 + index,
            bearingDeg = if (index % 2 == 0) index * 10f else null
        )
    }

    private fun assertRecordsEqual(expected: LocationRecord, actual: LocationRecord) {
        assertEquals("Latitude mismatch", expected.latitude, actual.latitude, 0.0001)
        assertEquals("Longitude mismatch", expected.longitude, actual.longitude, 0.0001)
        assertEquals("Timestamp mismatch", expected.timestampMs, actual.timestampMs)
        assertEquals("Accuracy mismatch", expected.accuracyM, actual.accuracyM, 0.01f)
        assertEquals("Speed mismatch", expected.speedKmh, actual.speedKmh, 0.01f)
        assertEquals("Altitude mismatch", expected.altitudeM, actual.altitudeM, 0.01)
        assertEquals("Bearing mismatch", expected.bearingDeg, actual.bearingDeg)
    }
}
