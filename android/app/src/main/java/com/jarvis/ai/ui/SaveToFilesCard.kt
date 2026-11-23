package com.jarvis.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.ai.android.files.ChatArchiver
import com.jarvis.ai.history.ConversationExport
import java.time.ZoneId

/** Logs-screen card: save conversations as text files in the person's own file manager. */
@Composable
fun SaveToFilesCard(archiver: ChatArchiver) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val auto by archiver.message.collectAsState()
    var folderSet by remember { mutableStateOf(archiver.folder != null) }
    var note by remember { mutableStateOf<String?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) note = if (archiver.writeAll(uri)) "Saved." else "Could not save the file."
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            archiver.chooseFolder(uri)
            folderSet = true
        }
    }

    GlassCard {
        Text("Save to your files", style = MaterialTheme.typography.titleSmall, color = JarvisColors.Ink)
        MutedText("Keeps your conversations with JARVIS as plain text files you can see in your file manager. Keys pasted into messages are removed before saving.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(
                "Save all chats as a file…",
                onClick = { saveAs.launch(ConversationExport.fileName(System.currentTimeMillis(), ZoneId.systemDefault(), "all")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton(if (folderSet) "Change auto-save folder" else "Auto-save to a folder…", onClick = { pickFolder.launch(null) }, subtle = true)
                if (folderSet) {
                    GlowButton("Stop auto-save", onClick = { archiver.stopAutoSave(); folderSet = false }, subtle = true)
                }
            }
        }
        if (folderSet) MutedText("Auto-save is on: one file per day in the folder you chose.")
        (note ?: auto)?.let { MutedText(it) }
    }
}
