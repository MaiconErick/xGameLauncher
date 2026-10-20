package com.maiconerick.xgamelauncher.tracker

import com.maiconerick.xgamelauncher.util.RootShell
import java.io.InputStream
import java.io.OutputStream

/**
 * One long-lived root shell per tracking session, used to ask "is this package's process still
 * alive?". A game that is killed (swiped from recents, or one that quits by ending its own
 * process, like TFT) may never log ACTIVITY_STOPPED, so UsageStats alone keeps it "visible"
 * forever. A single `su` per session means a single Magisk toast, not one per check.
 *
 * Every read has a deadline: a pending su prompt must never stall the watch loop.
 */
class RootProbe {
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null
    @Volatile private var disabled = false

    /** Packages from [pkgs] that have a running process; null when it can't tell (no root). */
    fun alive(pkgs: Collection<String>): Set<String>? {
        if (disabled || pkgs.isEmpty()) return null
        if (!RootShell.haveRoot()) { disabled = true; return null }
        return try {
            if (proc == null) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master"))
                proc = p; stdin = p.outputStream; stdout = p.inputStream
            }
            // One process list; a package counts as alive with its main process or any "pkg:sub" one.
            stdin!!.write("ps -A -o NAME=; echo __END__\n".toByteArray()); stdin!!.flush()
            val text = readUntil("__END__", 4_000) ?: run { close(); disabled = true; return null }
            val names = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (names.size < 10) return null // not a real process list: don't guess
            pkgs.filter { p -> names.any { it == p || it.startsWith("$p:") } }.toSet()
        } catch (_: Exception) {
            close(); null
        }
    }

    private fun readUntil(marker: String, timeoutMs: Long): String? {
        val ins = stdout ?: return null
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = ins.available()
            if (n > 0) {
                val r = ins.read(buf, 0, minOf(n, buf.size))
                if (r < 0) return null
                sb.append(String(buf, 0, r))
                if (sb.contains(marker)) return sb.toString()
            } else {
                try { Thread.sleep(25) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    fun close() {
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        proc = null; stdin = null; stdout = null
    }
}
