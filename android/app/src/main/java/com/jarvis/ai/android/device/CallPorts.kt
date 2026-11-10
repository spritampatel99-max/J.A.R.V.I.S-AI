package com.jarvis.ai.android.device

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.jarvis.ai.ai.tools.ContactEntry
import com.jarvis.ai.ai.tools.ContactNumber
import com.jarvis.ai.ai.tools.builtin.AnswerResult
import com.jarvis.ai.ai.tools.builtin.CallPort
import com.jarvis.ai.ai.tools.builtin.CallStart
import com.jarvis.ai.ai.tools.builtin.ContactSearch
import com.jarvis.ai.ai.tools.builtin.ContactsPort
import com.jarvis.ai.ai.tools.builtin.EndCallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Looks people up in the phone's contacts. Reads only the rows that match the name asked for. */
class AndroidContactsPort(context: Context) : ContactsPort {
    private val context = context.applicationContext

    override suspend fun search(query: String): ContactSearch = withContext(Dispatchers.IO) {
        if (!context.has(Manifest.permission.READ_CONTACTS)) return@withContext ContactSearch.PermissionMissing
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val names = LinkedHashMap<String, String>()
            val numbers = LinkedHashMap<String, MutableList<ContactNumber>>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && names.size <= MAX_CONTACTS) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val number = cursor.getString(2) ?: continue
                    val type = cursor.getInt(3)
                    val custom = cursor.getString(4)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, custom).toString()
                    names.putIfAbsent(id, name)
                    numbers.getOrPut(id) { mutableListOf() }.add(ContactNumber(number, label))
                }
            }
            ContactSearch.Found(names.map { (id, name) -> ContactEntry(id, name, numbers[id].orEmpty()) })
        } catch (e: SecurityException) {
            ContactSearch.PermissionMissing
        } catch (e: RuntimeException) {
            ContactSearch.Failed("The contacts could not be read (${e.javaClass.simpleName}).")
        }
    }

    private companion object {
        const val MAX_CONTACTS = 20
    }
}

/**
 * Starts, answers and ends calls through Android. Android does not report back whether a call connected, so every
 * result here only says what was asked of the system.
 */
class AndroidCallPort(context: Context) : CallPort {
    private val context = context.applicationContext

    override fun call(number: String, allowDirect: Boolean): CallStart {
        val direct = allowDirect && context.has(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (direct) CallStart.Placed else CallStart.DialerOpened
        } catch (e: ActivityNotFoundException) {
            CallStart.NoHandler
        } catch (e: SecurityException) {
            CallStart.Failed("Android refused to start the call.")
        }
    }

    @Suppress("MissingPermission")
    override fun answerRinging(): AnswerResult {
        if (!context.has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return AnswerResult.Failed("Allow \"Answer and end calls\" in the Prefs tab first.")
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return AnswerResult.Failed("This phone has no telephony service.")
        return try {
            telecom.acceptRingingCall()
            AnswerResult.Requested
        } catch (e: SecurityException) {
            AnswerResult.Failed("Android refused to answer the call.")
        }
    }

    @Suppress("MissingPermission")
    override fun endCall(): EndCallResult {
        if (!context.has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return EndCallResult.Failed("Allow \"Answer and end calls\" in the Prefs tab first.")
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return EndCallResult.Failed("This phone has no telephony service.")
        return try {
            if (telecom.endCall()) EndCallResult.Ended else EndCallResult.NoCall
        } catch (e: SecurityException) {
            EndCallResult.Failed("Android refused to end the call.")
        }
    }
}
