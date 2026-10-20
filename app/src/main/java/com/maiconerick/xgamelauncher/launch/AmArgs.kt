package com.maiconerick.xgamelauncher.launch

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.maiconerick.xgamelauncher.data.IntentExtra
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.util.RootShell

/**
 * Converts between Daijishō-style `am start` argument text and [LaunchIntent], and turns a
 * [LaunchIntent] into a real [Intent] (or an `am start` shell line for the root fallback).
 *
 * Daijishō format (platforms/<Name>.json → playerList[].amStartArguments), one option per line:
 *   -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
 *   -e ROM {file.path}
 *   -e LIBRETRO mednafen_psx_hw
 *   -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
 *   --activity-clear-task
 * Tokens are split BEFORE template substitution, so paths with spaces stay one argument.
 */
object AmArgs {

    data class ParseResult(val intent: LaunchIntent, val warnings: List<String>)

    /** am extra switches → our [IntentExtra.type]. `-e` is an alias of `--es`. */
    private val EXTRA_SWITCHES = mapOf(
        "-e" to "es", "--es" to "es", "--ez" to "ez", "--ei" to "ei", "--el" to "el",
        "--ef" to "ef", "--eu" to "eu", "--esa" to "esa", "--eia" to "eia", "--ela" to "ela",
        "--efa" to "efa", "--ecn" to "ecn",
    )

    val EXTRA_TYPES = listOf(
        "es" to "String", "ez" to "Boolean", "ei" to "Int", "el" to "Long", "ef" to "Float",
        "eu" to "Uri", "esa" to "String[]", "eia" to "Int[]", "ela" to "Long[]",
        "efa" to "Float[]", "ecn" to "Component", "esn" to "Null",
    )

    /** `--activity-*` / `--grant-*` switches and the Intent flag each one sets. */
    val FLAG_SWITCHES: Map<String, Int> = mapOf(
        "--activity-brought-to-front" to Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT,
        "--activity-clear-top" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
        "--activity-clear-task" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
        "--activity-clear-when-task-reset" to Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET,
        "--activity-exclude-from-recents" to Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        "--activity-launched-from-history" to Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        "--activity-multiple-task" to Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
        "--activity-no-animation" to Intent.FLAG_ACTIVITY_NO_ANIMATION,
        "--activity-no-history" to Intent.FLAG_ACTIVITY_NO_HISTORY,
        "--activity-no-user-action" to Intent.FLAG_ACTIVITY_NO_USER_ACTION,
        "--activity-previous-is-top" to Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP,
        "--activity-reorder-to-front" to Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        "--activity-reset-task-if-needed" to Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        "--activity-single-top" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
        "--activity-task-on-home" to Intent.FLAG_ACTIVITY_TASK_ON_HOME,
        "--activity-match-external" to 0x00000800,
        "--grant-read-uri-permission" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
        "--grant-write-uri-permission" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        "--grant-persistable-uri-permission" to Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        "--grant-prefix-uri-permission" to Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        "--exclude-stopped-packages" to Intent.FLAG_EXCLUDE_STOPPED_PACKAGES,
        "--include-stopped-packages" to Intent.FLAG_INCLUDE_STOPPED_PACKAGES,
        "--debug-log-resolution" to Intent.FLAG_DEBUG_LOG_RESOLUTION,
    )

    /** The flags people actually toggle — shown as switches in the player editor. */
    val COMMON_FLAGS = listOf(
        "--activity-clear-task", "--activity-clear-top", "--activity-no-history",
        "--activity-single-top", "--activity-no-animation",
    )

    // ---------------------------------------------------------------- tokenize / parse

