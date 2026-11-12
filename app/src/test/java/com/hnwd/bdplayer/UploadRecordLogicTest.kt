package com.hnwd.bdplayer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRecordLogicTest {

    private fun record(
        id: Long,
        state: UploadRecordState = UploadRecordState.RUNNING,
        finishedAt: Long = 0,
    ) = UploadRecord(
        id = id,
        name = "第 $id 课.mp4",
        dir = "/课程/日语",
        sizeBytes = 9L * 1024 * 1024,
        createdAt = 1_000L + id,
        finishedAt = finishedAt,
        state = state,
        percent = 42,
        label = "上传中 2/3",
        message = "",
    )

    @Test
    fun encodeDecode_roundTripKeepsChineseNamesAndAllFields() {
        val original = listOf(record(1), record(2, UploadRecordState.SUCCESS, 5_000L))
        val decoded = UploadRecordLogic.decode(UploadRecordLogic.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun decode_skipsOnlyCorruptedItems() {
        val good = UploadRecordLogic.encode(listOf(record(1)))
        val array = JSONArray(good)
        array.put(org.json.JSONObject("{\"name\":\"缺 id\"}"))
        array.put("not an object")
        val decoded = UploadRecordLogic.decode(array.toString())
        assertEquals(1, decoded.size)
        assertEquals(1L, decoded[0].id)
        assertTrue(UploadRecordLogic.decode("not json").isEmpty())
    }

    @Test
    fun activeStates_coverQueuedAndRunningOnly() {
        assertTrue(UploadRecordLogic.isActive(record(1, UploadRecordState.QUEUED)))
        assertTrue(UploadRecordLogic.isActive(record(2, UploadRecordState.RUNNING)))
        assertFalse(UploadRecordLogic.isActive(record(3, UploadRecordState.SUCCESS)))
        assertFalse(UploadRecordLogic.isActive(record(4, UploadRecordState.FAILED)))
        assertTrue(UploadRecordLogic.isQueued(record(1, UploadRecordState.QUEUED)))
        assertFalse(UploadRecordLogic.isQueued(record(2, UploadRecordState.RUNNING)))
    }

    @Test
    fun trim_keepsAllActiveAndCapsHistory() {
        val active = listOf(
            record(1, UploadRecordState.RUNNING),
            record(2, UploadRecordState.QUEUED),
        )
        val history = (3..150).map { record(it.toLong(), UploadRecordState.SUCCESS, it.toLong()) }
        val trimmed = UploadRecordLogic.trim(active + history)
        assertEquals(2, trimmed.count(UploadRecordLogic::isActive))
        assertEquals(UploadRecordLogic.MAX_RECORDS, trimmed.size)
        assertTrue(trimmed.containsAll(active))
    }

    @Test
    fun reconcileInterrupted_onlyMarksOrphansWhenNoTaskRunning() {
        val list = listOf(
            record(1, UploadRecordState.RUNNING),
            record(2, UploadRecordState.QUEUED),
            record(3, UploadRecordState.SUCCESS, 3_000L),
            record(4, UploadRecordState.FAILED, 4_000L),
        )
        val running = UploadRecordLogic.reconcileInterrupted(list, true, 9_999L)
        assertFalse(running.changed)
        assertEquals(list, running.records)

        val idle = UploadRecordLogic.reconcileInterrupted(list, false, 9_999L)
        assertTrue(idle.changed)
        assertEquals(UploadRecordState.FAILED, idle.records[0].state)
        assertEquals(UploadRecordState.FAILED, idle.records[1].state)
        assertEquals(UploadRecordLogic.INTERRUPTED, idle.records[0].message)
        assertEquals(9_999L, idle.records[0].finishedAt)
        assertEquals(UploadRecordState.SUCCESS, idle.records[2].state)
        assertEquals(UploadRecordState.FAILED, idle.records[3].state)
    }
}
