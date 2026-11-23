package com.jarvis.ai.android.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jarvis.ai.data.SettingsStore
import com.jarvis.ai.history.Conversation
import com.jarvis.ai.history.ConversationExport
import com.jarvis.ai.history.ConversationStore
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Saves conversations as Markdown text files in a place the person picks with Android's own file chooser, so they show up in the file
 * manager. No storage permission is involved: Android only gives access to the one file or folder the person chose.
 *
 * Auto-save keeps one file per day (`jarvis-chat-YYYY-MM-DD.md`) in the chosen folder and rewrites it a moment after the history
 * changes. The text is the history's, which has already had pasted keys removed.
 */
class ChatArchiver(
    private val context: Context,
    private val settings: SettingsStore,
    private val history: ConversationStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private val status = MutableStateFlow<String?>(null)

    /** A one-line result of the last automatic save, for the Logs screen. */
    val message: StateFlow<String?> = status.asStateFlow()

    val folder: Uri? get() = settings.archiveFolder?.let { Uri.parse(it) }

    /** Call after anything is added to the history. Does nothing unless an auto-save folder is chosen. */
    fun onHistoryChanged() {
        if (settings.archiveFolder == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            saveToday()
        }
    }

    fun chooseFolder(tree: Uri) {
        settings.archiveFolder = tree.toString()
        status.value = "Saving to a folder you chose."
        onHistoryChanged()
    }

    fun stopAutoSave() {
        folder?.let {
            try {
                context.contentResolver.releasePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Nothing was held for it any more.
            }
        }
        settings.archiveFolder = null
        status.value = "Auto-save is off."
    }

    /** Writes every conversation to the file the person picked with "Save as". @return true when it was written. */
    fun writeAll(target: Uri): Boolean = write(target, history.conversations.value)

    private fun saveToday() {
        val tree = folder ?: return
        val atMs = now()
        val zoneId = zone()
        val conversations = ConversationExport.forDay(history.conversations.value, atMs, zoneId)
        if (conversations.isEmpty()) return
        val name = ConversationExport.dayFileName(atMs, zoneId)
        try {
            val file = findOrCreate(tree, name)
            status.value = if (write(file, conversations, atMs)) "Saved $name" else "Could not write $name."
        } catch (e: SecurityException) {
            // The person removed the folder's permission in Android's settings or the folder was deleted.
            settings.archiveFolder = null
            status.value = "Android no longer lets this app use that folder, so auto-save was turned off. Choose a folder again."
        } catch (e: IOException) {
            status.value = "Could not save: the folder is not available."
        } catch (e: IllegalArgumentException) {
            status.value = "Could not save: the folder is not available."
        }
    }

    private fun write(target: Uri, conversations: List<Conversation>, atMs: Long = now()): Boolean = try {
        val text = ConversationExport.markdown(conversations, zone(), atMs)
        // "wt" replaces the old text, so a day's file always holds the whole day.
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun findOrCreate(tree: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return DocumentsContract.createDocument(resolver, parent, "text/markdown", name) ?: throw IOException("The folder would not create the file.")
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}
