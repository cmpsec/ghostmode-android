package com.today.td

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

object RootShell {
    private const val TAG = "Today"
    private val lock = Any()
    private var scriptDir: File? = null

    @Volatile
    var cached: Boolean? = null

    @Volatile
    var lastDetail: String = ""

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var suPath: String? = null

    fun init(context: Context) {
        scriptDir = context.applicationContext.cacheDir
    }

    fun invalidate() {
        cached = null
        cachedAt = 0L
        suPath = null
    }

    fun isAvailable(forcePrompt: Boolean, timeoutMs: Long = if (forcePrompt) 15000L else 4000L): Boolean {
        val now = System.currentTimeMillis()
        if (!forcePrompt && cached != null && now - cachedAt < 60_000) {
            return cached == true
        }
        val out = synchronized(lock) { probe(timeoutMs) }
        lastDetail = out
        val ok = out.contains("uid=0")
        cached = ok
        cachedAt = System.currentTimeMillis()
        Log.i(TAG, "root=$ok ${out.take(200)}")
        return ok
    }

    fun runCommand(command: String, timeoutMs: Long): String = synchronized(lock) {
        execSu(command, timeoutMs)
    }

    fun runScript(body: String, timeoutMs: Long): String {
        val dir = scriptDir ?: return "NO_DIR"
        val file = File(dir, "td-${System.nanoTime()}.sh")
        return try {
            synchronized(lock) {
                file.writeText("#!/system/bin/sh\n$body\n")
                execSu("sh ${shellQuote(file.absolutePath)}", timeoutMs)
            }
        } finally {
            file.delete()
        }
    }

    private fun probe(timeoutMs: Long): String {
        var last = "NO_SU"
        for (bin in suCandidates()) {
            val direct = exec(arrayOf(bin, "-c", "id"), timeoutMs)
            if (direct.contains("uid=0")) {
                suPath = bin
                return direct
            }
            if (needsInteractive(direct)) {
                val interactive = execInteractive(bin, "id", timeoutMs)
                if (interactive.contains("uid=0")) {
                    suPath = bin
                    return interactive
                }
                if (interactive != "NO_SU") {
                    suPath = bin
                    last = interactive
                }
            } else if (direct != "NO_SU") {
                suPath = bin
                return direct
            }
        }
        return last
    }

    private fun execSu(command: String, timeoutMs: Long): String {
        val binaries = suPath?.let { listOf(it) } ?: suCandidates()
        if (binaries.isEmpty()) return "NO_SU"
        var last = "NO_SU"
        for (bin in binaries) {
            val direct = exec(arrayOf(bin, "-c", command), timeoutMs)
            if (!needsInteractive(direct)) {
                if (direct != "NO_SU") suPath = bin
                return direct
            }
            val interactive = execInteractive(bin, command, timeoutMs)
            if (interactive != "NO_SU") {
                suPath = bin
                return interactive
            }
            last = interactive
        }
        return last
    }

    private fun suCandidates(): List<String> {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su"
        )
        val found = paths.filter { File(it).exists() }
        return if (found.isEmpty()) listOf("su") else found
    }

    private fun needsInteractive(out: String): Boolean {
        return out == "NO_SU" || out.contains("not found") || out.contains("invalid option") || out.startsWith("Usage:")
    }

    private fun exec(argv: Array<String>, timeoutMs: Long): String {
        return try {
            val process = ProcessBuilder(*argv).start()
            collect(process, timeoutMs)
        } catch (e: Exception) {
            "NO_SU"
        }
    }

    private fun execInteractive(binary: String, command: String, timeoutMs: Long): String {
        return try {
            val process = Runtime.getRuntime().exec(binary)
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { pump(process.inputStream, out) }
            val tErr = Thread { pump(process.errorStream, err) }
            tOut.start()
            tErr.start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(command)
                writer.write("\nexit\n")
                writer.flush()
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return "TIMEOUT"
            }
            tOut.join(1500)
            tErr.join(1500)
            (out.toString() + "\n" + err.toString()).trim()
        } catch (e: Exception) {
            "NO_SU"
        }
    }

    private fun collect(process: Process, timeoutMs: Long): String {
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { pump(process.inputStream, out) }
        val tErr = Thread { pump(process.errorStream, err) }
        tOut.start()
        tErr.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            tOut.join(300)
            tErr.join(300)
            return "TIMEOUT\n$out\n$err"
        }
        tOut.join(1500)
        tErr.join(1500)
        return (out.toString() + "\n" + err.toString()).trim()
    }

    private fun pump(input: InputStream, into: StringBuilder) {
        try {
            val buf = ByteArray(8192)
            var total = 0
            while (total < 2_000_000) {
                val n = input.read(buf)
                if (n < 0) break
                val take = minOf(n, 2_000_000 - total)
                into.append(String(buf, 0, take, Charsets.UTF_8))
                total += take
            }
        } catch (e: Exception) {
            into.append(e.message ?: "")
        }
    }
}
