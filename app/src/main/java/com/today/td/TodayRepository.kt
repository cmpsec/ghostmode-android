package com.today.td

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.os.Process
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object Repo {
    private const val TAG = "Today"
    const val NEED_ADMIN = "NEED_ADMIN"
    const val NEED_SHELL = "NEED_SHELL"
    const val NEED_SMS_ROLE = "NEED_SMS_ROLE"
    const val NEED_CHROME = "NEED_CHROME"
    private const val MAX_FILES = 3000
    private val safePkg = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val skipDirNames = setOf("cache", "code_cache", ".thumbnails", ".cache")
    private val chromePackages = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome"
    )
    private val historyNames = listOf(
        "History",
        "History-journal",
        "History-wal",
        "History-shm",
        "Archived History",
        "Archived History-journal",
        "Visited Links",
        "Visited Links-journal",
        "Top Sites",
        "Top Sites-journal",
        "Top Sites-wal",
        "Top Sites-shm"
    )
    private val knownTypes = mapOf(
        "com.google" to "Google",
        "com.google.android.gm.legacyimap" to "Gmail",
        "com.osp.app.signin" to "Samsung",
        "com.facebook.auth.login" to "Facebook",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram"
    )

    fun hasFileAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun loadApps(context: Context, dayStart: Long): List<AppEntry> {
        return try {
            val pm = context.packageManager
            val start = startOfDay(dayStart)
            val end = endOfDay(start)
            val result = ArrayList<AppEntry>()
            for (info in installedPackages(pm)) {
                try {
                    val appInfo = info.applicationInfo ?: continue
                    if (info.packageName == context.packageName) continue
                    if (info.firstInstallTime < start || info.firstInstallTime >= end) continue
                    if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                    if (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) continue
                    result += AppEntry(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        installedAt = info.firstInstallTime,
                        source = installerLabel(pm, info.packageName)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "app row", e)
                }
            }
            result.sortedByDescending { it.installedAt }
        } catch (e: Exception) {
            Log.e(TAG, "loadApps", e)
            emptyList()
        }
    }

    fun loadFiles(context: Context, withRootFind: Boolean): FileScan {
        return try {
            val start = startOfToday()
            val files = LinkedHashMap<String, FileEntry>()
            val dirs = ArrayList<File>()
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) {
                walkStorage(context, root, start, files, dirs)
            }
            val top = topDirectories(dirs)
            if (top.isNotEmpty()) {
                files.entries.removeIf { (path, entry) -> !entry.hidden && top.any { path.startsWith("$it/") } }
                for (path in top) {
                    val file = File(path)
                    files[path] = FileEntry(
                        path = path,
                        name = file.name.ifBlank { path },
                        dir = true,
                        size = 0L,
                        time = createdAt(file),
                        parent = file.parentFile?.name ?: "",
                        hidden = file.name.startsWith(".")
                    )
                }
            }
            scanMediaStore(context, start, files, top)
            if (withRootFind) {
                supplementAndroidData(start, files, top)
                supplementHidden(files)
            }
            val hidden = files.values.filter { it.hidden }.sortedByDescending { it.time }
            val normal = files.values.filter { !it.hidden }.sortedByDescending { it.time }
            val sorted = hidden + normal
            val truncated = sorted.size > MAX_FILES
            FileScan(if (truncated) sorted.take(MAX_FILES) else sorted, truncated)
        } catch (e: Exception) {
            Log.e(TAG, "loadFiles", e)
            FileScan(emptyList(), false)
        }
    }

    fun loadAccounts(context: Context, useRoot: Boolean): List<AccountEntry> {
        val map = LinkedHashMap<String, AccountEntry>()
        val descriptions = authenticatorMap(context)
        addVisibleAccounts(context, descriptions, map)
        if (useRoot || ShellBridge.ready()) {
            for ((name, type) in listSystemAccounts(context)) {
                if (name.isEmpty() || type.isEmpty()) continue
                val key = accountKey(name, type)
                if (map.containsKey(key)) continue
                val desc = descriptions[type]
                map[key] = AccountEntry(
                    name = name,
                    type = type,
                    typeLabel = typeLabel(context, desc, type),
                    authenticatorPackage = desc?.packageName
                )
            }
        }
        return map.values.sortedWith(compareBy({ it.typeLabel }, { it.name }))
    }

    fun hasCallAccess(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasSmsAccess(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun loadCalls(context: Context): List<CallEntry> {
        if (!hasCallAccess(context)) return emptyList()
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            ) ?: return emptyList()
            cursor.use {
                val result = ArrayList<CallEntry>()
                while (it.moveToNext() && result.size < 3000) {
                    val id = it.getLong(0)
                    val number = it.getString(1)?.trim().orEmpty().ifBlank { "Hidden number" }
                    val name = it.getString(2)?.trim().orEmpty()
                    val type = if (it.isNull(3)) 0 else it.getInt(3)
                    val at = if (it.isNull(4)) 0L else it.getLong(4)
                    val seconds = if (it.isNull(5)) 0L else it.getLong(5)
                    result += CallEntry(
                        id = id,
                        title = name.ifBlank { number },
                        number = number,
                        typeLabel = callTypeLabel(type),
                        at = at,
                        duration = formatCallDuration(seconds)
                    )
                }
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "calls", e)
            emptyList()
        }
    }

    fun loadSms(context: Context): List<SmsEntry> {
        if (!hasSmsAccess(context)) return emptyList()
        return try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                Telephony.Sms.DATE + " DESC"
            ) ?: return emptyList()
            cursor.use {
                val result = ArrayList<SmsEntry>()
                while (it.moveToNext() && result.size < 3000) {
                    val id = it.getLong(0)
                    val address = it.getString(1)?.trim().orEmpty().ifBlank { "Unknown" }
                    val body = it.getString(2)?.trim()?.replace('\n', ' ')?.replace('\r', ' ').orEmpty()
                    val at = if (it.isNull(3)) 0L else it.getLong(3)
                    val type = if (it.isNull(4)) 0 else it.getInt(4)
                    result += SmsEntry(
                        id = id,
                        address = address,
                        body = body.ifBlank { "(No text)" },
                        at = at,
                        box = smsBoxLabel(type)
                    )
                }
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "sms", e)
            emptyList()
        }
    }

    fun deleteCalls(context: Context, ids: List<Long>): String {
        if (ids.isEmpty()) return "No call selected"
        var ok = 0
        val left = ArrayList<Long>()
        for (id in ids) {
            val removed = try {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    CallLog.Calls._ID + "=?",
                    arrayOf(id.toString())
                ) > 0
            } catch (e: Exception) {
                false
            }
            if (removed) ok++ else left += id
        }
        if (left.isNotEmpty() && ShellBridge.ready()) {
            ok += shellContentDelete("content://call_log/calls", left)
        }
        return "Deleted $ok call(s), failed ${ids.size - ok}"
    }

    fun isDefaultSms(context: Context): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    fun deleteSms(context: Context, ids: List<Long>): String {
        if (ids.isEmpty()) return "No message selected"
        var ok = 0
        val left = ArrayList<Long>()
        if (isDefaultSms(context)) {
            for (id in ids) {
                val removed = try {
                    val direct = context.contentResolver.delete(Uri.parse("content://sms/$id"), null, null)
                    if (direct > 0) {
                        true
                    } else {
                        context.contentResolver.delete(
                            Telephony.Sms.CONTENT_URI,
                            Telephony.Sms._ID + "=?",
                            arrayOf(id.toString())
                        ) > 0
                    }
                } catch (e: Exception) {
                    false
                }
                if (removed) ok++ else left += id
            }
        } else {
            left += ids
        }
        if (left.isNotEmpty() && ShellBridge.ready()) {
            ok += shellContentDelete("content://sms", left)
        }
        val fail = ids.size - ok
        if (fail > 0 && !isDefaultSms(context)) return NEED_SMS_ROLE
        return if (isDefaultSms(context) && fail == 0) {
            "Deleted $ok message(s). You can switch the default SMS app back in Default apps"
        } else {
            "Deleted $ok message(s), failed $fail"
        }
    }

    fun loadUsage(context: Context, access: Boolean, dayStart: Long): List<UsageEntry> {
        if (!access) return emptyList()
        return try {
            val pm = context.packageManager
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = startOfDay(dayStart)
            val end = endOfDay(start)
            val lastUsed = HashMap<String, Long>()
            val foreground = HashMap<String, Long>()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
            for (stat in stats) {
                if (stat.lastTimeUsed in start until end) {
                    lastUsed[stat.packageName] = maxOf(lastUsed[stat.packageName] ?: 0L, stat.lastTimeUsed)
                    foreground[stat.packageName] = (foreground[stat.packageName] ?: 0L) + stat.totalTimeInForeground
                }
            }
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            @Suppress("DEPRECATION")
            val resumed = UsageEvents.Event.MOVE_TO_FOREGROUND
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == resumed && event.timeStamp in start until end) {
                    val prev = lastUsed[event.packageName] ?: 0L
                    if (event.timeStamp > prev) lastUsed[event.packageName] = event.timeStamp
                }
            }
            val result = ArrayList<UsageEntry>()
            for ((pkg, used) in lastUsed) {
                if (pkg == context.packageName || pkg in SKIP_USAGE) continue
                if (!safePkg.matches(pkg)) continue
                val appInfo = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                } catch (e: Exception) {
                    continue
                }
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val installed = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L)).firstInstallTime
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0).firstInstallTime
                    }
                } catch (e: Exception) {
                    0L
                }
                val installedToday = installed in start until end
                result += UsageEntry(
                    packageName = pkg,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    lastUsed = used,
                    foregroundMs = foreground[pkg] ?: 0L,
                    firstInstallTime = installed,
                    installedToday = installedToday,
                    clear = if (installedToday) ClearState.INSTALLED_TODAY else ClearState.UNKNOWN
                )
            }
            result.sortedByDescending { it.lastUsed }
        } catch (e: Exception) {
            Log.e(TAG, "loadUsage", e)
            emptyList()
        }
    }

    fun applyClearFlags(context: Context, entries: List<UsageEntry>, dayStart: Long): List<UsageEntry> {
        if (entries.isEmpty()) return entries
        val start = startOfDay(dayStart)
        val end = endOfDay(start)
        val user = Process.myUid() / 100000
        val pkgs = entries.map { it.packageName }.filter { safePkg.matches(it) }
        if (pkgs.isEmpty()) return entries
        val script = buildString {
            for (pkg in pkgs) {
                val quoted = shellQuote("/data/user/$user/$pkg")
                append("if [ -d $quoted ]; then stat -c '%W %Z %Y' $quoted 2>/dev/null || echo 0 0 0; else echo MISSING; fi\n")
            }
        }
        val out = RootShell.runScript(script, 30000)
        val parsed = out.lines().map { it.trim() }.filter { it == "MISSING" || STAT.matches(it) }
        if (parsed.size != pkgs.size) return entries
        val byPkg = pkgs.zip(parsed).toMap()
        return entries.map { entry ->
            if (entry.installedToday) {
                entry.copy(clear = ClearState.INSTALLED_TODAY)
            } else {
                entry.copy(clear = interpretStat(byPkg[entry.packageName], entry.firstInstallTime, start, end))
            }
        }
    }

    fun isDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(TodayAdminReceiver.component(context))
    }

    fun clearAppData(context: Context, pkg: String): String {
        if (!safePkg.matches(pkg) || pkg == context.packageName || pkg == "android") {
            return "This app's data cannot be cleared"
        }
        clearAsOwner(context, pkg)?.let { return it }
        if (!ShellBridge.ready()) return NEED_SHELL
        val user = Process.myUid() / 100000
        val dollar = '$'
        val script = buildString {
            append("r=\$(pm clear --user $user $pkg 2>&1)\n")
            append("echo \"OUT:${dollar}r\"\n")
            append("case \"${dollar}r\" in *[Ss]uccess*) exit 0;; esac\n")
            append("r=\$(pm clear $pkg 2>&1)\n")
            append("echo \"OUT:${dollar}r\"\n")
            append("case \"${dollar}r\" in *[Ss]uccess*) exit 0;; esac\n")
            append("cmd package clear --user $user $pkg 2>&1\n")
        }
        val out = ShellBridge.run(script, 25000)
        if (out.contains("uccess")) return "Storage cleared"
        Log.w(TAG, "pm clear ${out.take(220)}")
        return "The system refused to clear storage"
    }

    private fun clearAsOwner(context: Context, pkg: String): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val owner = dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
        if (!owner) return null
        val admin: ComponentName = TodayAdminReceiver.component(context)
        val latch = CountDownLatch(1)
        val result = AtomicReference("Failed to clear data")
        return try {
            dpm.clearApplicationUserData(admin, pkg, ContextCompat.getMainExecutor(context)) { _, succeeded ->
                result.set(if (succeeded) "App data cleared" else "Failed to clear data")
                latch.countDown()
            }
            if (!latch.await(20, TimeUnit.SECONDS)) "Timed out while clearing data" else result.get()
        } catch (e: Exception) {
            Log.e(TAG, "clear owner", e)
            null
        }
    }

    fun loadChromeHistory(context: Context): List<ChromeEntry> {
        val provided = queryChromeProvider(context)
        if (provided.isNotEmpty()) return provided
        if (RootShell.cached != true && !RootShell.isAvailable(false, 2000)) return emptyList()
        val apk = shellQuote(context.applicationInfo.sourceDir)
        val dollar = '$'
        val script = buildString {
            append("src=\"\"\n")
            append("for f in")
            for (pkg in chromePackages) {
                append(" /data/user/0/$pkg/app_chrome/Default/History")
                append(" /data/data/$pkg/app_chrome/Default/History")
            }
            append("\n")
            append("do\n")
            append("if [ -f \"${dollar}f\" ]; then src=\"${dollar}f\"; break; fi\n")
            append("done\n")
            append("if [ -z \"${dollar}src\" ]; then\n")
            append("src=\$(find /data/user /data/data -type f -path '*app_chrome*' -name History 2>/dev/null | head -n 1)\n")
            append("fi\n")
            append("if [ -z \"${dollar}src\" ]; then echo NO_DB; exit 0; fi\n")
            append("rm -f /data/local/tmp/gm-history.db /data/local/tmp/gm-history.db-wal /data/local/tmp/gm-history.db-shm\n")
            append("cp \"${dollar}src\" /data/local/tmp/gm-history.db || exit 0\n")
            append("cp \"${dollar}src-wal\" /data/local/tmp/gm-history.db-wal 2>/dev/null || true\n")
            append("cp \"${dollar}src-shm\" /data/local/tmp/gm-history.db-shm 2>/dev/null || true\n")
            append("chmod 600 /data/local/tmp/gm-history.db /data/local/tmp/gm-history.db-wal /data/local/tmp/gm-history.db-shm 2>/dev/null || true\n")
            append("cp $apk /data/local/tmp/gm-base.apk\n")
            append("chmod 644 /data/local/tmp/gm-base.apk\n")
            append("CLASSPATH=/data/local/tmp/gm-base.apk app_process /system/bin com.today.td.root.HistoryDump /data/local/tmp/gm-history.db\n")
            append("rm -f /data/local/tmp/gm-history.db /data/local/tmp/gm-history.db-wal /data/local/tmp/gm-history.db-shm /data/local/tmp/gm-base.apk\n")
        }
        return try {
            val out = ShellBridge.run(script, 40000)
            Log.i(TAG, "chrome read ${out.take(240)}")
            parseHistory(out)
        } catch (e: Exception) {
            Log.e(TAG, "chrome history", e)
            emptyList()
        }
    }

    private fun parseHistory(out: String): List<ChromeEntry> {
        val result = ArrayList<ChromeEntry>()
        for (line in out.lines()) {
            if (!line.contains('\t')) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val url = parts[1].trim()
            if (url.isBlank() || url.startsWith("RESULT:")) continue
            val visited = fromChromeTime(parts[2].trim().toLongOrNull() ?: 0L)
            val title = parts[0].trim().ifBlank { url }
            result += ChromeEntry(title = title, url = url, visitedAt = visited)
        }
        return result
    }

    private fun fromChromeTime(value: Long): Long {
        if (value <= 0L) return 0L
        return value / 1000L - 11644473600000L
    }

    fun isChromeInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return chromePackages.any { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun uninstallRoot(packages: List<String>): String {
        val user = Process.myUid() / 100000
        val valid = packages.filter { safePkg.matches(it) }
        if (valid.isEmpty()) return "No matching apps"
        val dollar = '$'
        val script = buildString {
            append("ok=0\nfail=0\n")
            for (pkg in valid) {
                append("r=$(pm uninstall --user $user $pkg 2>&1)\n")
                append("echo \"PKG:${dollar}r\"\n")
                append("case \"${dollar}r\" in *Success*) ok=${dollar}((ok+1));; *) fail=${dollar}((fail+1));; esac\n")
            }
            append("echo COUNT:${dollar}ok:${dollar}fail\n")
        }
        val out = RootShell.runScript(script, 60000)
        val match = COUNT2.find(out)
        return if (match != null) {
            "Removed ${match.groupValues[1]} app(s), failed ${match.groupValues[2]}"
        } else {
            "Could not remove the apps"
        }
    }

    fun deleteFiles(context: Context, paths: List<String>, useRoot: Boolean): String {
        var ok = 0
        var fail = 0
        val needRoot = ArrayList<String>()
        for (path in paths) {
            if (!safePath(path)) {
                fail++
                continue
            }
            if (deleteQuiet(context, path)) ok++ else needRoot += path
        }
        if (needRoot.isNotEmpty() && useRoot) {
            val script = buildString {
                for (path in needRoot) {
                    append("if rm -rf -- ${shellQuote(path)}; then echo OK; else echo FAIL; fi\n")
                }
            }
            val out = RootShell.runScript(script, 60000)
            val rootOk = out.lineSequence().count { it.trim() == "OK" }
            ok += rootOk
            fail += needRoot.size - rootOk
        } else {
            fail += needRoot.size
        }
        return "Deleted $ok, failed $fail"
    }

    fun accountPresent(context: Context, name: String, type: String): Boolean {
        val manager = AccountManager.get(context)
        try {
            if (manager.getAccountsByType(type).any { it.name.equals(name, true) }) return true
        } catch (e: Exception) {
            Log.i(TAG, "account lookup ${e.message}")
        }
        try {
            if (manager.accounts.any { it.name.equals(name, true) && it.type == type }) return true
        } catch (e: Exception) {
            Log.i(TAG, "account list ${e.message}")
        }
        if (ShellBridge.ready()) {
            return listSystemAccounts(context).any { it.first.equals(name, true) && it.second == type }
        }
        return true
    }

    fun removeAccountsRoot(context: Context, accounts: List<AccountEntry>): String {
        if (accounts.isEmpty()) return "No account selected"
        val payload = buildString {
            append("remove-many\n")
            for (account in accounts) {
                if (account.name.contains('\n') || account.name.contains('\t')) continue
                if (account.type.contains('\n') || account.type.contains('\t')) continue
                append(account.name).append('\t').append(account.type).append('\n')
            }
        }
        val payloadFile = File(context.cacheDir, "td-accounts.txt")
        payloadFile.writeText(payload)
        val apk = context.applicationInfo.sourceDir
        val dollar = '$'
        val script = buildString {
            append("cp ${shellQuote(payloadFile.absolutePath)} /data/local/tmp/today-accounts.txt\n")
            append("chmod 644 /data/local/tmp/today-accounts.txt\n")
            append("cp ${shellQuote(apk)} /data/local/tmp/today-base.apk\n")
            append("chmod 644 /data/local/tmp/today-base.apk\n")
            append("su 1000 -c \"CLASSPATH=/data/local/tmp/today-base.apk app_process /system/bin com.today.td.root.PrivilegedEntry file /data/local/tmp/today-accounts.txt\"\n")
            append("status=${dollar}?\n")
            append("if [ \"${dollar}status\" != 0 ]; then\n")
            append("CLASSPATH=/data/local/tmp/today-base.apk app_process /system/bin com.today.td.root.PrivilegedEntry file /data/local/tmp/today-accounts.txt\n")
            append("fi\n")
            append("rm -f /data/local/tmp/today-accounts.txt /data/local/tmp/today-base.apk\n")
        }
        return try {
            val out = RootShell.runScript(script, 90000)
            Log.i(TAG, "accounts ${out.take(500)}")
            val match = DONE.findAll(out).lastOrNull()
            if (match != null) {
                "Removed ${match.groupValues[1]} account(s), failed ${match.groupValues[2]}"
            } else {
                val line = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("RESULT:") }
                "Could not remove the accounts. ${line ?: "Root is required for a silent delete. Use the system account page."}"
            }
        } finally {
            payloadFile.delete()
        }
    }

    fun clearChrome(): String {
        if (!ShellBridge.ready()) return NEED_CHROME
        val user = Process.myUid() / 100000
        val script = buildString {
            for (pkg in chromePackages) {
                append("am force-stop $pkg >/dev/null 2>&1\n")
                append("pm clear --user $user $pkg\n")
                append("pm clear $pkg\n")
            }
        }
        val out = ShellBridge.run(script, 60000)
        Log.i(TAG, "chrome clear ${out.take(240)}")
        return if (out.contains("uccess")) {
            "Chrome data was wiped: history, passwords, cookies, and site data"
        } else {
            NEED_CHROME
        }
    }

    private fun ensureRoot(): Boolean {
        if (RootShell.cached == true) return true
        return RootShell.isAvailable(true, 20000)
    }

    private fun listSystemAccounts(context: Context): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        if (RootShell.cached == true) {
            val apk = shellQuote(context.applicationInfo.sourceDir)
            val script = buildString {
                append("cp $apk /data/local/tmp/gm-base.apk\n")
                append("chmod 644 /data/local/tmp/gm-base.apk\n")
                append("CLASSPATH=/data/local/tmp/gm-base.apk app_process /system/bin com.today.td.root.PrivilegedEntry list\n")
                append("rm -f /data/local/tmp/gm-base.apk\n")
                append("dumpsys account\n")
            }
            collectAccounts(ShellBridge.run(script, 30000), rows)
        }
        if (rows.isEmpty() && ShellBridge.granted()) {
            collectAccounts(ShellBridge.run("dumpsys account\n", 15000), rows)
        }
        return rows
    }

    private fun collectAccounts(out: String, rows: MutableList<Pair<String, String>>) {
        for (line in out.lines()) {
            val tab = line.indexOf('\t')
            if (tab > 0 && !line.startsWith("RESULT:")) {
                val name = line.substring(0, tab).trim()
                val type = line.substring(tab + 1).trim()
                if (name.isNotEmpty() && type.isNotEmpty() && !type.contains(' ')) {
                    rows += name to type
                    continue
                }
            }
            val match = ACCOUNT.find(line) ?: continue
            val name = match.groupValues[1].trim()
            val type = match.groupValues[2].trim()
            if (name.isNotEmpty() && type.isNotEmpty()) rows += name to type
        }
    }

    private fun shellContentDelete(uri: String, ids: List<Long>): Int {
        if (ids.isEmpty() || !ShellBridge.ready()) return 0
        val script = buildString {
            for (id in ids) {
                if (id <= 0L) continue
                append("if content delete --uri $uri/$id >/dev/null 2>&1; then echo OK; ")
                append("elif content delete --uri $uri --where ${shellQuote("_id=$id")} >/dev/null 2>&1; then echo OK; ")
                append("else echo FAIL; fi\n")
            }
        }
        val out = ShellBridge.run(script, 60000)
        return out.lineSequence().count { it.trim() == "OK" }
    }

    private fun queryChromeProvider(context: Context): List<ChromeEntry> {
        val uris = listOf(
            "content://com.android.chrome.browser/history",
            "content://com.android.chrome.ChromeBrowserProvider/history"
        )
        for (uri in uris) {
            try {
                val cursor = context.contentResolver.query(Uri.parse(uri), null, null, null, null) ?: continue
                cursor.use {
                    val urlCol = it.getColumnIndex("url")
                    if (urlCol < 0) return@use
                    val titleCol = it.getColumnIndex("title")
                    val dateCol = it.getColumnIndex("date")
                    val result = ArrayList<ChromeEntry>()
                    while (it.moveToNext() && result.size < 200) {
                        val url = it.getString(urlCol)?.trim().orEmpty()
                        if (url.isBlank()) continue
                        val title = if (titleCol >= 0) {
                            it.getString(titleCol)?.trim().orEmpty().ifBlank { url }
                        } else {
                            url
                        }
                        val raw = if (dateCol >= 0 && !it.isNull(dateCol)) it.getLong(dateCol) else 0L
                        val visited = if (raw > 11_644_473_600_000L) fromChromeTime(raw) else raw
                        result += ChromeEntry(title = title, url = url, visitedAt = visited)
                    }
                    if (result.isNotEmpty()) return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "chrome provider $uri", e)
            }
        }
        return emptyList()
    }

    private fun callTypeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        else -> "Call"
    }

    private fun smsBoxLabel(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "Inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sent"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
        else -> "Message"
    }

    private fun formatCallDuration(seconds: Long): String {
        if (seconds <= 0L) return "No duration"
        if (seconds < 60L) return "$seconds sec"
        return "${seconds / 60} min ${seconds % 60} sec"
    }

    private fun interpretStat(line: String?, firstInstall: Long, start: Long, end: Long): ClearState {
        if (line == null || line == "MISSING") return ClearState.UNKNOWN
        val birth = line.split(" ").getOrNull(0)?.toLongOrNull() ?: 0L
        if (birth <= 0L) return ClearState.UNKNOWN
        val birthMs = if (birth > 10_000_000_000L) birth else birth * 1000
        return if (firstInstall >= 1L && firstInstall < start && birthMs in start until end) {
            ClearState.CLEARED
        } else {
            ClearState.NOT_CLEARED
        }
    }

    private fun deleteQuiet(context: Context, path: String): Boolean {
        val file = File(path)
        val removed = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            false
        }
        try {
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            context.contentResolver.delete(uri, MediaStore.MediaColumns.DATA + "=?", arrayOf(path))
        } catch (e: Exception) {
            Log.w(TAG, "media delete", e)
        }
        return removed || !file.exists()
    }

    private fun supplementAndroidData(start: Long, files: MutableMap<String, FileEntry>, top: List<String>) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(start))
        val script = "find /storage/emulated/0/Android/data -type f -newermt ${shellQuote(stamp)} ! -path '*/cache/*' ! -path '*/code_cache/*' 2>/dev/null | head -n 2000"
        val out = RootShell.runScript(script, 40000)
        for (line in out.lines()) {
            val path = line.trim()
            if (!path.startsWith("/storage/")) continue
            if (!safePath(path) || files.containsKey(path)) continue
            if (top.any { path.startsWith("$it/") }) continue
            val file = File(path)
            files[path] = FileEntry(
                path = path,
                name = file.name,
                dir = false,
                size = file.length(),
                time = file.lastModified(),
                parent = file.parentFile?.name ?: "",
                hidden = file.name.startsWith(".")
            )
        }
    }

    private fun supplementHidden(files: MutableMap<String, FileEntry>) {
        val script = "find /storage/emulated/0 /sdcard -name '.*' \\( -type f -o -type d \\) " +
            "! -path '*/.thumbnails/*' ! -path '*/.cache/*' ! -path '*/cache/*' ! -path '*/code_cache/*' " +
            "2>/dev/null | head -n 2500"
        val out = RootShell.runScript(script, 40000)
        for (line in out.lines()) {
            val path = line.trim()
            if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) continue
            if (!safePath(path) || files.containsKey(path)) continue
            val file = File(path)
            if (!file.name.startsWith(".")) continue
            val dir = file.isDirectory
            files[path] = FileEntry(
                path = path,
                name = file.name,
                dir = dir,
                size = if (dir) 0L else file.length(),
                time = createdAt(file),
                parent = file.parentFile?.name ?: "",
                hidden = true
            )
        }
    }

    private fun scanMediaStore(
        context: Context,
        startMs: Long,
        into: MutableMap<String, FileEntry>,
        top: List<String>
    ) {
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = ArrayList<String>()
        projection += MediaStore.Files.FileColumns.DISPLAY_NAME
        projection += MediaStore.Files.FileColumns.SIZE
        projection += MediaStore.Files.FileColumns.DATE_ADDED
        projection += MediaStore.MediaColumns.DATA
        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.Files.FileColumns.RELATIVE_PATH
        val cursor = try {
            context.contentResolver.query(
                uri,
                projection.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_ADDED} >= ?",
                arrayOf((startMs / 1000).toString()),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "mediastore", e)
            null
        } ?: return
        cursor.use {
            val nameI = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeI = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateI = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val dataI = it.getColumnIndex(MediaStore.MediaColumns.DATA)
            val relI = if (Build.VERSION.SDK_INT >= 29) {
                it.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            } else {
                -1
            }
            while (it.moveToNext()) {
                val name = if (nameI >= 0 && !it.isNull(nameI)) it.getString(nameI) else continue
                val data = if (dataI >= 0 && !it.isNull(dataI)) it.getString(dataI) else null
                val rel = if (relI >= 0 && !it.isNull(relI)) it.getString(relI) else ""
                val path = data ?: (Environment.getExternalStorageDirectory().absolutePath + "/" + rel + name)
                if (!safePath(path) || isOwnFile(context, path) || into.containsKey(path)) continue
                if (top.any { dir -> path.startsWith("$dir/") }) continue
                val file = File(path)
                val size = if (sizeI >= 0 && !it.isNull(sizeI)) it.getLong(sizeI) else file.length()
                val time = if (dateI >= 0 && !it.isNull(dateI)) it.getLong(dateI) * 1000 else file.lastModified()
                into[path] = FileEntry(
                    path = path,
                    name = name,
                    dir = file.isDirectory,
                    size = size,
                    time = time,
                    parent = file.parentFile?.name ?: "",
                    hidden = name.startsWith(".")
                )
            }
        }
    }

    private fun walkStorage(
        context: Context,
        root: File,
        start: Long,
        files: MutableMap<String, FileEntry>,
        dirs: MutableList<File>
    ) {
        var visited = 0
        fun walk(dir: File, depth: Int): Pair<Boolean, Boolean> {
            if (depth > 24 || visited > 150_000) return true to false
            val kids = childrenOf(dir) ?: return true to false
            if (kids.isEmpty()) {
                return if (depth > 0 && createdAt(dir) >= start && canOfferDir(dir)) {
                    dirs += dir
                    false to true
                } else {
                    false to false
                }
            }
            var hasOld = false
            var hasNew = false
            for (child in kids) {
                visited++
                if (visited > 150_000) {
                    hasOld = true
                    break
                }
                if (isSymlink(child) || isOwnFile(context, child.absolutePath)) continue
                val hidden = child.name.startsWith(".")
                if (child.isDirectory) {
                    if (hidden && canOfferDir(child)) {
                        files[child.absolutePath] = FileEntry(
                            path = child.absolutePath,
                            name = child.name,
                            dir = true,
                            size = 0L,
                            time = createdAt(child),
                            parent = dir.name,
                            hidden = true
                        )
                    }
                    if (child.name in skipDirNames) continue
                    val (old, new) = walk(child, depth + 1)
                    if (old) hasOld = true
                    if (new) hasNew = true
                } else {
                    val time = createdAt(child)
                    if (hidden || time >= start) {
                        if (time >= start) hasNew = true else hasOld = true
                        files[child.absolutePath] = FileEntry(
                            path = child.absolutePath,
                            name = child.name,
                            dir = false,
                            size = child.length(),
                            time = time,
                            parent = dir.name,
                            hidden = hidden
                        )
                    } else {
                        hasOld = true
                    }
                }
            }
            if (!hasOld && hasNew && depth > 0 && canOfferDir(dir) && createdAt(dir) >= start) {
                dirs += dir
            }
            return hasOld to hasNew
        }
        walk(root, 0)
    }

    private fun topDirectories(dirs: List<File>): List<String> {
        val unique = dirs.map { it.absolutePath }.distinct().sortedBy { it.length }
        val top = ArrayList<String>()
        for (path in unique) {
            if (top.none { path.startsWith("$it/") }) top += path
        }
        return top
    }

    private fun childrenOf(dir: File): Array<File>? {
        return try {
            if (isSymlink(dir)) null else dir.listFiles()
        } catch (e: Exception) {
            null
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (e: Exception) {
            false
        }
    }

    private fun createdAt(file: File): Long {
        return try {
            val created = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                .creationTime()
                .toMillis()
            if (created > 0L) created else file.lastModified()
        } catch (e: Exception) {
            file.lastModified()
        }
    }

    private fun canOfferDir(dir: File): Boolean {
        val path = try {
            dir.canonicalPath
        } catch (e: Exception) {
            return false
        }
        if (!safePath(path)) return false
        val storage = try {
            Environment.getExternalStorageDirectory()?.canonicalPath
        } catch (e: Exception) {
            null
        } ?: return true
        val relative = path.removePrefix(storage).trim('/')
        return !relative.equals("Android", true)
    }

    private fun isOwnFile(context: Context, path: String): Boolean {
        val bases = listOfNotNull(
            context.externalCacheDir?.parentFile?.absolutePath,
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath
        )
        return bases.any { path.startsWith(it) }
    }

    private fun safePath(path: String): Boolean {
        if (path.isBlank() || path.contains('\u0000')) return false
        val canonical = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return false
        }
        if (canonical == "/storage" || canonical == "/storage/emulated" ||
            canonical == "/storage/emulated/0" || canonical == "/sdcard" || canonical == "/mnt"
        ) {
            return false
        }
        return canonical.startsWith("/storage/") || canonical.startsWith("/sdcard/") || canonical.startsWith("/mnt/")
    }

    private fun addVisibleAccounts(
        context: Context,
        descriptions: Map<String, AuthenticatorDescription>,
        map: LinkedHashMap<String, AccountEntry>
    ) {
        val manager = AccountManager.get(context)
        val accounts = LinkedHashSet<Account>()
        try {
            accounts.addAll(manager.accounts)
        } catch (e: Exception) {
            Log.w(TAG, "accounts", e)
        }
        val types = try {
            manager.authenticatorTypes
        } catch (e: Exception) {
            emptyArray<AuthenticatorDescription>()
        }
        for (type in types) {
            try {
                accounts.addAll(manager.getAccountsByType(type.type))
            } catch (e: Exception) {
                Log.w(TAG, "accounts ${type.type}", e)
            }
        }
        for (account in accounts) {
            val desc = descriptions[account.type]
            map[accountKey(account.name, account.type)] = AccountEntry(
                name = account.name,
                type = account.type,
                typeLabel = typeLabel(context, desc, account.type),
                authenticatorPackage = desc?.packageName
            )
        }
    }

    private fun authenticatorMap(context: Context): Map<String, AuthenticatorDescription> {
        return try {
            AccountManager.get(context).authenticatorTypes.associateBy { it.type }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun typeLabel(context: Context, desc: AuthenticatorDescription?, type: String): String {
        if (desc != null) {
            try {
                val resources = context.packageManager.getResourcesForApplication(desc.packageName)
                val label = resources.getString(desc.labelId)
                if (label.isNotBlank()) return label
            } catch (e: Exception) {
                Log.w(TAG, "label $type", e)
            }
        }
        return knownTypes[type] ?: type.substringAfterLast('.')
    }

    private fun installerLabel(pm: PackageManager, pkg: String): String {
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (e: Exception) {
            null
        }
        return when (installer) {
            "com.android.vending" -> "Play"
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.apps.nbu.files" -> "Files"
            null, "" -> "Unknown"
            else -> installer.substringAfterLast('.')
        }
    }

    private fun installedPackages(pm: PackageManager) = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(0)
    }

    private val ACCOUNT = Regex("""Account \{name=(.*), type=([^}\s]+)\}""")
    private val STAT = Regex("""\d+ \d+ \d+""")
    private val COUNT1 = Regex("""COUNT:(\d+)""")
    private val COUNT2 = Regex("""COUNT:(\d+):(\d+)""")
    private val DONE = Regex("""RESULT:DONE:(\d+):(\d+)""")

    private val SKIP_USAGE = setOf(
        "android",
        "com.android.systemui",
        "com.android.shell"
    )
}
