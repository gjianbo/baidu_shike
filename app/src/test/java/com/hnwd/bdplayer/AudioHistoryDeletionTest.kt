package com.hnwd.bdplayer

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AudioHistoryDeletionTest {
    private val entry = PanEntry("group", "message", "owner", "file", "lesson.mp3", "", "", false)
    private val other = entry.copy(fileId = "other")

    @Test
    fun deletingOneLessonStopsItsCurrentRecordingAndAllowsReopening() {
        val store = AudioHistory(MemoryPreferences())
        val history = store.scoped("account")
        val current = history.begin(entry)
        current.save(120, false, 600)
        history.begin(other).save(45, false, 600)
        store.scoped("other-account").begin(entry).save(60, false, 600)

        history.remove(entry)
        current.save(125, false, 600)

        assertFalse(AudioHistory.canResumeRecording(current.id))
        assertNull(history.find(entry))
        assertEquals(45, history.find(other)?.seconds)
        assertEquals(60, store.scoped("other-account").find(entry)?.seconds)

        history.begin(entry).save(5, false, 600)
        assertEquals(5, history.find(entry)?.seconds)
    }

    @Test
    fun deletingVideoRemovesBothResumeKeysAndRejectsLateSave() {
        val progress = MemoryPreferences()
        val history = AudioHistory(MemoryPreferences(), "account")
        val video = entry.copy(name = "lesson.mp4")
        val recording = history.begin(video)
        val legacy = AccountScope.key("group:message:file", "account")
        val current = AccountScope.key(entryKey(video), "account")
        progress.edit().putLong(legacy, 90_000L).apply()
        assertEquals(90_000L, recording.videoPosition(progress))
        recording.saveVideo(progress, 120_000L, false, 600)
        progress.edit().putLong(legacy, 90_000L).apply()

        history.remove(video, progress)
        recording.saveVideo(progress, 125_000L, false, 600)

        assertNull(history.find(video))
        assertFalse(progress.contains(current))
        assertFalse(progress.contains(legacy))
        assertEquals(0L, history.begin(video).videoPosition(progress))
    }

    @Test
    fun clearingHistoryStopsExistingRecordingsOnlyForThatAccount() {
        val store = AudioHistory(MemoryPreferences())
        val history = store.scoped("account")
        val current = history.begin(entry)
        val otherAccount = store.scoped("other-account").begin(other)
        current.save(120, false, 600)

        history.clear()
        current.save(125, false, 600)
        otherAccount.save(60, false, 600)

        assertNull(history.find(entry))
        assertEquals(60, store.scoped("other-account").find(other)?.seconds)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll() = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun contains(key: String) = key in values
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = error("Unexpected set read")
        override fun getInt(key: String, defValue: Int) = error("Unexpected int read")
        override fun getFloat(key: String, defValue: Float) = error("Unexpected float read")
        override fun getBoolean(key: String, defValue: Boolean) = error("Unexpected boolean read")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }

        override fun edit() = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun putStringSet(key: String, values: MutableSet<String>?) = error("Unexpected set write")
            override fun putInt(key: String, value: Int) = error("Unexpected int write")
            override fun putFloat(key: String, value: Float) = error("Unexpected float write")
            override fun putBoolean(key: String, value: Boolean) = error("Unexpected boolean write")
            override fun clear() = error("Unexpected preferences clear")
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                    listeners.forEach { it.onSharedPreferenceChanged(this@MemoryPreferences, key) }
                }
            }
        }
    }
}
