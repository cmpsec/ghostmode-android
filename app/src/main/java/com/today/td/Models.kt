package com.today.td

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val SEC_APPS = 1
const val SEC_FILES = 2
const val SEC_ACCOUNTS = 3
const val SEC_CHROME = 4
const val SEC_CLIP = 5
const val SEC_USAGE = 6
const val SEC_CALLS = 7
const val SEC_SMS = 8

enum class ClearState { CLEARED, NOT_CLEARED, UNKNOWN, INSTALLED_TODAY }
enum class Kind { APP, FILE, ACCOUNT, CALL, SMS }
enum class IconMode { APP, FILE, FOLDER, ACCOUNT, MEDIA }

data class AppEntry(
    val packageName: String,
    val label: String,
    val installedAt: Long,
    val source: String
)

data class FileEntry(
    val path: String,
    val name: String,
    val dir: Boolean,
    val size: Long,
    val time: Long,
    val parent: String,
    val hidden: Boolean = false
)

data class ChromeEntry(
    val title: String,
    val url: String,
    val visitedAt: Long
)

data class AccountEntry(
    val name: String,
    val type: String,
    val typeLabel: String,
    val authenticatorPackage: String?
) {
    val key: String get() = accountKey(name, type)
}

data class CallEntry(
    val id: Long,
    val title: String,
    val number: String,
    val typeLabel: String,
    val at: Long,
    val duration: String
)

data class SmsEntry(
    val id: Long,
    val address: String,
    val body: String,
    val at: Long,
    val box: String
)

data class UsageEntry(
    val packageName: String,
    val label: String,
    val lastUsed: Long,
    val foregroundMs: Long,
    val firstInstallTime: Long,
    val installedToday: Boolean,
    val clear: ClearState
)

data class FileScan(val list: List<FileEntry>, val truncated: Boolean)

data class UiState(
    val appsLoading: Boolean = true,
    val filesLoading: Boolean = true,
    val accountsLoading: Boolean = true,
    val usageLoading: Boolean = true,
    val callsLoading: Boolean = true,
    val smsLoading: Boolean = true,
    val busy: Boolean = false,
    val root: Boolean = false,
    val deviceAdmin: Boolean = false,
    val shell: Boolean = false,
    val fileAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val callAccess: Boolean = false,
    val smsAccess: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val filesTruncated: Boolean = false,
    val accounts: List<AccountEntry> = emptyList(),
    val usage: List<UsageEntry> = emptyList(),
    val calls: List<CallEntry> = emptyList(),
    val sms: List<SmsEntry> = emptyList(),
    val chromeInstalled: Boolean = false,
    val chrome: List<ChromeEntry> = emptyList(),
    val chromeLoading: Boolean = true,
    val selectedApps: Set<String> = emptySet(),
    val selectedFiles: Set<String> = emptySet(),
    val selectedAccounts: Set<String> = emptySet(),
    val selectedCalls: Set<String> = emptySet(),
    val selectedSms: Set<String> = emptySet(),
    val pickedDay: Long? = null,
    val message: String? = null,
    val clipDone: Boolean = false,
    val filesRootDone: Boolean = false,
    val accountsRootDone: Boolean = false,
    val usageRootDone: Boolean = false
)

fun accountKey(name: String, type: String) = name + "\u0000" + type

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun endOfDay(dayStart: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay(dayStart)
    add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

fun selectedDay(picked: Long?): Long = picked?.let { startOfDay(it) } ?: startOfToday()

fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

fun formatDayTime(ms: Long): String {
    return if (ms >= startOfToday()) {
        formatTime(ms)
    } else {
        SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(ms))
    }
}

private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "dng")
private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")

fun isVideoName(name: String): Boolean = extOf(name) in videoExt

fun isPreviewable(name: String): Boolean {
    val ext = extOf(name)
    return ext in imageExt || ext in videoExt
}

private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US)

fun formatDate(ms: Long): String = SimpleDateFormat("EEEE d MMMM", Locale.US).format(Date(ms))

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60000
    if (minutes < 1) return "Under a minute"
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${minutes % 60} min"
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
