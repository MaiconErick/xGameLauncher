package com.maiconerick.xgamelauncher.util

import java.io.BufferedReader
import java.io.OutputStreamWriter

/**
 * One long-lived `su` shell for frequent reads (CPU / FPS stats every couple of seconds).
 * Opening su once means ONE Magisk grant/toast instead of one per poll. Commands are
 * serialized; output is read up to a unique end marker.
 */
object RootSession {
    private var proc: Process? = null
    private var input: OutputStreamWriter? = null
    private var output: BufferedReader? = null
    private var counter = 0

    @Synchronized
    fun exec(cmd: String): String? {
        repeat(2) {
            try {
                val p = proc ?: Runtime.getRuntime().exec(arrayOf("su", "--mount-master")).also {
                    proc = it
                    input = OutputStreamWriter(it.outputStream)
                    output = it.inputStream.bufferedReader()
                }
                if (!p.isAlive) error("dead")
                val marker = "__XGL_END_${++counter}__"
                input!!.write("$cmd 2>/dev/null; echo $marker\n")
                input!!.flush()
                val sb = StringBuilder()
                while (true) {
                    val line = output!!.readLine() ?: error("closed")
                    if (line == marker) break
                    sb.append(line).append('\n')
                }
                return sb.toString()
            } catch (_: Throwable) {
                close()
            }
        }
        return null
    }

    @Synchronized
    fun close() {
        runCatching { proc?.destroy() }
        proc = null; input = null; output = null
    }
}
