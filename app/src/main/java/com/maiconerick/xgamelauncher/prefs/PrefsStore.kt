package com.maiconerick.xgamelauncher.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xgl_prefs")

class PrefsStore(private val context: Context) {

    /** Once the user has imported xPTT sessions into our Room db, don't double-count by reading
     *  xPTT's sqlite as a separate source anymore. Flip this to true after successful import. */
    val skipXpttRead: Flow<Boolean> =
        context.dataStore.data.map { it[K_SKIP_XPTT] ?: false }

    /** When on, TrackerService uploads xgl.db to Dropbox after each session close. */
    val dropboxBackup: Flow<Boolean> =
        context.dataStore.data.map { it[K_DROPBOX_BACKUP] ?: false }

    val deviceName: Flow<String> =
        context.dataStore.data.map {
            it[K_DEVICE_NAME] ?: com.maiconerick.xgamelauncher.ui.DeviceProfile.defaultDeviceName()
        }

    /** UI profile: "auto" (detect from screen + RAM), "full" (Thor-style) or "compact"
     *  (small 4:3 / low-RAM handhelds like the RG353M). See [com.maiconerick.xgamelauncher.ui.DeviceProfile]. */
    val uiProfile: Flow<String> =
        context.dataStore.data.map { it[K_UI_PROFILE] ?: "auto" }

