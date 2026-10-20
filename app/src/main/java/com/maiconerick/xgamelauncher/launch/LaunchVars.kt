package com.maiconerick.xgamelauncher.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.Platform
import java.io.File

/**
 * Template variables for player arguments. Supports Daijishō's tokens plus our legacy ones:
 *   {file.path} {file.uri} {file.name} {file.basename} {file.ext} {file.dir} {file.mime}
 *   {tags.<key>} — from a Daijishō Player Template (.dpt) game file: lines "[key] value"
 *   {game.name} {platform.name}
 *   legacy: {path} {uri} {id} {name} {platform}
 * Unknown tokens are left untouched so typos are visible in the command preview.
 */
class LaunchVars(private val values: Map<String, String>) {

    fun fill(s: String): String =
        TOKEN.replace(s) { m -> values[m.groupValues[1]] ?: m.value }

    operator fun get(key: String): String? = values[key]

    companion object {
        private val TOKEN = Regex("\\{([A-Za-z0-9_.\\-]+)\\}")

        val TOKENS_HELP = listOf(
            "{file.path}" to "Absolute path — most emulators, RetroArch ROM",
            "{file.uri}" to "SAF content:// uri (needs ROM folder access) — DuckStation, ARMSX2",
            "{file.name}" to "File name with extension",
            "{file.basename}" to "File name without extension",
            "{file.dir}" to "Folder containing the file",
            "{file.mime}" to "Mime type guessed from the extension",
            "{game.name}" to "Display name",
            "{tags.KEY}" to "Value of [KEY] in a .dpt template file",
        )

        fun forGame(ctx: Context, platform: Platform, game: GameEntry): LaunchVars {
            val f = File(game.filePath)
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            // SAF uri when a ROM-folder grant covers the file (like Daijishō); otherwise our own
            // FileProvider uri — emulators can't open a bare file:// (ARMSX2, NetherSX2 hang).
            val uri = (RomUris.contentUriFor(ctx, f) ?: RomUris.providerUriFor(ctx, f))?.toString()
                ?: Uri.fromFile(f).toString()
            val m = linkedMapOf(
                "file.path" to f.absolutePath,
                "file.uri" to uri,
                "file.name" to f.name,
                "file.basename" to f.nameWithoutExtension,
                "file.nameWithoutExtension" to f.nameWithoutExtension,
                "file.ext" to ext,
                "file.extension" to ext,
                "file.dir" to (f.parent ?: ""),
                "file.mime" to mime,
                "game.name" to game.name,
                "platform.name" to platform.name,
                "path" to f.absolutePath,
                "uri" to uri,
                "id" to f.nameWithoutExtension,
                "name" to game.name,
                "platform" to platform.name,
            )
            if (ext == "dpt") readDpt(f).forEach { (k, v) -> m["tags.$k"] = v }
            return LaunchVars(m)
        }

        /** Placeholder values for previews in the editor (no real game at hand). */
        fun sample(platform: Platform?): LaunchVars {
            val dir = platform?.romsPath?.trimEnd('/')?.ifBlank { null } ?: com.maiconerick.xgamelauncher.rom.RomFolders.ROMS_ROOT
            val p = "$dir/Example Game.chd"
            return LaunchVars(
                mapOf(
                    "file.path" to p, "path" to p,
                    "file.uri" to "content://…/Example%20Game.chd", "uri" to "content://…",
                    "file.name" to "Example Game.chd", "file.basename" to "Example Game",
                    "file.dir" to dir, "file.ext" to "chd", "file.mime" to "application/octet-stream",
                    "game.name" to "Example Game", "name" to "Example Game",
                    "platform.name" to (platform?.name ?: ""), "platform" to (platform?.name ?: ""),
                )
            )
        }

        /** Daijishō Player Template: first line "# Daijishou Player Template" or "# DST",
         *  then "[key] value" lines. */
        private fun readDpt(f: File): Map<String, String> = runCatching {
            val lines = f.readLines()
            val head = lines.firstOrNull()?.trim().orEmpty()
            if (!head.startsWith("# Daijishou Player Template", true) && !head.equals("# DST", true)) {
                return@runCatching emptyMap()
            }
            lines.drop(1).mapNotNull { line ->
                val m = Regex("^\\s*\\[([^\\]]+)]\\s*(.*)$").find(line) ?: return@mapNotNull null
                m.groupValues[1].trim() to m.groupValues[2].trim()
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * Builds SAF content uris for ROM files, the way Daijishō's {file.uri} does. Needs a
 * persisted tree grant (Settings → Devices → ROM folder access) covering the file; the
 * resulting tree-document uri is then re-granted to the emulator through ClipData.
 */
object RomUris {
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    fun grantedTrees(ctx: Context): List<Uri> =
        ctx.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.authority == EXTERNAL_AUTHORITY }
            .map { it.uri }
            .filter { runCatching { DocumentsContract.getTreeDocumentId(it) }.isSuccess }

    /** "primary:ROMS" → "/storage/emulated/0/ROMS"; "1234-ABCD:Games" → "/storage/1234-ABCD/Games". */
    fun treePath(tree: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val vol = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val root = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    fun contentUriFor(ctx: Context, file: File): Uri? {
        val path = normalize(file.absolutePath)
        val best = grantedTrees(ctx)
            .mapNotNull { t -> treePath(t)?.let { t to it } }
            .filter { (_, root) -> path.startsWith("$root/") }
            .maxByOrNull { (_, root) -> root.length } ?: return null
        val vol = DocumentsContract.getTreeDocumentId(best.first).substringBefore(':')
        val volRoot = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        val docId = "$vol:" + path.removePrefix("$volRoot/")
        return DocumentsContract.buildDocumentUriUsingTree(best.first, docId)
    }

    /** content://<pkg>.roms/… for any readable file (granted to the emulator per launch). */
    fun providerUriFor(ctx: Context, file: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.roms", file)
    }.getOrNull()

    fun persist(ctx: Context, tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.recoverCatching {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun release(ctx: Context, tree: Uri) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.recoverCatching {
            ctx.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun normalize(p: String): String = when {
        p.startsWith("/sdcard/") -> "/storage/emulated/0/" + p.removePrefix("/sdcard/")
        p.startsWith("/storage/self/primary/") -> "/storage/emulated/0/" + p.removePrefix("/storage/self/primary/")
        else -> p
    }
}
