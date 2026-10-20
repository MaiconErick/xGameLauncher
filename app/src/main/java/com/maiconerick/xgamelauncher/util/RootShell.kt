package com.maiconerick.xgamelauncher.util

import java.io.ByteArrayOutputStream

data class Su(val code: Int, val out: ByteArray, val err: String) {
    val outText: String get() = String(out)
}

object RootShell {

    /** Runs `cmd` under `su --mount-master -c` so other apps' /data/data dirs are visible. */
    fun run(cmd: String): Su {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", cmd))
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val ins = p.inputStream
            var n: Int
            while (ins.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
            val be = ByteArrayOutputStream()
            val es = p.errorStream
            while (es.read(buf).also { n = it } > 0) be.write(buf, 0, n)
            val err = String(be.toByteArray())
            val code = p.waitFor()
            Su(code, bo.toByteArray(), err)
        } catch (t: Throwable) {
            Su(-1, ByteArray(0), "su failed: ${t.message}")
        }
    }

    @Volatile private var rootCached: Boolean? = null

    /** Cached per process — every `su` call pops a Magisk toast/prompt, so ask once. */
    fun haveRoot(): Boolean {
        rootCached?.let { return it }
        val r = run("id")
        return (r.code == 0 && r.outText.contains("uid=0")).also { rootCached = it }
    }

    /** Shell-quote a path. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
