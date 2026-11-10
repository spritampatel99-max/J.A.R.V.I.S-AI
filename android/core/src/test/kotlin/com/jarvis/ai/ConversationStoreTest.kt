package com.jarvis.ai

import com.jarvis.ai.history.ConversationStore
import com.jarvis.ai.history.HistoryFilter
import com.jarvis.ai.history.HistoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {

    private class MemoryHistory(var text: String? = null, var failWrites: Boolean = false) : HistoryStorage {
        override fun read(): String? = text
        override fun write(text: String) {
            if (failWrites) throw java.io.IOException("disk full")
            this.text = text
        }
    }

    private val time = FakeTime(1_000L)
    private val disk = MemoryHistory()

    private fun store(redact: (String) -> String = { it }) = ConversationStore(disk, time, redact)

    @Test
    fun firstMessageStartsAConversationTitledFromIt() {
        val store = store()
        val id = store.append(null, "user", "Call mom\nthen something else")

        val c = store.conversations.value.single()
        assertEquals(id, c.id)
        assertEquals("Call mom", c.title)
        assertEquals(1, c.exchanges)
    }

    @Test
    fun laterMessagesJoinTheSameConversationAndItMovesToTheTop() {
        val store = store()
        val first = store.append(null, "user", "first")
        time.now += 10
        val second = store.append(null, "user", "second")
        time.now += 10
        store.append(first, "assistant", "reply", answeredBy = "Built-in command")

        val list = store.conversations.value
        assertEquals(listOf(first, second), list.map { it.id }, "the conversation that was touched last is first")
        assertEquals(2, list.first().messages.size)
        assertEquals("reply", list.first().lastReply)
        assertNotEquals(first, second)
    }

    @Test
    fun anUnknownConversationIdStartsANewOne() {
        val store = store()
        store.append("does-not-exist", "user", "hello")
        assertEquals(1, store.conversations.value.size)
        assertNotEquals("does-not-exist", store.conversations.value.single().id)
    }

    @Test
    fun secretsAreRedactedBeforeTheyAreStoredOrWritten() {
        val store = store { it.replace("sk-abc123", "[redacted]") }
        store.append(null, "user", "my key is sk-abc123")

        assertEquals("my key is [redacted]", store.conversations.value.single().messages.single().text)
        assertFalse(disk.text!!.contains("sk-abc123"))
    }

    @Test
    fun theDefaultRedactorRemovesAnApiKeyShapedString() {
        val store = ConversationStore(disk, time)
        store.append(null, "user", "use AIzaSyA1234567890abcdefghijklmnopqrstuvw for gemini")
        assertFalse(disk.text!!.contains("AIzaSyA1234567890abcdefghijklmnopqrstuvw"))
    }

    @Test
    fun historyIsReloadedFromStorage() {
        val first = store()
        val id = first.append(null, "user", "remember me", viaVoice = true)

        val second = store()
        val c = second.conversations.value.single()
        assertEquals(id, c.id)
        assertTrue(c.usedVoice)
    }

    @Test
    fun corruptStorageStartsEmptyInsteadOfCrashing() {
        disk.text = "{ this is not json"
        assertTrue(store().conversations.value.isEmpty())
    }

    @Test
    fun searchLooksInTitlesAndMessagesIgnoringCase() {
        val store = store()
        val a = store.append(null, "user", "Set an alarm")
        store.append(a, "assistant", "Alarm set for 7 AM")
        store.append(null, "user", "Weather today")

        assertEquals(listOf(a), store.find("ALARM").map { it.id })
        assertEquals(listOf(a), store.find("7 am").map { it.id })
        assertEquals(2, store.find("").size)
        assertTrue(store.find("nothing like this").isEmpty())
    }

    @Test
    fun filtersSelectVoiceAndActionConversations() {
        val store = store()
        val voice = store.append(null, "user", "spoken", viaVoice = true)
        val typed = store.append(null, "user", "typed")
        store.recordToolRun(typed)

        assertEquals(listOf(voice), store.find("", HistoryFilter.VOICE).map { it.id })
        assertEquals(listOf(typed), store.find("", HistoryFilter.ACTIONS).map { it.id })
        assertEquals(2, store.find("", HistoryFilter.ALL).size)
    }

    @Test
    fun deleteAndClearRemoveTheDataFromStorageToo() {
        val store = store()
        val a = store.append(null, "user", "alpha secret")
        store.append(null, "user", "beta")

        store.delete(a)
        assertEquals(1, store.conversations.value.size)
        assertFalse(disk.text!!.contains("alpha secret"))

        store.clear()
        assertTrue(store.conversations.value.isEmpty())
        assertEquals("[]", disk.text)
        assertEquals(2L, store.sizeBytes())
    }

    @Test
    fun sizeReflectsWhatIsStored() {
        val store = store()
        val empty = store.sizeBytes()
        store.append(null, "user", "x".repeat(500))
        assertTrue(store.sizeBytes() > empty + 500)
    }

    @Test
    fun thereAreCapsOnConversationsMessagesAndTextLength() {
        val store = store()
        repeat(ConversationStore.MAX_CONVERSATIONS + 5) { time.now += 1; store.append(null, "user", "c$it") }
        assertEquals(ConversationStore.MAX_CONVERSATIONS, store.conversations.value.size)

        val id = store.conversations.value.first().id
        repeat(ConversationStore.MAX_MESSAGES + 10) { store.append(id, "user", "m$it") }
        assertEquals(ConversationStore.MAX_MESSAGES, store.conversations.value.first { it.id == id }.messages.size)

        store.append(id, "assistant", "y".repeat(ConversationStore.MAX_TEXT + 100))
        assertEquals(ConversationStore.MAX_TEXT, store.conversations.value.first { it.id == id }.messages.last().text.length)
    }

    @Test
    fun aFailedSaveIsReportedAndTheNextSuccessfulOneClearsIt() {
        val store = store()
        disk.failWrites = true
        store.append(null, "user", "one")
        assertTrue(store.saveFailed)
        assertEquals(1, store.conversations.value.size, "the history stays usable in memory")

        disk.failWrites = false
        store.append(null, "user", "two")
        assertFalse(store.saveFailed)
        assertNotNull(disk.text)
        assertNull(null)
    }
}
