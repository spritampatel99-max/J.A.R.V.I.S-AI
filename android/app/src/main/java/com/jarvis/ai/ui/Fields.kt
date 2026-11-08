package com.jarvis.ai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/** One look for every text field in the app. */
@Composable
fun jarvisFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedContainerColor = colors.surfaceContainerLow,
        unfocusedContainerColor = colors.surfaceContainerLow,
        cursorColor = colors.primary,
        focusedLabelColor = colors.primary,
        unfocusedLabelColor = colors.onSurfaceVariant,
    )
}

/** Opens a web address in the user's browser. Does nothing if the phone has no browser. */
internal fun openWebPage(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser installed: nothing sensible to do.
    }
}
