package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The scrolling column every page that opens from Prefs uses. The tab bar is hidden there, so the bottom gap is small. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A text field with a length limit, in the app's one field style. */
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLength: Int = 200,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onChange(it) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.medium,
        colors = jarvisFieldColors(),
    )
}

/** One choice out of several, shown as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) GlassChip(label(option), onClick = { onSelect(option) }, selected = option == selected)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = JarvisColors.Coral) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A 24-hour time entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initialHour: Int, initialMinute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimeInput(state = state) },
    )
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = JarvisColors.Muted)
}

private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

/** "Tue 10 Nov, 18:30" in the phone's time zone. */
fun formatWhen(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    WHEN_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