    /** Shell-ish split: whitespace separates, "double" and 'single' quotes group, and quoted
     *  pieces glued to neighbours concatenate ("-r","{x}" → -r,{x}). Backslash escapes the
     *  next char outside single quotes. */
    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> when {
                    c == quote -> quote = null
                    c == '\\' && quote == '"' && i + 1 < s.length &&
                        (s[i + 1] == '"' || s[i + 1] == '\\') -> { cur.append(s[i + 1]); i++ }
                    else -> cur.append(c)
                }
                c == '"' || c == '\'' -> { quote = c; inToken = true }
                c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++; inToken = true }
                c.isWhitespace() -> if (inToken) { out += cur.toString(); cur.clear(); inToken = false }
                else -> { cur.append(c); inToken = true }
            }
            i++
        }
        if (inToken) out += cur.toString()
        return out
    }

    /**
     * Parses am-start argument text onto [base] (keeps id / name). Anything we
     * can't map lands in `warnings` instead of being silently dropped.
     */
    fun parse(args: String, base: LaunchIntent): ParseResult {
        val t = tokenize(args)
        val warnings = ArrayList<String>()
        var component: String? = null
        var pkg: String? = null
        var action: String? = null
        var data: String? = null
        var mime: String? = null
        val cats = ArrayList<String>()
        val extras = ArrayList<IntentExtra>()
        val flags = ArrayList<String>()
        var flagsHex: String? = null
        var kill = base.killBefore
        var i = 0
        fun next(opt: String): String? =
            if (i + 1 < t.size) t[++i] else { warnings += "$opt is missing its value"; null }
        while (i < t.size) {
            val tok = t[i]
            when {
                tok == "am" || tok == "start" || tok == "start-activity" -> Unit
                tok == "-n" -> next(tok)?.let { component = it; pkg = it.substringBefore('/') }
                tok == "-p" -> next(tok)?.let { pkg = it }
                tok == "-a" -> action = next(tok)
                tok == "-d" -> data = next(tok)
                tok == "-t" -> mime = next(tok)
                tok == "-c" -> next(tok)?.let { cats += it }
                tok == "-f" -> flagsHex = next(tok)
                tok == "--esn" -> next(tok)?.let { extras += IntentExtra("esn", it, "") }
                tok in EXTRA_SWITCHES -> {
                    val key = next(tok)
                    val value = next(tok)
                    if (key != null && value != null) extras += IntentExtra(EXTRA_SWITCHES.getValue(tok), key, value)
                }
                tok in FLAG_SWITCHES -> if (tok !in flags) flags += tok
                tok == "-S" -> kill = true
                tok == "-W" || tok == "-D" || tok == "-N" -> Unit
                tok == "--user" || tok == "--display" || tok == "-R" || tok == "-P" ||
                    tok == "--start-profiler" || tok == "--windowingMode" ||
                    tok == "--activityType" -> { next(tok) }
                tok.startsWith("-") -> warnings += "Unsupported option: $tok"
                // A bare trailing argument is am's <URI> form — treat it as data.
                else -> if (data == null) data = tok else warnings += "Extra argument ignored: $tok"
            }
            i++
        }
        if (component == null && pkg == null) warnings += "No -n component — the player needs one to launch."
        return ParseResult(
            base.copy(
                component = component, pkg = pkg, action = action, dataTemplate = data,
                mimeType = mime, categories = cats, extras = extras, flags = flags,
                flagsHex = flagsHex, killBefore = kill,
            ),
            warnings,
        )
    }

    /** One option per line, same shape Daijishō uses (so it can be pasted back there). */
    fun format(li: LaunchIntent): String = buildList {
        val comp = li.component?.takeIf { it.isNotBlank() }
        if (comp != null) add("-n ${q(comp)}")
        else li.pkg?.takeIf { it.isNotBlank() }?.let { add("-p ${q(it)}") }
        li.action?.takeIf { it.isNotBlank() }?.let { add("-a ${q(it)}") }
        li.dataTemplate?.takeIf { it.isNotBlank() }?.let { add("-d ${q(it)}") }
        li.mimeType?.takeIf { it.isNotBlank() }?.let { add("-t ${q(it)}") }
        li.categories.forEach { add("-c ${q(it)}") }
        li.extras.filter { it.key.isNotBlank() }.forEach { e ->
            add(
                when (e.type) {
                    "es" -> "-e ${q(e.key)} ${q(e.value)}"
                    "esn" -> "--esn ${q(e.key)}"
                    else -> "--${e.type} ${q(e.key)} ${q(e.value)}"
                }
            )
        }
        li.flagsHex?.takeIf { it.isNotBlank() }?.let { add("-f $it") }
        li.flags.forEach { add(it) }
    }.joinToString("\n")

    /** Quotes only when needed so the text stays readable. */
    private fun q(s: String): String =
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' })
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        else s

    // ---------------------------------------------------------------- build

    data class Built(val intent: Intent, val contentUris: List<Uri>)

    /** Resolves a [LaunchIntent] + template vars into a startable Intent. Null when the
     *  player names neither a component nor a package. */
    fun toIntent(li: LaunchIntent, vars: LaunchVars): Built? {
        val pkg = li.targetPackage()
        if (li.component.isNullOrBlank() && pkg == null) return null
        val out = Intent()
        val grants = ArrayList<Uri>()
        li.action?.takeIf { it.isNotBlank() }?.let { out.action = vars.fill(it) }
        val comp = li.component?.takeIf { it.isNotBlank() }?.let { expandComponent(it, pkg) }
        if (comp != null) out.setClassName(comp.first, comp.second)
        else if (pkg != null) out.setPackage(pkg)
        val data = li.dataTemplate?.takeIf { it.isNotBlank() }?.let { Uri.parse(vars.fill(it)) }
        val mime = li.mimeType?.takeIf { it.isNotBlank() }?.let { vars.fill(it) }
        when {
            data != null && mime != null -> out.setDataAndType(data, mime)
            data != null -> out.data = data
            mime != null -> out.type = mime
        }
        if (data?.scheme == "content") grants += data
        li.categories.forEach { out.addCategory(vars.fill(it)) }
        for (e in li.extras) {
            if (e.key.isBlank()) continue
            val key = vars.fill(e.key)
            val v = expandValue(e, pkg, vars)
            when (e.type) {
                "ez" -> out.putExtra(key, v.equals("true", true) || v == "1")
                "ei" -> v.trim().toIntOrNull()?.let { out.putExtra(key, it) }
                "el" -> v.trim().toLongOrNull()?.let { out.putExtra(key, it) }
                "ef" -> v.trim().toFloatOrNull()?.let { out.putExtra(key, it) }
                "eu" -> out.putExtra(key, Uri.parse(v))
                "esa" -> out.putExtra(key, splitArray(v).toTypedArray())
                "eia" -> out.putExtra(key, splitArray(v).mapNotNull { it.trim().toIntOrNull() }.toIntArray())
                "ela" -> out.putExtra(key, splitArray(v).mapNotNull { it.trim().toLongOrNull() }.toLongArray())
                "efa" -> out.putExtra(key, splitArray(v).mapNotNull { it.trim().toFloatOrNull() }.toFloatArray())
                "ecn" -> android.content.ComponentName.unflattenFromString(v)?.let { out.putExtra(key, it) }
                "esn" -> out.putExtra(key, null as String?)
                else -> out.putExtra(key, v)
            }
            // SAF uris passed as string extras (DuckStation bootPath, EmuCoreX GAME_PATH…)
            // only become readable by the emulator if we grant them through ClipData.
            if (v.startsWith("content://")) grants += Uri.parse(v)
        }
        li.flags.forEach { f -> FLAG_SWITCHES[f]?.let { out.addFlags(it) } }
        li.flagsHex?.takeIf { it.isNotBlank() }?.let { parseFlags(it) }?.let { out.addFlags(it) }
        out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (grants.isNotEmpty()) {
            val clip = ClipData.newRawUri("", grants.first())
            grants.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            out.clipData = clip
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return Built(out, grants)
    }

    /** Filled-in `am start …` line, shell-quoted — used for the root fallback and previews. */
    fun toShell(li: LaunchIntent, vars: LaunchVars): String {
        val pkg = li.targetPackage()
        val parts = ArrayList<String>()
        parts += listOf("am", "start")
        li.component?.takeIf { it.isNotBlank() }?.let { c ->
            val (p, cls) = expandComponent(c, pkg) ?: return@let
            parts += listOf("-n", "$p/$cls")
        } ?: pkg?.let { parts += listOf("-p", it) }
        li.action?.takeIf { it.isNotBlank() }?.let { parts += listOf("-a", vars.fill(it)) }
        li.dataTemplate?.takeIf { it.isNotBlank() }?.let { parts += listOf("-d", vars.fill(it)) }
        li.mimeType?.takeIf { it.isNotBlank() }?.let { parts += listOf("-t", vars.fill(it)) }
        li.categories.forEach { parts += listOf("-c", vars.fill(it)) }
        li.extras.filter { it.key.isNotBlank() }.forEach { e ->
            val key = vars.fill(e.key)
            if (e.type == "esn") parts += listOf("--esn", key)
            else parts += listOf(if (e.type == "es") "--es" else "--${e.type}", key, expandValue(e, pkg, vars))
        }
        li.flagsHex?.takeIf { it.isNotBlank() }?.let { parts += listOf("-f", it) }
        parts += li.flags
        return parts.joinToString(" ") { if (it.all { c -> c.isLetterOrDigit() || c in "-_./:=,{}" }) it else RootShell.q(it) }
    }

    /** "pkg/.Cls" → (pkg, pkg.Cls); "pkg/cls" → (pkg, cls); bare "cls" → (fallbackPkg, cls). */
    fun expandComponent(c: String, fallbackPkg: String?): Pair<String, String>? {
        val slash = c.indexOf('/')
        return if (slash > 0) {
            val p = c.substring(0, slash)
            var cls = c.substring(slash + 1)
            if (cls.startsWith('.')) cls = p + cls
            p to cls
        } else {
            val p = fallbackPkg ?: return null
            p to (if (c.startsWith('.')) p + c else c)
        }
    }

    /**
     * Template fill plus Daijishō's RetroArch shorthand: `-e LIBRETRO mednafen_psx_hw` means
     * `/data/data/<retroarch pkg>/cores/mednafen_psx_hw_libretro_android.so`.
     */
    private fun expandValue(e: IntentExtra, pkg: String?, vars: LaunchVars): String {
        val v = vars.fill(e.value)
        if (e.key == "LIBRETRO" && pkg != null && v.isNotBlank() && '/' !in v) {
            val core = v.removeSuffix(".so").removeSuffix("_libretro_android").removeSuffix("_libretro")
            return "/data/data/$pkg/cores/${core}_libretro_android.so"
        }
        return v
    }

    /** am splits arrays on commas not preceded by a backslash. */
    private fun splitArray(v: String): List<String> =
        v.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }

    private fun parseFlags(s: String): Int? {
        val t = s.trim()
        return if (t.startsWith("0x", true)) t.substring(2).toLongOrNull(16)?.toInt()
        else t.toLongOrNull()?.toInt()
    }
}
