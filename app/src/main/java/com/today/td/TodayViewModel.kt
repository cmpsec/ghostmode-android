package com.today.td

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh(askRoot: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val day = selectedDay(_state.value.pickedDay)
            val ask = askRoot
            _state.update {
                it.copy(
                    appsLoading = true,
                    filesLoading = true,
                    accountsLoading = true,
                    usageLoading = true,
                    callsLoading = true,
                    smsLoading = true,
                    filesRootDone = false,
                    accountsRootDone = false,
                    usageRootDone = false,
                    chromeLoading = true,
                    chrome = emptyList(),
                    fileAccess = Repo.hasFileAccess(ctx),
                    usageAccess = Repo.hasUsageAccess(ctx),
                    callAccess = Repo.hasCallAccess(ctx),
                    smsAccess = Repo.hasSmsAccess(ctx),
                    deviceAdmin = Repo.isDeviceAdmin(ctx),
                    shell = ShellBridge.ready()
                )
            }
            supervisorScope {
                launch(Dispatchers.IO) {
                    val apps = Repo.loadApps(ctx, day)
                    if (!isActive) return@launch
                    _state.update { s ->
                        s.copy(
                            apps = apps,
                            appsLoading = false,
                            selectedApps = s.selectedApps.intersect(apps.map { it.packageName }.toSet())
                        )
                    }
                }
                launch(Dispatchers.IO) {
                    val files = Repo.loadFiles(ctx, false)
                    if (!isActive) return@launch
                    _state.update { s ->
                        if (s.filesRootDone) {
                            s.copy(filesLoading = false)
                        } else {
                            s.copy(
                                files = files.list,
                                filesTruncated = files.truncated,
                                filesLoading = false,
                                selectedFiles = s.selectedFiles.intersect(files.list.map { it.path }.toSet())
                            )
                        }
                    }
                }
                launch(Dispatchers.IO) {
                    val accounts = Repo.loadAccounts(ctx, false)
                    if (!isActive) return@launch
                    _state.update { s ->
                        if (s.accountsRootDone) {
                            s.copy(accountsLoading = false)
                        } else {
                            s.copy(
                                accounts = accounts,
                                accountsLoading = false,
                                selectedAccounts = s.selectedAccounts.intersect(accounts.map { it.key }.toSet())
                            )
                        }
                    }
                }
                launch(Dispatchers.IO) {
                    val usage = Repo.loadUsage(ctx, Repo.hasUsageAccess(ctx), day)
                    if (!isActive) return@launch
                    _state.update { s ->
                        if (s.usageRootDone) s.copy(usageLoading = false) else s.copy(usage = usage, usageLoading = false)
                    }
                }
                launch(Dispatchers.IO) {
                    val calls = Repo.loadCalls(ctx)
                    if (!isActive) return@launch
                    _state.update { s ->
                        s.copy(
                            calls = calls,
                            callsLoading = false,
                            selectedCalls = s.selectedCalls.intersect(calls.map { it.id.toString() }.toSet())
                        )
                    }
                }
                launch(Dispatchers.IO) {
                    val sms = Repo.loadSms(ctx)
                    if (!isActive) return@launch
                    _state.update { s ->
                        s.copy(
                            sms = sms,
                            smsLoading = false,
                            selectedSms = s.selectedSms.intersect(sms.map { it.id.toString() }.toSet())
                        )
                    }
                }
                launch(Dispatchers.IO) {
                    val chrome = Repo.isChromeInstalled(ctx)
                    if (!isActive) return@launch
                    _state.update { it.copy(chromeInstalled = chrome) }
                }
            }
            if (!isActive) return@launch
            val root = withContext(Dispatchers.IO) {
                when {
                    ask -> RootShell.isAvailable(true, 45000)
                    RootShell.cached == true -> RootShell.isAvailable(false, 4000)
                    else -> _state.value.root
                }
            }
            if (!isActive) return@launch
            _state.update { it.copy(root = root, shell = ShellBridge.ready(), chromeLoading = root) }
            if (!root) {
                if (ShellBridge.ready()) {
                    val accounts = withContext(Dispatchers.IO) { Repo.loadAccounts(ctx, true) }
                    if (!isActive) return@launch
                    _state.update { s ->
                        s.copy(
                            accounts = accounts,
                            accountsRootDone = true,
                            accountsLoading = false,
                            shell = true,
                            selectedAccounts = s.selectedAccounts.intersect(accounts.map { it.key }.toSet())
                        )
                    }
                }
                return@launch
            }
            val accounts = withContext(Dispatchers.IO) { Repo.loadAccounts(ctx, true) }
            val usage = withContext(Dispatchers.IO) {
                Repo.applyClearFlags(ctx, Repo.loadUsage(ctx, Repo.hasUsageAccess(ctx), day), day)
            }
            val files = withContext(Dispatchers.IO) { Repo.loadFiles(ctx, true) }
            val history = withContext(Dispatchers.IO) { Repo.loadChromeHistory(ctx) }
            if (!isActive) return@launch
            _state.update { s ->
                s.copy(
                    accounts = accounts,
                    accountsRootDone = true,
                    accountsLoading = false,
                    selectedAccounts = s.selectedAccounts.intersect(accounts.map { it.key }.toSet()),
                    usage = usage,
                    usageRootDone = true,
                    usageLoading = false,
                    files = files.list,
                    filesTruncated = files.truncated,
                    filesRootDone = true,
                    filesLoading = false,
                    selectedFiles = s.selectedFiles.intersect(files.list.map { it.path }.toSet()),
                    chrome = history,
                    chromeLoading = false
                )
            }
        }
    }

    fun setChrome(entries: List<ChromeEntry>, note: String) {
        _state.update { it.copy(chrome = entries, chromeLoading = false, message = note) }
    }

    fun setDay(dayStart: Long?) {
        _state.update { it.copy(pickedDay = dayStart?.let { startOfDay(it) }) }
        refresh(askRoot = false)
    }

    fun requestRoot() {
        RootShell.invalidate()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val root = withContext(Dispatchers.IO) { RootShell.isAvailable(true, 45000) }
            _state.update { s ->
                s.copy(
                    root = root,
                    deviceAdmin = Repo.isDeviceAdmin(ctx),
                    message = when {
                        root -> "Root access granted"
                        RootShell.lastDetail.contains("denied", ignoreCase = true) -> "Root access was denied"
                        else -> s.message
                    }
                )
            }
            if (root) refresh(askRoot = false)
        }
    }

    fun toggleApp(id: String) = _state.update { s ->
        s.copy(selectedApps = flip(s.selectedApps, id))
    }

    fun toggleFile(id: String) = _state.update { s ->
        s.copy(selectedFiles = flip(s.selectedFiles, id))
    }

    fun toggleAccount(id: String) = _state.update { s ->
        s.copy(selectedAccounts = flip(s.selectedAccounts, id))
    }

    fun toggleCall(id: String) = _state.update { s ->
        s.copy(selectedCalls = flip(s.selectedCalls, id))
    }

    fun toggleSms(id: String) = _state.update { s ->
        s.copy(selectedSms = flip(s.selectedSms, id))
    }

    fun toggleAll(section: Int) {
        _state.update { s ->
            when (section) {
                SEC_APPS -> {
                    val ids = s.apps.map { it.packageName }.toSet()
                    s.copy(selectedApps = if (ids.isNotEmpty() && s.selectedApps.containsAll(ids)) emptySet() else ids)
                }
                SEC_FILES -> {
                    val ids = s.files.map { it.path }.toSet()
                    s.copy(selectedFiles = if (ids.isNotEmpty() && s.selectedFiles.containsAll(ids)) emptySet() else ids)
                }
                SEC_ACCOUNTS -> {
                    val ids = s.accounts.map { it.key }.toSet()
                    s.copy(selectedAccounts = if (ids.isNotEmpty() && s.selectedAccounts.containsAll(ids)) emptySet() else ids)
                }
                SEC_CALLS -> {
                    val ids = s.calls.map { it.id.toString() }.toSet()
                    s.copy(selectedCalls = if (ids.isNotEmpty() && s.selectedCalls.containsAll(ids)) emptySet() else ids)
                }
                SEC_SMS -> {
                    val ids = s.sms.map { it.id.toString() }.toSet()
                    s.copy(selectedSms = if (ids.isNotEmpty() && s.selectedSms.containsAll(ids)) emptySet() else ids)
                }
                else -> s
            }
        }
    }

    fun setMessage(text: String?) = _state.update { it.copy(message = text) }
    fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy) }
    fun setClipDone(done: Boolean) = _state.update { it.copy(clipDone = done) }

    private fun flip(current: Set<String>, id: String): Set<String> {
        val next = current.toMutableSet()
        if (!next.add(id)) next.remove(id)
        return next
    }
}
