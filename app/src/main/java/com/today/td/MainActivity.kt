package com.today.td

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.DatePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), TodayAdapter.Callbacks {
    private val vm: TodayViewModel by viewModels()
    private val adapter = TodayAdapter(this)
    private val uninstallQueue = ArrayDeque<String>()
    private var dialog: AlertDialog? = null
    private var pendingPerm = false

    private lateinit var dateView: TextView
    private lateinit var messageView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refreshButton: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var chipRoot: TextView
    private lateinit var chipShell: TextView
    private lateinit var chipFiles: TextView
    private lateinit var chipUsage: TextView
    private var pendingSmsIds = emptyList<Long>()
    private lateinit var dayToday: TextView
    private lateinit var dayPick: TextView
    private val jumpButtons = HashMap<Int, TextView>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refresh(askRoot = false) }

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { launchNextUninstall() }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingPerm = false
        val active = isDeviceAdmin()
        vm.setMessage(if (active) "Device admin turned on" else "Device admin request was declined")
        vm.refresh(askRoot = false)
    }

    private val smsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Repo.isDefaultSms(this) && pendingSmsIds.isNotEmpty()) {
            val ids = pendingSmsIds
            busyThen("Deleting messages…") { Repo.deleteSms(applicationContext, ids) }
        } else {
            vm.setMessage("The system blocks SMS deletion until GhostMode is the default SMS app")
        }
    }

    private val accountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh(askRoot = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dateView = findViewById(R.id.date)
        messageView = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        refreshButton = findViewById(R.id.refresh)
        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        swipe.setColorSchemeColors(0xFF1D9BF0.toInt())
        swipe.setOnRefreshListener { vm.refresh(askRoot = false) }
        refreshButton.setOnClickListener { vm.refresh(askRoot = false) }
        buildDayBar()
        buildChips()
        buildJumps()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        val current = vm.state.value
        if (current.appsLoading && current.filesLoading && current.apps.isEmpty() && current.files.isEmpty()) {
            vm.refresh(askRoot = false)
        }
        ensureRuntimePerms()
        if (!getPreferences(MODE_PRIVATE).getBoolean("guide_shown", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("guide_shown", true).apply()
            window.decorView.post { showGuide() }
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (pendingPerm) {
            pendingPerm = false
            vm.refresh(askRoot = false)
        }
    }

    override fun onToggle(kind: Kind, id: String) {
        if (vm.state.value.busy) return
        when (kind) {
            Kind.APP -> vm.toggleApp(id)
            Kind.FILE -> vm.toggleFile(id)
            Kind.ACCOUNT -> vm.toggleAccount(id)
            Kind.CALL -> vm.toggleCall(id)
            Kind.SMS -> vm.toggleSms(id)
        }
    }

    override fun onToggleAll(section: Int) {
        if (vm.state.value.busy) return
        vm.toggleAll(section)
    }

    override fun onDelete(section: Int) {
        if (vm.state.value.busy) return
        when (section) {
            SEC_APPS -> confirmApps()
            SEC_FILES -> confirmFiles()
            SEC_ACCOUNTS -> confirmAccounts()
            SEC_CALLS -> confirmCalls()
            SEC_SMS -> confirmSms()
        }
    }

    override fun onChrome() {
        val state = vm.state.value
        if (state.busy) return
        if (!state.chromeInstalled) {
            vm.setMessage("Chrome is not installed")
            return
        }
        if (state.shell || state.root) {
            confirm(
                "Chrome history",
                "All Chrome data will be wiped: browsing history, passwords, cookies, and site data.",
                "Clear all"
            ) { busyThen("Wiping Chrome data…") { Repo.clearChrome() } }
            return
        }
        startChromeClear()
    }

    override fun onShowChrome() {
        val state = vm.state.value
        if (state.busy) return
        if (!state.chromeInstalled) {
            vm.setMessage("Chrome is not installed")
            return
        }
        if (state.root) {
            vm.setBusy(true)
            vm.setMessage("Reading Chrome history…")
            lifecycleScope.launch {
                val entries = withContext(Dispatchers.IO) { Repo.loadChromeHistory(applicationContext) }
                vm.setBusy(false)
                vm.setChrome(entries, if (entries.isEmpty()) "No entries in Chrome history" else "Showing ${entries.size} entries")
            }
            return
        }
        startChromeRead()
    }

    override fun onRevealAccounts() {
        if (vm.state.value.busy) return
        try {
            @Suppress("DEPRECATION")
            val intent = AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
            accountLauncher.launch(intent)
        } catch (e: Exception) {
            vm.setMessage("Could not open the account screen")
        }
    }

    override fun onClearUsage(packageName: String) {
        if (vm.state.value.busy) return
        val label = vm.state.value.usage.firstOrNull { it.packageName == packageName }?.label ?: packageName
        confirm(
            "Clear storage",
            "Clear storage for $label?\nThe app stops and its data and cache are deleted.",
            "Clear storage"
        ) { clearUsage(packageName, label) }
    }

    override fun onCopy() {
        if (vm.state.value.busy) return
        val manager = getSystemService(ClipboardManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            manager.setPrimaryClip(ClipData.newPlainText("Copy", "Copy"))
            vm.setClipDone(true)
            vm.setMessage("Clipboard cleared, then the word Copy was written")
        } catch (e: Exception) {
            vm.setMessage("Could not change the clipboard")
        }
    }

    private fun confirmApps() {
        val state = vm.state.value
        val selected = state.apps.filter { it.packageName in state.selectedApps }
        if (selected.isEmpty()) return
        val how = if (state.root) "They will be removed together." else "The system will ask once per app."
        confirm(
            "Remove apps",
            "Remove ${selected.size} app(s) from this device?\n$how\n${preview(selected.map { it.label })}",
            "Remove"
        ) {
            if (state.root) {
                busyThen("Removing apps…") { Repo.uninstallRoot(selected.map { it.packageName }) }
            } else {
                uninstallQueue.clear()
                uninstallQueue.addAll(selected.map { it.packageName })
                vm.setMessage("Confirm each uninstall")
                launchNextUninstall()
            }
        }
    }

    private fun confirmFiles() {
        val state = vm.state.value
        val selected = state.files.filter { it.path in state.selectedFiles }
        if (selected.isEmpty()) return
        confirm(
            "Delete files",
            "Delete ${selected.size} item(s)? This cannot be undone.\n${preview(selected.map { it.name })}",
            "Delete"
        ) {
            busyThen("Deleting…") {
                Repo.deleteFiles(applicationContext, selected.map { it.path }, state.root)
            }
        }
    }

    private fun confirmAccounts() {
        val state = vm.state.value
        val selected = state.accounts.filter { it.key in state.selectedAccounts }
        if (selected.isEmpty()) return
        confirm(
            "Remove accounts",
            "Remove ${selected.size} account(s)?\nOpens device Settings, then Users & accounts, then the selected account. You confirm the last step.\n${preview(selected.map { it.name })}",
            "Remove"
        ) {
            removeAccountsConfirmed(selected, state.root || ShellBridge.rootIdentity())
        }
    }

    private fun removeAccountsConfirmed(selected: List<AccountEntry>, tryRoot: Boolean) {
        vm.setBusy(false)
        vm.setMessage("Removing accounts…")
        lifecycleScope.launch {
            if (tryRoot) {
                val message = withContext(Dispatchers.IO) {
                    Repo.removeAccountsRoot(applicationContext, selected)
                }
                val failed = Regex("failed (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
                if (failed == 0) {
                    vm.setMessage(message)
                    vm.refresh(askRoot = false)
                    return@launch
                }
            }
            removeViaSettings(selected)
        }
    }

    private suspend fun removeViaSettings(selected: List<AccountEntry>, index: Int = 0, removed: Int = 0) {
        if (index >= selected.size) {
            vm.setMessage(
                if (removed == selected.size) "Removed $removed account(s)"
                else "Removed $removed of ${selected.size}. Confirm Remove on the dialog."
            )
            vm.refresh(askRoot = false)
            return
        }
        val entry = selected[index]
        vm.setBusy(false)
        try {
            if (AccountManager.get(this).removeAccountExplicitly(Account(entry.name, entry.type))) {
                removeViaSettings(selected, index + 1, removed + 1)
                return
            }
        } catch (e: Exception) {
            // Google accounts cannot be removed silently.
        }
        if (ChromeAssistService.instance == null && ShellBridge.ready()) {
            withContext(Dispatchers.IO) { ShellBridge.enableChromeAssist() }
            var waited = 0
            while (ChromeAssistService.instance == null && waited < 16) {
                kotlinx.coroutines.delay(250)
                waited++
            }
        }
        vm.setMessage("Opening device Settings, then Users & accounts, then ${entry.name}.")
        if (ChromeAssistService.instance != null) ChromeAssist.beginAccountRemove(entry.name)
        if (!ChromeAssist.openDeviceSettings(this)) {
            ChromeAssist.cancelAccount()
            vm.setMessage("Could not open device Settings")
            return
        }
        lifecycleScope.launch {
            val gone = withTimeoutOrNull(90000) {
                while (true) {
                    kotlinx.coroutines.delay(1500)
                    if (ChromeAssist.accountRemoveClicked) {
                        vm.setMessage("Tap Remove to delete ${entry.name}")
                    }
                    val still = withContext(Dispatchers.IO) {
                        Repo.accountPresent(applicationContext, entry.name, entry.type)
                    }
                    if (!still) return@withTimeoutOrNull true
                }
            } == true
            ChromeAssist.cancelAccount()
            if (gone) {
                removeViaSettings(selected, index + 1, removed + 1)
            } else {
                vm.setMessage("Still there. On ${entry.name} tap Remove account, then tap Remove.")
                vm.refresh(askRoot = false)
            }
        }
    }

    private fun busyThen(status: String, block: () -> String) {
        vm.setBusy(true)
        vm.setMessage(status)
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) { block() }
            vm.setBusy(false)
            finishWork(message)
        }
    }

    private fun launchNextUninstall() {
        val pkg = if (uninstallQueue.isEmpty()) null else uninstallQueue.removeFirst()
        if (pkg == null) {
            vm.setMessage("Uninstall requests finished")
            vm.refresh(askRoot = false)
            return
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        try {
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            launchNextUninstall()
        }
    }

    private fun render(state: UiState) {
        val day = selectedDay(state.pickedDay)
        val todayMode = state.pickedDay == null
        dateView.text = if (todayMode) "Today · ${formatDate(day)}" else formatDate(day)
        dayToday.isEnabled = !state.busy
        dayPick.isEnabled = !state.busy
        dayToday.setTextColor(if (todayMode) 0xFF000000.toInt() else 0xFFE7E9EA.toInt())
        dayToday.background = chipBg(if (todayMode) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), if (todayMode) 0 else 0xFF2F3336.toInt())
        dayPick.text = if (todayMode) "Pick a date" else formatDate(day)
        dayPick.setTextColor(if (todayMode) 0xFFE7E9EA.toInt() else 0xFF000000.toInt())
        dayPick.background = chipBg(if (todayMode) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), if (todayMode) 0xFF2F3336.toInt() else 0)
        val spinning = state.busy || state.appsLoading || state.filesLoading || state.accountsLoading ||
            state.usageLoading || state.callsLoading || state.smsLoading
        progress.isVisible = spinning
        swipe.isRefreshing = (state.appsLoading || state.filesLoading || state.accountsLoading ||
            state.usageLoading || state.callsLoading || state.smsLoading) && !state.busy
        swipe.isEnabled = !state.busy
        refreshButton.isEnabled = !state.busy
        messageView.isVisible = !state.message.isNullOrBlank()
        messageView.text = state.message.orEmpty()
        val adminOn = state.deviceAdmin || state.root
        chipRoot.text = if (adminOn) "Admin: on" else "Admin: needed"
        paintPerm(chipRoot, adminOn)
        chipShell.text = if (state.shell) "Shell: on" else "Shell: needed"
        paintPerm(chipShell, state.shell)
        chipFiles.text = if (state.fileAccess) "Files: allowed" else "Files: needed"
        paintPerm(chipFiles, state.fileAccess)
        chipUsage.text = if (state.usageAccess) "Usage: allowed" else "Usage: needed"
        paintPerm(chipUsage, state.usageAccess)
        jumpButtons[SEC_APPS]?.text = "Apps ${badge(state.appsLoading, state.apps.size)}"
        jumpButtons[SEC_FILES]?.text = "Files ${badge(state.filesLoading, state.files.size)}"
        jumpButtons[SEC_ACCOUNTS]?.text = "Accounts ${badge(state.accountsLoading, state.accounts.size)}"
        jumpButtons[SEC_USAGE]?.text = "Usage ${badge(state.usageLoading, state.usage.size)}"
        adapter.submitList(buildRows(state))
    }

    private fun buildRows(state: UiState): List<Row> {
        val rows = ArrayList<Row>()
        rows += section(
            SEC_APPS,
            "Apps installed today",
            state.apps.size,
            state.appsLoading,
            "Installed on ${formatDate(selectedDay(state.pickedDay))}",
            0xFFE7E9EA.toInt(),
            true,
            state.apps.count { it.packageName in state.selectedApps },
            state.apps.isNotEmpty() && state.apps.all { it.packageName in state.selectedApps },
            state.busy
        )
        if (state.apps.isEmpty()) {
            rows += Row.Empty(SEC_APPS, if (state.appsLoading) "Checking apps…" else "No apps installed on this day")
        } else {
            for (app in state.apps) {
                rows += Row.Check(
                    kind = Kind.APP,
                    id = app.packageName,
                    title = app.label,
                    subtitle = "${formatTime(app.installedAt)} · ${app.source}",
                    selected = app.packageName in state.selectedApps,
                    iconMode = IconMode.APP,
                    iconKey = app.packageName
                )
            }
        }
        val fileNote = when {
            !state.fileAccess -> "Without All files access, some downloads and hidden items may be missing"
            state.filesTruncated -> "Showing the first 3000. Hidden files are listed first"
            else -> "Today's files, plus every hidden file or folder of any date"
        }
        rows += section(
            SEC_FILES,
            "Files and folders today",
            state.files.size,
            state.filesLoading,
            fileNote,
            0xFFE7E9EA.toInt(),
            true,
            state.files.count { it.path in state.selectedFiles },
            state.files.isNotEmpty() && state.files.all { it.path in state.selectedFiles },
            state.busy
        )
        if (state.files.isEmpty()) {
            rows += Row.Empty(SEC_FILES, if (state.filesLoading) "Checking files…" else "No files from today and no hidden files")
        } else {
            for (file in state.files) {
                val kindText = when {
                    file.dir && file.hidden -> "Hidden folder"
                    file.dir -> "Folder"
                    file.hidden -> "Hidden · ${formatSize(file.size)}"
                    else -> formatSize(file.size)
                }
                val whenText = if (file.hidden) formatDayTime(file.time) else formatTime(file.time)
                rows += Row.Check(
                    kind = Kind.FILE,
                    id = file.path,
                    title = file.name,
                    subtitle = "$whenText · $kindText · ${file.parent}",
                    selected = file.path in state.selectedFiles,
                    iconMode = when {
                        file.dir -> IconMode.FOLDER
                        isPreviewable(file.name) -> IconMode.MEDIA
                        else -> IconMode.FILE
                    },
                    iconKey = file.path
                )
            }
        }
        rows += section(
            SEC_ACCOUNTS,
            "Device accounts",
            state.accounts.size,
            state.accountsLoading,
            if (state.root) "Every account. Not filtered by date" else "Device emails and app accounts. A full list needs the shell or root",
            0xFFE7E9EA.toInt(),
            true,
            state.accounts.count { it.key in state.selectedAccounts },
            state.accounts.isNotEmpty() && state.accounts.all { it.key in state.selectedAccounts },
            state.busy
        )
        if (state.accounts.isEmpty()) {
            rows += Row.Empty(
                SEC_ACCOUNTS,
                when {
                    state.accountsLoading -> "Reading accounts…"
                    !state.shell -> "No visible accounts. Tap here to reveal one, or turn on the shell to list all of them"
                    else -> "No accounts on this device"
                }
            )
        } else {
            for (account in state.accounts) {
                rows += Row.Check(
                    kind = Kind.ACCOUNT,
                    id = account.key,
                    title = account.name,
                    subtitle = account.typeLabel,
                    selected = account.key in state.selectedAccounts,
                    iconMode = IconMode.ACCOUNT,
                    iconKey = account.authenticatorPackage ?: account.type
                )
            }
        }
        rows += logSection(
            state,
            SEC_CALLS,
            "Call log",
            state.calls.size,
            state.callsLoading,
            state.calls.count { it.id.toString() in state.selectedCalls },
            state.calls.isNotEmpty() && state.calls.all { it.id.toString() in state.selectedCalls }
        )
        if (!state.callAccess) {
            rows += Row.Empty(SEC_CALLS, "Allow call log access")
        } else if (state.calls.isEmpty()) {
            rows += Row.Empty(SEC_CALLS, if (state.callsLoading) "Reading calls…" else "No calls")
        } else {
            for (call in state.calls) {
                rows += Row.Check(
                    kind = Kind.CALL,
                    id = call.id.toString(),
                    title = call.title,
                    subtitle = "${call.typeLabel} · ${call.duration} · ${formatDayTime(call.at)} · ${call.number}",
                    selected = call.id.toString() in state.selectedCalls,
                    iconMode = IconMode.ACCOUNT,
                    iconKey = call.number
                )
            }
        }
        rows += logSection(
            state,
            SEC_SMS,
            "Messages",
            state.sms.size,
            state.smsLoading,
            state.sms.count { it.id.toString() in state.selectedSms },
            state.sms.isNotEmpty() && state.sms.all { it.id.toString() in state.selectedSms }
        )
        if (!state.smsAccess) {
            rows += Row.Empty(SEC_SMS, "Allow SMS access")
        } else if (state.sms.isEmpty()) {
            rows += Row.Empty(SEC_SMS, if (state.smsLoading) "Reading messages…" else "No messages")
        } else {
            for (message in state.sms) {
                rows += Row.Check(
                    kind = Kind.SMS,
                    id = message.id.toString(),
                    title = message.address,
                    subtitle = "${message.box} · ${formatDayTime(message.at)} · ${message.body}",
                    selected = message.id.toString() in state.selectedSms,
                    iconMode = IconMode.ACCOUNT,
                    iconKey = message.address
                )
            }
        }
        rows += section(
            SEC_CHROME,
            "Chrome history",
            state.chrome.size,
            state.chromeLoading,
            null,
            0xFFE7E9EA.toInt(),
            false,
            0,
            false,
            state.busy
        )
        val chromeDetail = when {
            !state.chromeInstalled -> "Chrome is not installed on this device"
            state.shell || state.root -> "The wipe deletes history, passwords, and site data."
            ChromeAssist.enabled(this) -> "The wipe deletes history, passwords, and site data from the Chrome screen."
            else -> "Follow the setup guide: the Shell chip or accessibility, then wipe all site data."
        }
        rows += Row.Chrome(chromeDetail, !state.busy && state.chromeInstalled)
        if (state.chromeInstalled) {
            if (state.chrome.isEmpty()) {
                val text = when {
                    state.chromeLoading -> "Reading Chrome history…"
                    !state.root -> "Tap Show history. The service reads titles from the Chrome screen"
                    else -> "Could not read Chrome history, or there are no entries"
                }
                rows += Row.Empty(SEC_CHROME, text)
            } else {
                for (item in state.chrome) {
                    rows += Row.History(
                        title = item.title,
                        url = item.url,
                        time = if (item.visitedAt > 0L) formatDayTime(item.visitedAt) else "From the Chrome screen"
                    )
                }
            }
        }
        rows += section(
            SEC_CLIP, "Clipboard", 0, false, null,
            0xFFE7E9EA.toInt(), false, 0, false, state.busy
        )
        rows += Row.Clip(
            detail = "Clears the whole clipboard, then writes the word Copy. Not tied to the selected date.",
            result = if (state.clipDone) "Clipboard now: Copy" else null,
            enabled = !state.busy
        )
        rows += section(
            SEC_USAGE,
            "Apps used today",
            state.usage.size,
            state.usageLoading,
            if (state.usageAccess) "Used on ${formatDate(selectedDay(state.pickedDay))}. Clear deletes the app data" else "Grant usage access from the button above",
            0xFFE7E9EA.toInt(),
            false,
            0,
            false,
            state.busy
        )
        if (state.usage.isEmpty()) {
            val text = when {
                state.usageLoading -> "Reading usage…"
                !state.usageAccess -> "Usage access is not granted"
                else -> "No apps were used on this day"
            }
            rows += Row.Empty(SEC_USAGE, text)
        } else {
            for (item in state.usage) {
                val (label, color, bg) = statusStyle(item.clear)
                rows += Row.Usage(
                    packageName = item.packageName,
                    title = item.label,
                    subtitle = "${formatDuration(item.foregroundMs)} · last used ${formatTime(item.lastUsed)}",
                    status = label,
                    statusColor = color,
                    statusBg = bg,
                    clearEnabled = !state.busy
                )
            }
        }
        return rows
    }

    private fun section(
        id: Int,
        title: String,
        itemCount: Int,
        loading: Boolean,
        subtitle: String?,
        accent: Int,
        selectable: Boolean,
        selected: Int,
        allSelected: Boolean,
        busy: Boolean
    ) = Row.Section(
        id = id,
        title = title,
        count = when {
            id == SEC_CLIP -> ""
            loading && itemCount == 0 -> "…"
            else -> itemCount.toString()
        },
        itemCount = itemCount,
        subtitle = subtitle,
        accent = accent,
        selectable = selectable,
        selected = selected,
        allSelected = allSelected,
        busy = busy
    )

    private fun statusStyle(state: ClearState): Triple<String, Int, Int> = when (state) {
        ClearState.CLEARED -> Triple("Cleared", 0xFFF4212E.toInt(), 0xFF2A0A0C.toInt())
        ClearState.NOT_CLEARED -> Triple("Not cleared", 0xFFFFFFFF.toInt(), 0xFF16181C.toInt())
        ClearState.UNKNOWN -> Triple("Unknown", 0xFF71767B.toInt(), 0xFF16181C.toInt())
        ClearState.INSTALLED_TODAY -> Triple("Installed today", 0xFF1D9BF0.toInt(), 0xFF031A2B.toInt())
    }

    private fun buildDayBar() {
        val bar = findViewById<LinearLayout>(R.id.dayBar)
        dayToday = chip("Today") {
            if (!vm.state.value.busy) vm.setDay(null)
        }
        dayPick = chip("Pick a date") {
            if (!vm.state.value.busy) pickDay()
        }
        bar.addView(dayToday)
        bar.addView(dayPick)
    }

    private fun pickDay() {
        val current = selectedDay(vm.state.value.pickedDay)
        val cal = Calendar.getInstance().apply { timeInMillis = current }
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                vm.setDay(picked)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun buildChips() {
        val perms = findViewById<LinearLayout>(R.id.perms)
        val chipGuide = chip("Setup guide") { showGuide() }
        chipGuide.setTextColor(0xFF000000.toInt())
        chipGuide.background = chipBg(0xFFFFFFFF.toInt())
        perms.addView(chipGuide)
        chipRoot = chip("Admin: needed") { requestAdmin() }
        chipShell = chip("Shell: needed") { requestShell() }
        chipFiles = chip("Files: needed") { requestFiles() }
        chipUsage = chip("Usage: needed") { requestUsage() }
        perms.addView(chipRoot)
        perms.addView(chipShell)
        perms.addView(chipFiles)
        perms.addView(chipUsage)
    }

    private fun buildJumps() {
        val jumps = findViewById<LinearLayout>(R.id.jumps)
        val items = listOf(
            SEC_APPS to "Apps",
            SEC_FILES to "Files",
            SEC_ACCOUNTS to "Accounts",
            SEC_CALLS to "Calls",
            SEC_SMS to "Messages",
            SEC_CHROME to "Chrome",
            SEC_CLIP to "Clipboard",
            SEC_USAGE to "Usage"
        )
        for ((id, label) in items) {
            val view = chip(label) { scrollTo(id) }
            view.setTextColor(0xFFE7E9EA.toInt())
            view.background = chipBg(0xFF000000.toInt(), 0xFF2F3336.toInt())
            jumps.addView(view)
            jumpButtons[id] = view
        }
    }

    private fun chip(text: String, click: () -> Unit): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextColor(0xFFFFFFFF.toInt())
        view.textSize = 13f
        val padH = dp(12)
        val padV = dp(8)
        view.setPadding(padH, padV, padH, padV)
        view.background = chipBg(0xFF000000.toInt(), 0xFF2F3336.toInt())
        view.setOnClickListener { click() }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = dp(8)
        view.layoutParams = params
        return view
    }

    private fun paintPerm(view: TextView, on: Boolean) {
        if (on) {
            view.setTextColor(0xFFFFFFFF.toInt())
            view.background = chipBg(0xFF1D9BF0.toInt())
        } else {
            view.setTextColor(0xFF1D9BF0.toInt())
            view.background = chipBg(0xFF000000.toInt(), 0xFF1D9BF0.toInt())
        }
    }

    private fun chipBg(color: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun scrollTo(section: Int) {
        val position = adapter.currentList.indexOfFirst { it is Row.Section && it.id == section }
        if (position >= 0) {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        }
    }

    private fun requestAdmin(askRoot: Boolean = true) {
        val who = ComponentName(this, TodayAdminReceiver::class.java)
        if (!isDeviceAdmin()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, who)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "GhostMode needs device admin."
                )
            }
            try {
                pendingPerm = true
                adminLauncher.launch(intent)
            } catch (e: Exception) {
                pendingPerm = false
                vm.setMessage("Could not open the device admin screen")
            }
        } else {
            vm.setMessage("Device admin is on")
        }
        if (askRoot) vm.requestRoot()
    }

    private fun isDeviceAdmin(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(ComponentName(this, TodayAdminReceiver::class.java))
    }

    private fun clearUsage(pkg: String, label: String) {
        vm.setBusy(true)
        vm.setMessage("Clearing data for $label…")
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) { Repo.clearAppData(applicationContext, pkg) }
            vm.setBusy(false)
            finishWork(message)
        }
    }

    private fun requestFiles() {
        pendingPerm = true
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                return
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun requestUsage() {
        pendingPerm = true
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun ensureRuntimePerms() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.GET_ACCOUNTS
        }
        for (permission in arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            "android.permission.WRITE_SMS"
        )) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                need += permission
            }
        }
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    private fun logSection(
        state: UiState,
        id: Int,
        title: String,
        count: Int,
        loading: Boolean,
        selected: Int,
        allSelected: Boolean
    ) = section(
        id,
        title,
        count,
        loading,
        "All of them. Not filtered by date",
        0xFFE7E9EA.toInt(),
        true,
        selected,
        allSelected,
        state.busy
    )

    private fun confirmCalls() {
        val state = vm.state.value
        val selected = state.calls.filter { it.id.toString() in state.selectedCalls }
        if (selected.isEmpty()) return
        confirm(
            "Delete calls",
            "Delete ${selected.size} call(s) from the device log?",
            "Delete"
        ) {
            busyThen("Deleting calls…") {
                Repo.deleteCalls(applicationContext, selected.map { it.id })
            }
        }
    }

    private fun confirmSms() {
        val state = vm.state.value
        val selected = state.sms.filter { it.id.toString() in state.selectedSms }
        if (selected.isEmpty()) return
        pendingSmsIds = selected.map { it.id }
        confirm(
            "Delete messages",
            "Delete ${selected.size} message(s)? If the system blocks it, GhostMode asks to be the SMS app just long enough to delete them.",
            "Delete"
        ) {
            busyThen("Deleting messages…") {
                Repo.deleteSms(applicationContext, pendingSmsIds)
            }
        }
    }

    private fun finishWork(message: String) {
        when (message) {
            Repo.NEED_SHELL -> requestShell()
            Repo.NEED_SMS_ROLE -> requestSmsRole()
            Repo.NEED_CHROME -> startChromeClear()
            Repo.NEED_ADMIN -> {
                vm.setMessage("Device admin was not accepted")
                requestAdmin(askRoot = false)
            }
            else -> {
                vm.setMessage(message)
                vm.refresh(askRoot = false)
            }
        }
    }

    private fun showGuide() {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
        }
        fun heading(text: String) {
            column.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(0, dp(14), 0, dp(6))
            })
        }
        fun body(text: String) {
            column.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFFE7E9EA.toInt())
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        fun action(text: String, click: () -> Unit) {
            val button = chip(text) {
                dialog?.dismiss()
                click()
            }
            button.setTextColor(0xFFFFFFFF.toInt())
            button.background = chipBg(0xFF1D9BF0.toInt())
            (button.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            column.addView(button)
        }
        heading("1. Developer options")
        body("Settings → About phone → tap Build number seven times until Developer options appear.")
        action("Open developer options") { openDeveloperSettings() }
        heading("2. Wireless debugging")
        body("In Developer options, turn on Wireless debugging. Tap it, then Pair device with pairing code, and leave that screen open. A 6-digit code and a port appear.")
        heading("3. Install Shizuku")
        body("Shizuku is what clears app storage, lists every account, and wipes Chrome, without root.")
        action("Install Shizuku") { ShellBridge.openInstall(this) }
        heading("4. Start Shizuku")
        body("Open Shizuku → Start → Pairing. Enter the pairing code and port from the wireless debugging screen, then tap Start. Repeat this after every reboot.")
        action("Open Shizuku") { ShellBridge.openApp(this) }
        heading("5. Grant shell to GhostMode")
        body("Come back here, tap the Shell chip, and allow it. A blue chip means it is on, and Clear then wipes the app's data.")
        action("Grant shell") {
            if (ShellBridge.installed(this) && ShellBridge.running()) {
                ShellBridge.requestPermission { granted ->
                    runOnUiThread {
                        vm.setMessage(if (granted) "Shell access granted" else "Shell was not granted. Make sure Shizuku is running")
                        if (granted) vm.refresh(askRoot = false)
                    }
                }
            } else {
                vm.setMessage("Start Shizuku from step 4, then come back")
                showGuide()
            }
        }
        heading("6. Chrome: history and passwords")
        body("Clear history works through the shell. Show history turns on accessibility. If you see Restricted settings: Settings → Apps → GhostMode → the three dots → Allow restricted settings, then turn GhostMode on in Accessibility.")
        action("Open accessibility") {
            pendingPerm = true
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                vm.setMessage("Could not open accessibility settings")
            }
        }
        heading("7. Messages")
        body("When you delete a message, allow GhostMode to become the SMS app. Deletion runs after that. You can switch back from Settings → Default apps.")
        heading("8. Accounts")
        body("Allow contacts when the app asks, so email accounts can appear. If an account stays hidden, tap the accounts row or turn on the shell to list all of them.")
        val scroll = ScrollView(this).apply {
            addView(column)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Setup guide")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                vm.setMessage("Open Developer options from Settings")
            } catch (e2: Exception) {
                vm.setMessage("Open Settings, then Developer options")
            }
        }
    }

    private fun requestShell() {
        if (ShellBridge.granted()) {
            vm.setMessage("Shell access is on")
            vm.refresh(askRoot = false)
            return
        }
        if (ShellBridge.installed(this) && ShellBridge.running()) {
            ShellBridge.requestPermission { granted ->
                runOnUiThread {
                    vm.setMessage(if (granted) "Shell access granted" else "Shell access was not granted")
                    if (granted) vm.refresh(askRoot = false) else showGuide()
                }
            }
            return
        }
        showGuide()
    }

    private fun requestSmsRole() {
        val intent = smsRoleIntent()
        if (intent == null) {
            vm.setMessage("This system does not allow changing the SMS app")
            return
        }
        try {
            smsRoleLauncher.launch(intent)
        } catch (e: Exception) {
            vm.setMessage("Could not open the SMS app picker")
        }
    }

    private fun askAccessibility(body: String) {
        confirm("Accessibility", body, "App page") {
            pendingPerm = true
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            details.data = Uri.parse("package:$packageName")
            try {
                startActivity(details)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e2: Exception) {
                    vm.setMessage("Could not open settings")
                }
            }
        }
    }

    private fun startChromeRead() {
        vm.setBusy(true)
        vm.setMessage("Preparing Chrome history…")
        lifecycleScope.launch {
            if (!ChromeAssist.enabled(this@MainActivity) && ShellBridge.ready()) {
                withContext(Dispatchers.IO) { ShellBridge.enableChromeAssist() }
            }
            var waited = 0
            while (ChromeAssistService.instance == null && waited < 16) {
                kotlinx.coroutines.delay(250)
                waited++
            }
            if (ChromeAssistService.instance == null) {
                vm.setBusy(false)
                askAccessibility(
                    "Android blocks sideloaded apps from accessibility and shows Restricted settings.\n" +
                        "Settings → Apps → GhostMode → the menu at the top → Allow restricted settings.\n" +
                        "Then go back to Accessibility, turn GhostMode on, and tap Show history."
                )
                return@launch
            }
            vm.setMessage("Opening Chrome history…")
            ChromeAssist.beginRead { entries, note ->
                runOnUiThread {
                    vm.setBusy(false)
                    vm.setChrome(entries, note)
                }
            }
            if (!ChromeAssist.openHistory(this@MainActivity)) {
                ChromeAssist.cancel()
                vm.setBusy(false)
                vm.setMessage("Could not open Chrome history")
            }
        }
    }

    private fun startChromeClear() {
        if (!ChromeAssist.enabled(this)) {
            askAccessibility("Open Settings, then Accessibility, then Installed apps, then GhostMode, and turn it on. The wipe then deletes history, passwords, and site data.")
            return
        }
        confirm(
            "Chrome history",
            "Chrome will open and delete browsing data for all time: history, passwords, cookies, and site data.",
            "Clear all"
        ) {
            vm.setBusy(true)
            vm.setMessage("Clearing Chrome from its own screen…")
            ChromeAssist.beginClear { note ->
                runOnUiThread {
                    vm.setBusy(false)
                    vm.setMessage(note)
                    vm.refresh(askRoot = false)
                }
            }
            if (!ChromeAssist.openClear(this)) {
                ChromeAssist.cancel()
                vm.setBusy(false)
                vm.setMessage("Could not open Chrome's clear-data page")
            }
        }
    }

    private fun confirm(title: String, body: String, ok: String, action: () -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(ok) { _, _ -> action() }
            .show()
    }

    private fun preview(names: List<String>): String {
        val head = names.take(6).joinToString("\n") { "• $it" }
        val more = names.size - 6
        return if (more > 0) "$head\nand $more more" else head
    }

    private fun badge(loading: Boolean, count: Int) = if (loading && count == 0) "…" else count.toString()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
