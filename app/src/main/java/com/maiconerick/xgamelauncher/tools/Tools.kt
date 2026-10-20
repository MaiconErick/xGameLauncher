package com.maiconerick.xgamelauncher.tools

/**
 * Tools tab registry. Each tool has an enable toggle (row, left side) and its own settings
 * page. Keys map to PrefsStore.toolFlag / toolString.
 */
enum class Tool(val id: String, val title: String, val subtitle: String, val defaultEnabled: Boolean) {
    Roms("roms", "ROM downloader", "romgi catalog — download games straight into a platform's folder", true),
    Watcher("watcher", "Folder → Dropbox", "Upload new files from a folder to Dropbox, then delete them", false),
    Clipboard("clipboard", "Clipboard", "Shared clipboard history with your PC (via the Pi hub)", false);

    val enabledKey: String get() = "${id}_enabled"

    companion object {
        fun byId(id: String?) = values().firstOrNull { it.id == id }
    }
}

object ToolKeys {
    const val ROM_ROOT = "roms_root"              // default download folder root
    const val ROM_EXTRACT = "roms_extract"        // unzip .zip downloads
    const val WATCH_FOLDER = "watcher_folder"
    const val WATCH_DEST = "watcher_dest"         // Dropbox folder
    const val WATCH_DELETE = "watcher_delete"
    const val CLIP_HUB = "clipboard_hub"
    const val CLIP_AUTOCOPY = "clipboard_autocopy"

    const val DEFAULT_WATCH_DEST = "/xGameLauncher/Uploads"
    const val DEFAULT_HUB = "http://192.168.1.128:8768"
    const val FALLBACK_HUB = "http://100.64.0.128:8768"
}