    /** Play pixel_start/pixel_finish cues on game/lid events. */
    val soundFxEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K_SOUND_FX] ?: true }

    /** Root of the on-device platform-icon library (walked by the icon picker). */
    val platformIconsRoot: Flow<String> =
        context.dataStore.data.map { it[K_ICONS_ROOT] ?: DEFAULT_ICONS_ROOT }

    /** Show floating "Tracking / Lid closed / …" overlay on top of the running game. */
    val overlayEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K_OVERLAY] ?: true }

    val rawgApiKey: Flow<String> =
        context.dataStore.data.map { it[K_RAWG_KEY] ?: "" }

    val igdbClientId: Flow<String> =
        context.dataStore.data.map { it[K_IGDB_ID] ?: "" }

    val igdbClientSecret: Flow<String> =
        context.dataStore.data.map { it[K_IGDB_SECRET] ?: "" }

    val steamGridDbKey: Flow<String> = context.dataStore.data.map { it[K_SGDB] ?: "" }
    val screenScraperUser: Flow<String> = context.dataStore.data.map { it[K_SS_USER] ?: "" }
    val screenScraperPass: Flow<String> = context.dataStore.data.map { it[K_SS_PASS] ?: "" }
    val xpttDbPath: Flow<String> = context.dataStore.data.map {
        it[K_XPTT_DB] ?: DEFAULT_XPTT_DB
    }

    /** Serialized `AppTabsData` — user-defined tabs on the Apps screen + per-tab package
     *  membership. See [com.maiconerick.xgamelauncher.ui.apptabs.AppTabsData]. */
    val appTabsJson: Flow<String> = context.dataStore.data.map { it[K_APP_TABS] ?: "" }

    /** Apps grid tile size in dp — controls both the icon container and the row density. */
    val appsIconSizeDp: Flow<Int> = context.dataStore.data.map { it[K_APPS_ICON_SIZE] ?: 108 }

    /** Apps grid column count. 0 = adaptive (uses [appsIconSizeDp]); >0 = fixed columns. */
    val appsColumns: Flow<Int> = context.dataStore.data.map { it[K_APPS_COLUMNS] ?: 0 }

    /** Default display target for app launches from the Apps tab. "top" or "bottom". */
    val appsDefaultDisplay: Flow<String> = context.dataStore.data.map { it[K_APPS_DISPLAY] ?: "top" }

    /** Center apps in the grid (so a partial last row aligns center-x). */
    val appsCenterGrid: Flow<Boolean> = context.dataStore.data.map { it[K_APPS_CENTER_GRID] ?: false }

    /** Center the tab-pill row (instead of left-aligning it under the launch chip). */
    val appsCenterTabs: Flow<Boolean> = context.dataStore.data.map { it[K_APPS_CENTER_TABS] ?: false }

    suspend fun setSkipXpttRead(v: Boolean) =
        context.dataStore.edit { it[K_SKIP_XPTT] = v }

    suspend fun setDropboxBackup(v: Boolean) =
        context.dataStore.edit { it[K_DROPBOX_BACKUP] = v }

    suspend fun setDeviceName(v: String) =
        context.dataStore.edit { it[K_DEVICE_NAME] = v }

    /** Platforms the user deleted. Discovered ones (Daijishō, Windows, Android) would otherwise
     *  be recreated on every refresh, so deletion = hide. */
    val hiddenPlatformIds: Flow<Set<String>> =
        context.dataStore.data.map { it[K_HIDDEN_PLATFORMS] ?: emptySet() }

    suspend fun hidePlatform(id: String) =
        context.dataStore.edit { it[K_HIDDEN_PLATFORMS] = (it[K_HIDDEN_PLATFORMS] ?: emptySet()) + id }

    suspend fun unhidePlatform(id: String) =
        context.dataStore.edit { it[K_HIDDEN_PLATFORMS] = (it[K_HIDDEN_PLATFORMS] ?: emptySet()) - id }

    /** Show controller button hints in the bottom bar (on by default). */
    val showButtonHints: Flow<Boolean> =
        context.dataStore.data.map { it[K_SHOW_HINTS] ?: true }

    suspend fun setShowButtonHints(v: Boolean) =
        context.dataStore.edit { it[K_SHOW_HINTS] = v }

    /** Last player used per game (Windows variant file path, or a platform player id). */
    suspend fun lastPlayer(gameId: String): String? =
        context.dataStore.data.map { it[stringPreferencesKey("last_player_$gameId")] }.first()

    suspend fun setLastPlayer(gameId: String, key: String) =
        context.dataStore.edit { it[stringPreferencesKey("last_player_$gameId")] = key }

    /** Global grid: columns without the info panel — this is what sets the tile size. */
    val gridColumns: Flow<Int> = context.dataStore.data.map { it[K_GRID_COLS] ?: 5 }
    /** Columns while the panel is open: 0 = auto (as many as fit at the same tile size). */
    val panelColumns: Flow<Int> = context.dataStore.data.map { it[K_PANEL_COLS] ?: 0 }
    suspend fun setGridColumns(v: Int) = context.dataStore.edit { it[K_GRID_COLS] = v }

    /** Game tile width in dp for the platform grid (0 = derived from [gridColumns]). */
    val gridTileDp: Flow<Int> = context.dataStore.data.map { it[K_GRID_TILE_DP] ?: 0 }
    suspend fun setGridTileDp(v: Int) = context.dataStore.edit { it[K_GRID_TILE_DP] = v }
    suspend fun setPanelColumns(v: Int) = context.dataStore.edit { it[K_PANEL_COLS] = v }

    /** Focus border colours: base line + travelling highlight. "" = accent / white. */
    val focusColorA: Flow<String> = context.dataStore.data.map { it[K_FOCUS_A] ?: "" }
    val focusColorB: Flow<String> = context.dataStore.data.map { it[K_FOCUS_B] ?: "" }
    suspend fun setFocusColors(a: String, b: String) =
        context.dataStore.edit { it[K_FOCUS_A] = a; it[K_FOCUS_B] = b }

    /** Bottom strip: show platforms as icons (true) or names (false). */
    val stripUseIcons: Flow<Boolean> = context.dataStore.data.map { it[K_STRIP_ICONS] ?: false }
    suspend fun setStripUseIcons(v: Boolean) = context.dataStore.edit { it[K_STRIP_ICONS] = v }

    /** Home hub: platform background fills the whole area between the bars (no card/border). */
    val hubFullBleed: Flow<Boolean> = context.dataStore.data.map { it[K_HUB_FULL_BLEED] ?: false }
    suspend fun setHubFullBleed(v: Boolean) = context.dataStore.edit { it[K_HUB_FULL_BLEED] = v }

    /** Home hub: PLAYTIME expanded to today / week / month / year (remembered). */
    val hubPlaytimeExpanded: Flow<Boolean> = context.dataStore.data.map { it[K_HUB_PT_EXPANDED] ?: false }
    suspend fun setHubPlaytimeExpanded(v: Boolean) = context.dataStore.edit { it[K_HUB_PT_EXPANDED] = v }

    /** Per-platform backdrop behind the hub logo + stats: platformId → "kind|strength". */
    val heroBackdrops: Flow<Map<String, com.maiconerick.xgamelauncher.ui.components.TextBackdrop>> =
        context.dataStore.data.map { p ->
            runCatching {
                val o = org.json.JSONObject(p[K_HERO_BACKDROPS] ?: "{}")
                o.keys().asSequence().associateWith { com.maiconerick.xgamelauncher.ui.components.TextBackdrop.decode(o.getString(it)) }
            }.getOrDefault(emptyMap())
        }
    suspend fun setHeroBackdrop(platformId: String, b: com.maiconerick.xgamelauncher.ui.components.TextBackdrop) =
        context.dataStore.edit { p ->
            val o = runCatching { org.json.JSONObject(p[K_HERO_BACKDROPS] ?: "{}") }.getOrDefault(org.json.JSONObject())
            if (b.kind == com.maiconerick.xgamelauncher.ui.components.TextBackdrop.NONE) o.remove(platformId) else o.put(platformId, b.encode())
            p[K_HERO_BACKDROPS] = o.toString()
        }

    /** Bottom strip icon size, 0..1 → [stripIconDp]. */
    val stripIconSize: Flow<Float> = context.dataStore.data.map { it[K_STRIP_ICON_SIZE] ?: 0.2f }
    suspend fun setStripIconSize(v: Float) = context.dataStore.edit { it[K_STRIP_ICON_SIZE] = v.coerceIn(0f, 1f) }

    /** Bottom strip: small per-platform icon next to each name. */
    val stripMiniIcons: Flow<Boolean> = context.dataStore.data.map { it[K_STRIP_MINI] ?: false }
    suspend fun setStripMiniIcons(v: Boolean) = context.dataStore.edit { it[K_STRIP_MINI] = v }

    /** Per-platform mini icon paths (platformId → image path), stored as JSON. */
    val miniIcons: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        runCatching {
            val o = org.json.JSONObject(p[K_MINI_ICONS] ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }
    suspend fun setMiniIcon(platformId: String, path: String?) = context.dataStore.edit { p ->
        val o = runCatching { org.json.JSONObject(p[K_MINI_ICONS] ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (path.isNullOrBlank()) o.remove(platformId) else o.put(platformId, path)
        p[K_MINI_ICONS] = o.toString()
    }

    /** Per-platform bottom-strip icon paths (platformId → image path), stored as JSON. */
    val barIcons: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        runCatching {
            val o = org.json.JSONObject(p[K_BAR_ICONS] ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }
    suspend fun setBarIcon(platformId: String, path: String?) = context.dataStore.edit { p ->
        val o = runCatching { org.json.JSONObject(p[K_BAR_ICONS] ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (path.isNullOrBlank()) o.remove(platformId) else o.put(platformId, path)
        p[K_BAR_ICONS] = o.toString()
    }

    val navSounds: Flow<Boolean> = context.dataStore.data.map { it[K_NAV_SOUNDS] ?: false }
    val navVolume: Flow<Int> = context.dataStore.data.map { it[K_NAV_VOLUME] ?: 50 }
    suspend fun setNavSounds(v: Boolean) = context.dataStore.edit { it[K_NAV_SOUNDS] = v }
    suspend fun setNavVolume(v: Int) = context.dataStore.edit { it[K_NAV_VOLUME] = v }

    /** User renames for Android apps (pkg → name), shown everywhere in the launcher. */
    val appNames: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        runCatching {
            val o = org.json.JSONObject(p[K_APP_NAMES] ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }
    suspend fun setAppName(pkg: String, name: String?) = context.dataStore.edit { p ->
        val o = runCatching { org.json.JSONObject(p[K_APP_NAMES] ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (name.isNullOrBlank()) o.remove(pkg) else o.put(pkg, name)
        p[K_APP_NAMES] = o.toString()
    }

    /** Ask the display for its highest refresh rate (Thor: 120Hz). */
    val highRefresh: Flow<Boolean> = context.dataStore.data.map { it[K_HIGH_REFRESH] ?: false }
    suspend fun setHighRefresh(v: Boolean) = context.dataStore.edit { it[K_HIGH_REFRESH] = v }

    /** Focus animation kind (see FocusStyle.KINDS). */
    val focusKind: Flow<String> = context.dataStore.data.map { it[K_FOCUS_KIND] ?: "snake" }
    suspend fun setFocusKind(v: String) = context.dataStore.edit { it[K_FOCUS_KIND] = v }

    /** Bar background effect: kind + speed/amount (0..1). */
    val barFxKind: Flow<String> = context.dataStore.data.map { it[K_BARFX_KIND] ?: "none" }
    val barFxSpeed: Flow<Float> = context.dataStore.data.map { it[K_BARFX_SPEED] ?: 0.35f }
    val barFxAmount: Flow<Float> = context.dataStore.data.map { it[K_BARFX_AMOUNT] ?: 0.5f }
    suspend fun setBarFx(kind: String, speed: Float, amount: Float) = context.dataStore.edit {
        it[K_BARFX_KIND] = kind; it[K_BARFX_SPEED] = speed; it[K_BARFX_AMOUNT] = amount
    }

    // ---- Themes -----------------------------------------------------------------------
    /** Active theme id (see ui/theme/AppTheme.kt): "neon" | "halloween" | "halloween_amoled". */
    val themeId: Flow<String> = context.dataStore.data.map { it[K_THEME] ?: "neon" }
    suspend fun setThemeId(v: String) {
        context.getSharedPreferences("xgl_ui", Context.MODE_PRIVATE).edit().putString("theme_id", v).apply()
        context.dataStore.edit { it[K_THEME] = v }
    }

    /**
     * Focus + bar animation settings are kept per theme group, so every theme has its own.
     * "neon" uses the original keys (the user's choices from before themes existed); other
     * groups prefix them ("halloween_focus_kind", …) and start from the theme's defaults.
     */
    private fun gk(group: String, base: String) = if (group == "neon") base else "${group}_$base"

    fun focusKindOf(group: String, default: String): Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey(gk(group, "focus_kind"))] ?: default }
    suspend fun setFocusKindOf(group: String, v: String) =
        context.dataStore.edit { it[stringPreferencesKey(gk(group, "focus_kind"))] = v }
    fun focusColorAOf(group: String): Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey(gk(group, "focus_color_a"))] ?: "" }
    fun focusColorBOf(group: String): Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey(gk(group, "focus_color_b"))] ?: "" }
    suspend fun setFocusColorsOf(group: String, a: String, b: String) = context.dataStore.edit {
        it[stringPreferencesKey(gk(group, "focus_color_a"))] = a
        it[stringPreferencesKey(gk(group, "focus_color_b"))] = b
    }
    fun barFxKindOf(group: String, default: String): Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey(gk(group, "barfx_kind"))] ?: default }
    fun barFxSpeedOf(group: String): Flow<Float> = context.dataStore.data.map {
        it[androidx.datastore.preferences.core.floatPreferencesKey(gk(group, "barfx_speed"))] ?: 0.35f
    }
    fun barFxAmountOf(group: String): Flow<Float> = context.dataStore.data.map {
        it[androidx.datastore.preferences.core.floatPreferencesKey(gk(group, "barfx_amount"))] ?: 0.5f
    }
    suspend fun setBarFxOf(group: String, kind: String, speed: Float, amount: Float) = context.dataStore.edit {
        it[stringPreferencesKey(gk(group, "barfx_kind"))] = kind
        it[androidx.datastore.preferences.core.floatPreferencesKey(gk(group, "barfx_speed"))] = speed
        it[androidx.datastore.preferences.core.floatPreferencesKey(gk(group, "barfx_amount"))] = amount
    }

    // ---- Tools tab --------------------------------------------------------------------
    /** Generic per-tool flags/strings (keys prefixed "tool_"), so tools don't each need API. */
    fun toolFlag(key: String, default: Boolean): Flow<Boolean> =
        context.dataStore.data.map { it[androidx.datastore.preferences.core.booleanPreferencesKey("tool_$key")] ?: default }
    fun toolString(key: String, default: String): Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey("tool_$key")] ?: default }
    suspend fun setToolFlag(key: String, v: Boolean) =
        context.dataStore.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("tool_$key")] = v }
    suspend fun setToolString(key: String, v: String) =
        context.dataStore.edit { it[stringPreferencesKey("tool_$key")] = v }

    suspend fun setUiProfile(v: String) =
        context.dataStore.edit { it[K_UI_PROFILE] = v }

    suspend fun setSoundFxEnabled(v: Boolean) =
        context.dataStore.edit { it[K_SOUND_FX] = v }

    suspend fun setPlatformIconsRoot(v: String) =
        context.dataStore.edit { it[K_ICONS_ROOT] = v }

    suspend fun setOverlayEnabled(v: Boolean) =
        context.dataStore.edit { it[K_OVERLAY] = v }

    suspend fun setRawgApiKey(v: String) =
        context.dataStore.edit { it[K_RAWG_KEY] = v }

    suspend fun setIgdbCreds(clientId: String, clientSecret: String) =
        context.dataStore.edit { it[K_IGDB_ID] = clientId; it[K_IGDB_SECRET] = clientSecret }

    suspend fun setSgdbKey(v: String) = context.dataStore.edit { it[K_SGDB] = v }
    suspend fun setScreenScraperCreds(user: String, pass: String) = context.dataStore.edit {
        it[K_SS_USER] = user; it[K_SS_PASS] = pass
    }
    suspend fun setXpttDbPath(v: String) = context.dataStore.edit { it[K_XPTT_DB] = v }

    suspend fun setAppTabsJson(v: String) = context.dataStore.edit { it[K_APP_TABS] = v }
    suspend fun setAppsIconSizeDp(v: Int) = context.dataStore.edit { it[K_APPS_ICON_SIZE] = v }
    suspend fun setAppsColumns(v: Int) = context.dataStore.edit { it[K_APPS_COLUMNS] = v }
    suspend fun setAppsDefaultDisplay(v: String) = context.dataStore.edit { it[K_APPS_DISPLAY] = v }
    suspend fun setAppsCenterGrid(v: Boolean) = context.dataStore.edit { it[K_APPS_CENTER_GRID] = v }
    suspend fun setAppsCenterTabs(v: Boolean) = context.dataStore.edit { it[K_APPS_CENTER_TABS] = v }

    companion object {
        private val K_SKIP_XPTT =
            androidx.datastore.preferences.core.booleanPreferencesKey("skip_xptt_read")
        private val K_DROPBOX_BACKUP =
            androidx.datastore.preferences.core.booleanPreferencesKey("dropbox_backup")
        private val K_DEVICE_NAME = stringPreferencesKey("device_name")
        private val K_THEME = stringPreferencesKey("theme_id")
        private val K_UI_PROFILE = stringPreferencesKey("ui_profile")
        private val K_FOCUS_KIND = stringPreferencesKey("focus_kind")
        private val K_BARFX_KIND = stringPreferencesKey("barfx_kind")
        private val K_BARFX_SPEED = androidx.datastore.preferences.core.floatPreferencesKey("barfx_speed")
        private val K_BARFX_AMOUNT = androidx.datastore.preferences.core.floatPreferencesKey("barfx_amount")
        private val K_HIGH_REFRESH = androidx.datastore.preferences.core.booleanPreferencesKey("high_refresh")
        private val K_APP_NAMES = stringPreferencesKey("app_names_json")
        private val K_NAV_SOUNDS = androidx.datastore.preferences.core.booleanPreferencesKey("nav_sounds")
        private val K_NAV_VOLUME = androidx.datastore.preferences.core.intPreferencesKey("nav_volume")
        private val K_STRIP_ICONS = androidx.datastore.preferences.core.booleanPreferencesKey("strip_use_icons")
        private val K_BAR_ICONS = stringPreferencesKey("bar_icons_json")
        private val K_GRID_TILE_DP = androidx.datastore.preferences.core.intPreferencesKey("grid_tile_dp")
        private val K_HUB_PT_EXPANDED = androidx.datastore.preferences.core.booleanPreferencesKey("hub_playtime_expanded")
        private val K_HUB_FULL_BLEED = androidx.datastore.preferences.core.booleanPreferencesKey("hub_full_bleed")
        private val K_HERO_BACKDROPS = stringPreferencesKey("hero_backdrops_json")
        private val K_STRIP_ICON_SIZE = androidx.datastore.preferences.core.floatPreferencesKey("strip_icon_size")
        private val K_STRIP_MINI = androidx.datastore.preferences.core.booleanPreferencesKey("strip_mini_icons")
        private val K_MINI_ICONS = stringPreferencesKey("mini_icons_json")

        /** Strip icon height for a 0..1 slider value: 14dp .. 64dp. */
        fun stripIconDp(v: Float): Float = 14f + v.coerceIn(0f, 1f) * 50f
        private val K_FOCUS_A = stringPreferencesKey("focus_color_a")
        private val K_FOCUS_B = stringPreferencesKey("focus_color_b")
        private val K_GRID_COLS = androidx.datastore.preferences.core.intPreferencesKey("grid_columns")
        private val K_PANEL_COLS = androidx.datastore.preferences.core.intPreferencesKey("panel_columns")
        private val K_SHOW_HINTS =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_button_hints")
        private val K_HIDDEN_PLATFORMS =
            androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_platform_ids")
        private val K_SOUND_FX =
            androidx.datastore.preferences.core.booleanPreferencesKey("sound_fx_enabled")
        private val K_ICONS_ROOT = stringPreferencesKey("platform_icons_root")
        private val K_OVERLAY =
            androidx.datastore.preferences.core.booleanPreferencesKey("overlay_enabled")
        private val K_RAWG_KEY = stringPreferencesKey("rawg_api_key")
        private val K_IGDB_ID = stringPreferencesKey("igdb_client_id")
        private val K_IGDB_SECRET = stringPreferencesKey("igdb_client_secret")
        const val DEFAULT_ICONS_ROOT = "/storage/emulated/0/xGameLauncher/platform_icons"
        private val K_SGDB = stringPreferencesKey("steamgriddb_key")
        private val K_SS_USER = stringPreferencesKey("screenscraper_user")
        private val K_SS_PASS = stringPreferencesKey("screenscraper_pass")
        private val K_XPTT_DB = stringPreferencesKey("xptt_db_path")
        private val K_APP_TABS = stringPreferencesKey("app_tabs_json")
        private val K_APPS_ICON_SIZE = androidx.datastore.preferences.core.intPreferencesKey("apps_icon_size_dp")
        private val K_APPS_COLUMNS = androidx.datastore.preferences.core.intPreferencesKey("apps_columns")
        private val K_APPS_DISPLAY = stringPreferencesKey("apps_default_display")
        private val K_APPS_CENTER_GRID = androidx.datastore.preferences.core.booleanPreferencesKey("apps_center_grid")
        private val K_APPS_CENTER_TABS = androidx.datastore.preferences.core.booleanPreferencesKey("apps_center_tabs")

        const val DEFAULT_XPTT_DB =
            "/storage/emulated/0/MaiconErick/PlayTime/DB/PlayTime_THOR.db"
    }
}
