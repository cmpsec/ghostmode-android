package com.today.td

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

object ShellBridge {
    private const val TAG = "Today"
    const val SHIZUKU = "moe.shizuku.privileged.api"

    fun ready(): Boolean = RootShell.cached == true || granted()

    fun installed(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(SHIZUKU, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(SHIZUKU, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun running(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun rootIdentity(): Boolean {
        if (RootShell.cached == true) return true
        return try {
            granted() && Shizuku.getUid() == 0
        } catch (e: Throwable) {
            false
        }
    }

    fun granted(): Boolean {
        return try {
            running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission(done: (Boolean) -> Unit) {
        if (!running()) {
            done(false)
            return
        }
        if (granted()) {
            done(true)
            return
        }
        val code = 1401
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != code) return
                Shizuku.removeRequestPermissionResultListener(this)
                done(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            Log.e(TAG, "shizuku permission", e)
            done(false)
        }
    }

    fun openApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openInstall(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU")).addFlags(flags)
            )
        } catch (e: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")).addFlags(flags)
            )
        }
    }

    fun enableChromeAssist(): Boolean {
        if (!ready()) return false
        val service = "com.today.td/com.today.td.ChromeAssistService"
        val dollar = '$'
        val script = buildString {
            append("appops set com.today.td ACCESS_RESTRICTED_SETTINGS allow\n")
            append("appops set com.today.td android:access_restricted_settings allow\n")
            append("cur=\$(settings get secure enabled_accessibility_services)\n")
            append("svc=$service\n")
            append("case \"${dollar}cur\" in\n")
            append("*\"${dollar}svc\"*) ;;\n")
            append("\"\"|null) settings put secure enabled_accessibility_services \"${dollar}svc\" ;;\n")
            append("*) settings put secure enabled_accessibility_services \"${dollar}cur:${dollar}svc\" ;;\n")
            append("esac\n")
            append("settings put secure accessibility_enabled 1\n")
            append("settings get secure enabled_accessibility_services\n")
        }
        return run(script, 15000).contains(service)
    }

    fun run(script: String, timeoutMs: Long = 20000): String {
        if (RootShell.cached == true) return RootShell.runScript(script, timeoutMs)
        if (!granted()) return "NO_SHELL"
        return try {
            val process = startShell()
            val out = StringBuilder()
            val err = StringBuilder()
            val stdout = Thread {
                try {
                    out.append(process.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                    Log.w(TAG, "shell out", e)
                }
            }
            val stderr = Thread {
                try {
                    err.append(process.errorStream.bufferedReader().readText())
                } catch (e: Exception) {
                    Log.w(TAG, "shell err", e)
                }
            }
            stdout.start()
            stderr.start()
            process.outputStream.use { stream ->
                stream.write(script.toByteArray())
                stream.flush()
            }
            waitFor(process, timeoutMs)
            stdout.join(1500)
            stderr.join(1500)
            (out.toString() + "\n" + err.toString()).trim()
        } catch (e: Exception) {
            Log.e(TAG, "shell", e)
            "NO_SHELL"
        }
    }

    private fun startShell(): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh"), null, null) as Process
    }

    private fun waitFor(process: Process, timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                process.exitValue()
                return
            } catch (e: IllegalThreadStateException) {
                try {
                    Thread.sleep(40)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        }
        process.destroy()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                process.waitFor(1, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            process.destroy()
        }
    }
}
