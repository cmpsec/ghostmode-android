package com.today.td

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChromeAssistService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            packageNames = null
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: ChromeAssistService? = null
    }
}

object ChromeAssist {
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, ChromeEntry>()
    private val urlInText = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val hostLine = Regex("""(?i)^(https?://)?([a-z0-9-]+\.)+[a-z]{2,}([/:].*)?$""")
    private var mode = 0
    private var stable = 0
    private var scrolls = 0
    private var ticks = 0
    private var timeSet = false
    private var deleteArmed = false
    private var advancedOpened = false
    private var readPhase = 0
    private var openedHistoryActivity = false
    private val accountHandler = Handler(Looper.getMainLooper())
    private var accountMode = false
    private var accountName = ""
    private var accountMenuOpened = false
    private var accountTicks = 0
    private var accountLastClick = ""
    @Volatile var accountRemoveClicked = false
    private var readDone: ((List<ChromeEntry>, String) -> Unit)? = null
    private var clearDone: ((String) -> Unit)? = null

    fun enabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val flat = ComponentName(context, ChromeAssistService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    fun beginRead(done: (List<ChromeEntry>, String) -> Unit) {
        stop()
        if (ChromeAssistService.instance == null) {
            done(emptyList(), "Turn on the GhostMode accessibility service, then try again")
            return
        }
        mode = 1
        readDone = done
        handler.postDelayed({ tickRead() }, 700)
        handler.postDelayed({
            if (mode == 1) finishRead(if (found.isEmpty()) "Chrome history did not appear" else "Showing ${found.size} entries")
        }, 22000)
    }

    fun beginClear(done: (String) -> Unit) {
        stop()
        if (ChromeAssistService.instance == null) {
            done("Turn on the GhostMode accessibility service, then try again")
            return
        }
        mode = 2
        clearDone = done
        handler.postDelayed({ tickClear() }, 800)
        handler.postDelayed({
            if (mode == 2) finishClear("Could not finish the wipe. Open Clear browsing data in Chrome and delete all time.")
        }, 28000)
    }

    fun cancel() {
        stop()
    }

    fun beginAccountRemove(name: String) {
        cancelAccount()
        if (ChromeAssistService.instance == null || name.isBlank()) return
        accountName = name
        accountMode = true
        accountMenuOpened = false
        accountTicks = 0
        accountLastClick = ""
        accountRemoveClicked = false
        accountHandler.postDelayed({ tickAccount() }, 900)
    }

    fun cancelAccount() {
        accountMode = false
        accountHandler.removeCallbacksAndMessages(null)
    }

    fun openDeviceSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tickAccount() {
        if (!accountMode) return
        accountTicks++
        if (accountRemoveClicked || accountTicks > 80) {
            accountMode = false
            return
        }
        val root = settingsRoot()
        if (root == null) {
            accountHandler.postDelayed({ tickAccount() }, 700)
            return
        }
        if (clickViewId(root, "remove_account") || clickFresh("remove") { clickMatch(root) { isRemoveAccountAction(it) } }) {
            accountRemoveClicked = true
            accountMode = false
            return
        }
        val lines = textsOf(root)
        val hasName = lines.any { it.contains(accountName, true) }
        val accountList = lines.any { isAddAccount(it) }
        when {
            hasName && accountList -> {
                if (!clickFresh("row") { clickMatch(root) { isAccountRow(it) } }) scroll(root)
            }
            hasName -> {
                if (!accountMenuOpened && accountTicks >= 2) {
                    accountMenuOpened = clickViewId(root, "menu_button") ||
                        clickViewId(root, "action_menu") ||
                        clickFresh("menu") { clickMatch(root) { isOverflow(it) } }
                }
                if (!accountMenuOpened) scroll(root)
            }
            lines.any { isManageAccounts(it) } -> {
                if (!clickFresh("manage") { clickMatch(root) { isManageAccounts(it) } }) scroll(root)
            }
            !accountList && lines.any { isUsersAccountsEntry(it) } -> {
                if (!clickFresh("section") { clickMatch(root) { isUsersAccountsEntry(it) } }) scroll(root)
            }
            accountList -> {
                if (!clickFresh("google") { clickMatch(root) { isGoogleSection(it) } }) scroll(root)
            }
            else -> scroll(root)
        }
        accountHandler.postDelayed({ tickAccount() }, 700)
    }

    private fun clickFresh(label: String, click: () -> Boolean): Boolean {
        if (accountLastClick == label) return false
        if (!click()) return false
        accountLastClick = label
        return true
    }

    private fun isRemoveAccountAction(text: String): Boolean {
        val clean = text.trim()
        if (clean.equals("Remove", true) || clean.equals("Delete", true)) return false
        if (clean == "حذف" || clean == "إزالة" || clean == "ازالة" || clean == "مسح") return false
        return containsAny(
            clean,
            "remove account",
            "delete account",
            "remove this account",
            "إزالة الحساب",
            "ازالة الحساب",
            "حذف الحساب",
            "إزالة هذا الحساب"
        )
    }

    private fun isAccountRow(text: String): Boolean {
        val clean = text.trim()
        return clean.equals(accountName, true) ||
            (clean.contains(accountName, true) && clean.length <= accountName.length + 32)
    }

    private fun isUsersAccountsEntry(text: String): Boolean {
        val clean = text.trim()
        if (clean.length > 48) return false
        return clean.equals("Accounts", true) || clean == "الحسابات" || containsAny(
            clean,
            "users & accounts",
            "users and accounts",
            "passwords & accounts",
            "passwords and accounts",
            "accounts and backup",
            "accounts & backup",
            "accounts and sync",
            "cloud and accounts",
            "المستخدمون والحسابات",
            "كلمات المرور والحسابات",
            "الحسابات والنسخ الاحتياطي",
            "الحسابات والمزامنة"
        )
    }

    private fun isManageAccounts(text: String): Boolean {
        val clean = text.trim()
        return clean.length <= 48 && containsAny(clean, "manage accounts", "إدارة الحسابات")
    }

    private fun isAddAccount(text: String): Boolean {
        return containsAny(text, "add account", "إضافة حساب", "اضافة حساب")
    }

    private fun isGoogleSection(text: String): Boolean {
        val clean = text.trim()
        return clean.equals("Google", true) || clean.equals("جوجل", true)
    }

    private fun isOverflow(text: String): Boolean {
        return containsAny(text, "more options", "المزيد من الخيارات", "خيارات إضافية", "مزيد من الخيارات")
    }

    private fun settingsRoot(): AccessibilityNodeInfo? {
        val service = ChromeAssistService.instance ?: return null
        val windows = try {
            service.windows
        } catch (e: Exception) {
            emptyList()
        }
        var fallback: AccessibilityNodeInfo? = null
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (!isSettingsPkg(pkg)) continue
            val blob = textsOf(root).joinToString("\n")
            if (blob.contains(accountName, true) ||
                blob.contains("Remove account", true) ||
                blob.contains("إزالة الحساب") ||
                blob.contains("حذف الحساب")
            ) {
                return root
            }
            fallback = root
        }
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() == "com.android.settings") return active
        if (active != null && isSettingsPkg(active.packageName?.toString().orEmpty())) return active
        return fallback
    }

    private fun isSettingsPkg(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg == "com.samsung.android.settings" ||
            pkg == "com.google.android.gms" ||
            pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg.endsWith(".settings")
    }

    fun openHistory(context: Context): Boolean {
        val packages = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.google.android.apps.chrome"
        )
        openedHistoryActivity = false
        for (pkg in packages) {
            val history = Intent()
                .setClassName(pkg, "org.chromium.chrome.browser.history.HistoryActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(history)
                openedHistoryActivity = true
                return true
            } catch (e: Exception) {
                continue
            }
        }
        for (pkg in packages) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launch)
                return true
            } catch (e: Exception) {
                continue
            }
        }
        return open(context, listOf("chrome://history", "chrome://history/"))
    }

    fun openClear(context: Context) = open(
        context,
        listOf("chrome://settings/clearBrowserData", "chrome://settings/clearBrowserData/")
    )

    private fun open(context: Context, uris: List<String>): Boolean {
        for (pkg in listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.google.android.apps.chrome"
        )) {
            for (uri in uris) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return false
    }

    private fun tickRead() {
        if (mode != 1) return
        val root = chromeRoot()
        if (root == null) {
            handler.postDelayed({ tickRead() }, 700)
            return
        }
        val lines = textsOf(root)
        val hosts = lines.count { looksLikeSite(it) }
        val heading = lines.any {
            val clean = it.trim()
            clean.equals("History", true) || clean.equals("السجل", true)
        }
        if ((openedHistoryActivity || readPhase >= 2 || heading) && hosts >= 1) {
            val before = found.size
            collect(root)
            if (found.size == before) stable++ else stable = 0
            if ((stable >= 3 && found.isNotEmpty()) || scrolls >= 14) {
                finishRead(if (found.isEmpty()) "Chrome opened, but no history rows were readable" else "Showing ${found.size} entries")
                return
            }
            scroll(root)
            scrolls++
            handler.postDelayed({ tickRead() }, 700)
            return
        }
        if (readPhase == 0) {
            val opened = clickViewId(root, "menu_button") || clickMatch(root) { isChromeMenu(it) }
            if (opened) readPhase = 1
        } else if (clickMatch(root) { isHistoryMenu(it) }) {
            readPhase = 2
        }
        handler.postDelayed({ tickRead() }, 700)
    }

    private fun tickClear() {
        if (mode != 2) return
        ticks++
        val root = chromeRoot()
        if (root == null) {
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        val texts = textsOf(root)
        val onClearPage = texts.any {
            containsAny(
                it,
                "browsing history",
                "delete browsing data",
                "saved passwords",
                "سجل التصفح",
                "حذف بيانات التصفح",
                "كلمات المرور"
            )
        }
        if (!onClearPage) {
            if (ticks > 2) {
                clickMatch(root) { text ->
                    containsAny(
                        text,
                        "delete browsing data",
                        "clear browsing data",
                        "حذف بيانات التصفح",
                        "مسح بيانات التصفح"
                    )
                }
            }
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        if (!advancedOpened) {
            advancedOpened = true
            if (clickMatch(root) { isAdvancedTab(it) }) {
                scroll(root)
                handler.postDelayed({ tickClear() }, 700)
                return
            }
        }
        val boxes = adjustBoxes(root)
        if (!boxes.history) {
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        if (!boxes.passwords && ticks < 10) {
            scroll(root)
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        if (boxes.changed) {
            handler.postDelayed({ tickClear() }, 500)
            return
        }
        if (!timeSet) {
            val texts = textsOf(root)
            val options = texts.count { timeOption(it) }
            if (options >= 2) {
                clickMatch(root) { isAllTime(it) }
                timeSet = true
            } else if (texts.any { isAllTime(it) }) {
                timeSet = true
            } else {
                clickMatch(root) { timeOption(it) && !isAllTime(it) }
            }
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        if (!deleteArmed) {
            if (clickMatch(root) {
                    containsAny(it, "delete data", "clear data", "حذف البيانات", "مسح البيانات")
                }
            ) {
                deleteArmed = true
            }
            handler.postDelayed({ tickClear() }, 700)
            return
        }
        val confirmed = clickMatch(root) { text ->
            val clean = text.trim()
            clean.equals("Delete", true) || clean.equals("Clear", true) ||
                clean.equals("حذف", true) || clean.equals("مسح", true)
        }
        if (confirmed || ticks > 24) {
            finishClear(
                if (confirmed) "Chrome was asked to delete history, passwords, cookies, and site data"
                else "Timed out waiting for Chrome to confirm the delete"
            )
            return
        }
        handler.postDelayed({ tickClear() }, 700)
    }

    private fun finishRead(note: String) {
        val items = found.values.toList()
        val done = readDone
        stop()
        done?.invoke(items, note)
    }

    private fun finishClear(note: String) {
        val done = clearDone
        stop()
        done?.invoke(note)
    }

    private fun stop() {
        mode = 0
        handler.removeCallbacksAndMessages(null)
        found.clear()
        stable = 0
        scrolls = 0
        ticks = 0
        readPhase = 0
        openedHistoryActivity = false
        timeSet = false
        deleteArmed = false
        advancedOpened = false
        readDone = null
        clearDone = null
    }

    private fun chromeRoot(): AccessibilityNodeInfo? {
        val service = ChromeAssistService.instance ?: return null
        val windows = try {
            service.windows
        } catch (e: Exception) {
            emptyList()
        }
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.contains("chrome")) return root
        }
        val active = service.rootInActiveWindow ?: return null
        val pkg = active.packageName?.toString().orEmpty()
        return if (pkg.contains("chrome")) active else null
    }

    private fun collect(root: AccessibilityNodeInfo) {
        val lines = ArrayList<String>()
        walk(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) lines += text
            if (desc.isNotBlank() && desc != text) lines += desc
        }
        var previous = ""
        for (line in lines) {
            val url = extractUrl(line)
            if (url != null) {
                val title = if (previous.isBlank() || extractUrl(previous) != null) url else previous
                found[url] = ChromeEntry(title = title, url = url, visitedAt = 0L)
            }
            if (line.length in 2..180 && extractUrl(line) == null) previous = line
        }
    }

    private fun extractUrl(line: String): String? {
        val embedded = urlInText.find(line)?.value?.trimEnd('.', ',', ')', '،')
        if (embedded != null) return embedded
        val clean = line.trim()
        if (clean.length !in 4..200) return null
        if (clean.contains(' ') || clean.contains('\n')) return null
        if (!hostLine.matches(clean)) return null
        return if (clean.startsWith("http", true)) clean else "https://$clean"
    }

    private data class BoxState(val history: Boolean, val passwords: Boolean, val changed: Boolean)

    private fun adjustBoxes(root: AccessibilityNodeInfo): BoxState {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        walk(root) { nodes += it }
        var history = false
        var passwords = false
        var changed = false
        for (index in nodes.indices) {
            val node = nodes[index]
            if (!node.isCheckable) continue
            val label = nearbyText(nodes, index)
            if (!wipeLabel(label)) continue
            if (historyLabel(label)) history = true
            if (containsAny(label, "password", "كلمة المرور", "كلمات المرور")) passwords = true
            if (!node.isChecked) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                changed = true
            }
        }
        return BoxState(history, passwords, changed)
    }

    private fun nearbyText(nodes: List<AccessibilityNodeInfo>, index: Int): String {
        val own = listOfNotNull(nodes[index].text?.toString(), nodes[index].contentDescription?.toString())
            .joinToString(" ")
            .trim()
        if (own.length > 2) return own
        val forward = ArrayList<String>()
        for (cursor in index + 1 until minOf(nodes.size, index + 4)) {
            if (nodes[cursor].isCheckable) break
            val text = nodes[cursor].text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) forward += text
        }
        if (forward.isNotEmpty()) return forward.joinToString(" ")
        val back = nodes.getOrNull(index - 1)?.text?.toString()?.trim().orEmpty()
        return back
    }

    private fun looksLikeSite(text: String): Boolean = extractUrl(text.trim()) != null

    private fun isChromeMenu(text: String): Boolean {
        return containsAny(
            text,
            "customize and control",
            "more options",
            "open menu",
            "main menu",
            "تخصيص",
            "المزيد من الخيارات"
        )
    }

    private fun isHistoryMenu(text: String): Boolean {
        val clean = text.trim()
        if (containsAny(clean, "clear browsing", "delete browsing", "حذف بيانات", "مسح بيانات")) return false
        return clean.equals("History", true) ||
            clean.equals("السجل", true) ||
            clean.equals("سجل التصفح", true)
    }

    private fun clickViewId(root: AccessibilityNodeInfo, idSuffix: String): Boolean {
        var clicked = false
        walk(root) { node ->
            if (clicked) return@walk
            val id = node.viewIdResourceName ?: return@walk
            if (!id.endsWith(idSuffix) && !id.endsWith("/$idSuffix")) return@walk
            val target = clickable(node) ?: return@walk
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return clicked
    }

    private fun clickMatch(root: AccessibilityNodeInfo, match: (String) -> Boolean): Boolean {
        var clicked = false
        walk(root) { node ->
            if (clicked) return@walk
            val blob = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ")
                .trim()
            if (blob.isBlank() || !match(blob)) return@walk
            val target = clickable(node) ?: return@walk
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return clicked
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun scroll(root: AccessibilityNodeInfo) {
        walk(root) { node ->
            if (node.isScrollable) {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
    }

    private fun textsOf(root: AccessibilityNodeInfo): List<String> {
        val lines = ArrayList<String>()
        walk(root) { node ->
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += it }
        }
        return lines
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            visit(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { stack.add(it) }
            }
        }
    }

    private fun historyLabel(text: String): Boolean {
        return containsAny(text, "browsing history", "browser history", "سجل التصفح")
    }

    private fun isAdvancedTab(text: String): Boolean {
        val clean = text.trim()
        return clean.equals("advanced", true) ||
            clean.equals("متقدم", true) ||
            clean.equals("متقدمة", true) ||
            containsAny(clean, "advanced options", "خيارات متقدمة")
    }

    private fun wipeLabel(text: String): Boolean = historyLabel(text) || siteDataLabel(text)

    private fun siteDataLabel(text: String): Boolean {
        return containsAny(
            text,
            "cookie",
            "ملفات تعريف الارتباط",
            "كوكي",
            "cached",
            "المخزن",
            "المؤقت",
            "password",
            "كلمات المرور",
            "كلمة المرور",
            "autofill",
            "الملء التلقائي",
            "تعبئة تلقائية",
            "site settings",
            "إعدادات المواقع"
        )
    }

    private fun isAllTime(text: String): Boolean {
        return containsAny(
            text,
            "all time",
            "كل الأوقات",
            "كل الوقت",
            "جميع الأوقات",
            "منذ البدء",
            "من البداية"
        )
    }

    private fun timeOption(text: String): Boolean {
        return isAllTime(text) || containsAny(
            text,
            "15",
            "hour",
            "ساعة",
            "24",
            "day",
            "days",
            "يوم",
            "أيام",
            "week",
            "أسبوع",
            "اسابيع",
            "4 weeks",
            "4 أسابيع"
        )
    }

    private fun containsAny(text: String, vararg parts: String): Boolean {
        val folded = text.lowercase()
        return parts.any { folded.contains(it.lowercase()) }
    }
}
