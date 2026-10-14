package com.today.td

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        } ?: return
        if (messages.isEmpty()) return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val address = messages.firstOrNull()?.displayOriginatingAddress
            ?: messages.firstOrNull()?.originatingAddress
            ?: ""
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, messages.first().timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        try {
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e("Today", "sms deliver", e)
        }
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Today", "mms received")
    }
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore("?").orEmpty()
        val message = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra("sms_body")
            ?: ""
        Thread {
            try {
                if (number.isNotBlank() && message.isNotBlank()) {
                    sendText(this, number, message)
                }
            } catch (e: Exception) {
                Log.e("Today", "respond sms", e)
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }
}

class ComposeSmsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF000000.toInt())
        }
        val number = EditText(this).apply {
            hint = "Number"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF71767B.toInt())
            setText(intent?.data?.schemeSpecificPart?.substringBefore("?").orEmpty())
        }
        val body = EditText(this).apply {
            hint = "Message"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF71767B.toInt())
            setText(intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            minLines = 4
        }
        val send = AppCompatButton(this).apply {
            text = "Send"
            setOnClickListener {
                val to = number.text?.toString()?.trim().orEmpty()
                val text = body.text?.toString()?.trim().orEmpty()
                if (to.isBlank() || text.isBlank()) return@setOnClickListener
                try {
                    sendText(this@ComposeSmsActivity, to, text)
                    Toast.makeText(this@ComposeSmsActivity, "Sent", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@ComposeSmsActivity, "Could not send", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(number)
        root.addView(body)
        root.addView(send)
        setContentView(root)
    }
}

private fun sendText(context: Context, number: String, message: String) {
    val manager = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getDefault()
    }
    val parts = manager.divideMessage(message)
    if (parts.size <= 1) {
        manager.sendTextMessage(number, null, message, null, null)
    } else {
        manager.sendMultipartTextMessage(number, null, parts, null, null)
    }
}

fun Activity.smsRoleIntent(): Intent? {
    if (Build.VERSION.SDK_INT >= 29) {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
            return roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
        }
    }
    return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
    }
}
