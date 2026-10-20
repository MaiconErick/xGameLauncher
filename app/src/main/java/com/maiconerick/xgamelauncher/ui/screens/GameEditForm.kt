package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import com.maiconerick.xgamelauncher.ui.components.focusRing
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.art.ArtTarget
import com.maiconerick.xgamelauncher.data.GameEntry
import com.maiconerick.xgamelauncher.data.GameOverrideRow
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.components.GhostBufferTextField
import com.maiconerick.xgamelauncher.ui.dialogs.ArtSearchDialog
import com.maiconerick.xgamelauncher.ui.dialogs.ArtSearchResult
import com.maiconerick.xgamelauncher.ui.input.onGamepadActivate
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon

/** Sentinel intent id for "ask each time" chooser — an empty string round-trips through
 *  the DB as "no override" would, so we intentionally store `""` distinct from `null`. */
private const val ASK_INTENT_ID = ""

/**
 * Per-game override editor body. Rendered full-screen by [GameEditScreen] via a route,
 * but self-contained enough to also be dropped into an inline panel if we ever want that
 * back. The caller supplies save/reset/cancel, art search hooks, and picker plumbing;
 * the form handles unsaved-changes prompts on B (see [BackHandler] below).
 */
@Composable
internal fun GameEditForm(
    platform: Platform,
    game: GameEntry,
    initial: GameOverrideRow?,
    androidComponent: String?,
    allPlatforms: List<Platform>,
    onSave: (GameOverrideRow) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    requestPicker: (PickerRequest?) -> Unit,
    pickerShowing: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val neon = LocalNeon.current
    val context = LocalContext.current

    var displayName by remember { mutableStateOf(initial?.displayNameOverride ?: game.name) }
    var intentSel by remember { mutableStateOf<String?>(initial?.launchIntentId) }
    var screenSel by remember { mutableStateOf<String?>(initial?.screenPref) }
    var heroOverride by remember { mutableStateOf(initial?.heroArtOverride ?: "") }
    var boxOverride by remember { mutableStateOf(initial?.boxArtOverride ?: "") }
    var artSearchTarget by remember { mutableStateOf<ArtTarget?>(null) }

    fun buildRow(): GameOverrideRow = GameOverrideRow(
        gameId = game.id,
        launchIntentId = intentSel,
        screenPref = screenSel,
        // game.name is already the renamed title when an override exists, so an unchanged
        // field must keep the override (comparing to game.name used to drop it on save).
        displayNameOverride = displayName.trim().ifBlank { null }?.let { n ->
            if (initial?.displayNameOverride != null) n else n.takeIf { it != game.name }
        },
        boxArtOverride = boxOverride.trim().ifBlank { null },
        heroArtOverride = heroOverride.trim().ifBlank { null },
    )

    fun hasChanges(): Boolean {
        val cur = buildRow()
        val prev = initial ?: GameOverrideRow(gameId = game.id)
        return cur != prev.copy(gameId = game.id)
    }

    fun describeChanges(): String {
        val cur = buildRow()
        val prev = initial ?: GameOverrideRow(gameId = game.id)
        return buildList {
            if (cur.displayNameOverride != prev.displayNameOverride)
                add("Name → “${cur.displayNameOverride ?: game.name}”")
            if (cur.screenPref != prev.screenPref) add("Screen → ${cur.screenPref ?: "default"}")
            if (cur.launchIntentId != prev.launchIntentId) add("Launch player")
            if (cur.heroArtOverride != prev.heroArtOverride) add("Hero art")
            if (cur.boxArtOverride != prev.boxArtOverride) add("Box art")
        }.joinToString("\n") { "• $it" }
    }

    fun requestCancel() {
        if (!hasChanges()) { onCancel(); return }
        requestPicker(
            PickerRequest(
                title = "Save changes?",
                message = describeChanges(),
                options = listOf(
                    PickerOption("save", "Save changes"),
                    PickerOption("discard", "Discard changes"),
                    PickerOption("keep", "Keep editing"),
                ),
                onPick = { id ->
                    requestPicker(null)
                    when (id) {
                        "save" -> onSave(buildRow())
                        "discard" -> onCancel()
                        else -> Unit
                    }
                },
                onCancel = { requestPicker(null) },
            )
        )
    }

    // null = section list; B on a section page goes back to the list first.
    var activeTab by remember(game.id) { mutableStateOf<EditTab?>(null) }
    var lastTab by remember(game.id) { mutableStateOf<EditTab?>(null) }
    val keyboardCtl = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(enabled = !pickerShowing()) {
        if (activeTab != null) {
            keyboardCtl?.hide(); focusMgr.clearFocus(force = true)
            lastTab = activeTab; activeTab = null
        } else requestCancel()
    }

    val heroPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            heroOverride = uri.toString()
        }
    }
    val boxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            boxOverride = uri.toString()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(neon.surface)) {
        // Sticky action bar at the top so save is always visible.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1014))
                .padding(
                    horizontal = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 10.dp else 24.dp,
                    vertical = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 8.dp else 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Edit game — ${game.name}",
                color = neon.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            ActionChip(label = "SAVE", accent = true, onClick = { onSave(buildRow()) })
            Spacer(Modifier.width(8.dp))
            ActionChip(label = "RESET", accent = false, onClick = onReset)
            Spacer(Modifier.width(8.dp))
            ActionChip(label = "CANCEL", accent = false, onClick = { requestCancel() })
        }

        val tab = activeTab
        if (tab == null) {
            GameEditIndex(
                focusTab = lastTab,
                summary = { t ->
                    when (t) {
                        EditTab.General -> displayName.ifBlank { game.name }
                        EditTab.Launch -> "Player, launch options and the exact command"
                        EditTab.Art -> listOf(
                            "hero " + if (heroOverride.isNotBlank()) "custom" else "auto",
                            "box " + if (boxOverride.isNotBlank()) "custom" else "auto",
                        ).joinToString(" · ")
                    }
                },
                onOpen = { activeTab = it },
            )
        } else Column(modifier = Modifier.fillMaxSize()) {
            com.maiconerick.xgamelauncher.ui.components.PageHeader(icon = tab.icon, title = tab.label)
            val enter = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(tab) {
                kotlinx.coroutines.delay(50)
                runCatching { enter.requestFocus() }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(enter)
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 10.dp else 32.dp,
                        vertical = if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.compact) 10.dp else 20.dp,
                    ),
            ) {
                when (tab) {
                    EditTab.General -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FieldLabel("DISPLAY NAME")
                        GhostBufferTextField(value = displayName, onValueChange = { displayName = it })

                        // Screen choice only exists on dual-screen devices (Thor).
                        if (com.maiconerick.xgamelauncher.ui.LocalDeviceProfile.current.multiDisplay) {
                        FieldLabel("SCREEN")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipOption(label = "Default", selected = screenSel == null,
                                onClick = { screenSel = null })
                            ChipOption(label = "Top", selected = screenSel == "top",
                                onClick = { screenSel = "top" })
                            ChipOption(label = "Bottom", selected = screenSel == "bottom",
                                onClick = { screenSel = "bottom" })
                            ChipOption(label = "Ask", selected = screenSel == "ask",
                                onClick = { screenSel = "ask" })
                        }
                        }
                    }

                    EditTab.Launch -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FieldLabel("LAUNCH WITH")
                        if (androidComponent != null) {
                            Text(
                                text = "Android app — fires the app's launcher activity directly.",
                                color = neon.inkDim,
                                fontSize = 12.sp,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ChipOption(label = "Default", selected = intentSel == null,
                                    onClick = { intentSel = null })
                                ChipOption(label = "Ask each time", selected = intentSel == ASK_INTENT_ID,
                                    onClick = { intentSel = ASK_INTENT_ID })
                                platform.intents.forEach { it ->
                                    ChipOption(label = it.name, selected = intentSel == it.id,
                                        onClick = { intentSel = it.id })
                                }
                                val foreignIntents = allPlatforms
                                    .filter { it.id != platform.id }
                                    .flatMap { p -> p.intents.map { p to it } }
                                if (foreignIntents.isNotEmpty()) {
                                    ChipOption(label = "Other…", selected = false, onClick = {
                                        requestPicker(
                                            PickerRequest(
                                                title = "Pick a player from another platform",
                                                options = foreignIntents.map { (p, i) ->
                                                    PickerOption(id = i.id, label = i.name, subtitle = p.name)
                                                },
                                                onPick = { id -> intentSel = id; requestPicker(null) },
                                                onCancel = { requestPicker(null) },
                                            )
                                        )
                                    })
                                }
                            }
                        }

                        LaunchReadout(
                            platform = platform,
                            allPlatforms = allPlatforms,
                            game = game,
                            selectedIntentId = intentSel,
                            androidComponent = androidComponent,
                        )
                    }

                    EditTab.Art -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FieldLabel("ART — leave blank to use the auto-fetched art")

                        FieldLabel("BACKGROUND (HERO)")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                PlainInput(
                                    value = heroOverride,
                                    onChange = { heroOverride = it },
                                    placeholder = "content://… · https://… · /storage/…",
                                )
                            }
                            ActionChip(label = "SEARCH", accent = true,
                                onClick = { artSearchTarget = ArtTarget.HERO })
                            ActionChip(label = "PICK FILE", accent = false,
                                onClick = { heroPicker.launch(arrayOf("image/*")) })
                            if (heroOverride.isNotBlank()) {
                                ActionChip(label = "CLEAR", accent = false, onClick = { heroOverride = "" })
                            }
                        }

                        FieldLabel("BOX ART")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                PlainInput(
                                    value = boxOverride,
                                    onChange = { boxOverride = it },
                                    placeholder = "content://… · https://… · /storage/…",
                                )
                            }
                            ActionChip(label = "SEARCH", accent = true,
                                onClick = { artSearchTarget = ArtTarget.BOX })
                            ActionChip(label = "PICK FILE", accent = false,
                                onClick = { boxPicker.launch(arrayOf("image/*")) })
                            if (boxOverride.isNotBlank()) {
                                ActionChip(label = "CLEAR", accent = false, onClick = { boxOverride = "" })
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    artSearchTarget?.let { target ->
        ArtSearchDialog(
            initialQuery = displayName.trim().ifBlank { game.name },
            target = target,
            onApply = { result: ArtSearchResult ->
                result.heroUrl?.let { heroOverride = it }
                result.boxUrl?.let { boxOverride = it }
                // The dialog asks the user whether to rename the game to the query they
                // typed; if they confirmed, `renameTo` carries the new display name.
                result.renameTo?.takeIf { it.isNotBlank() }?.let { displayName = it }
                artSearchTarget = null
            },
            onCancel = { artSearchTarget = null },
        )
    }
}

@Composable
private fun LaunchReadout(
    platform: Platform,
    allPlatforms: List<Platform>,
    game: GameEntry,
    selectedIntentId: String?,
    androidComponent: String?,
) {
    val neon = LocalNeon.current
    // Resolve the actual intent — search this platform first, then fall back to every
    // other platform so a "foreign" intent id (from the Other… picker) still works.
    val resolvedIntent = when {
        selectedIntentId == null || selectedIntentId == ASK_INTENT_ID -> null
        else -> platform.intents.firstOrNull { it.id == selectedIntentId }
            ?: allPlatforms.flatMap { it.intents }.firstOrNull { it.id == selectedIntentId }
    }
    // If there's no explicit default and multiple intents exist, MainActivity's launch
    // flow would prompt at launch — but the readout is more useful if it previews the
    // first available player rather than reading "None available".
    val defaultIntent = platform.intents.firstOrNull { it.id == platform.defaultIntentId }
        ?: platform.intents.firstOrNull()
    val defaultIsImplicit = resolvedIntent == null &&
        platform.defaultIntentId == null &&
        platform.intents.size > 1

    val (playerName, cmd) = when {
        androidComponent != null ->
            "Android app" to "am start -n $androidComponent"
        selectedIntentId == ASK_INTENT_ID ->
            "Ask each time" to "Prompt at launch — you'll pick a player then."
        else -> {
            val intent = resolvedIntent ?: defaultIntent
            val baseName = intent?.name ?: "None available"
            val name = if (defaultIsImplicit) "$baseName · will prompt at launch" else baseName
            val cmd = buildAmCommand(LocalContext.current, intent, platform, game)
            name to cmd
        }
    }
    // Where in the platform tree did the resolved intent come from? Helps the user tell
    // whether they picked a foreign one via Other…
    val originPlatform = resolvedIntent?.let { r ->
        allPlatforms.firstOrNull { p -> p.intents.any { it.id == r.id } }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1014))
            .border(1.dp, neon.accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "PLAYER",
            color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            text = playerName,
            color = neon.accent, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
        )
        if (originPlatform != null && originPlatform.id != platform.id) {
            Text(
                text = "from ${originPlatform.name}".uppercase(),
                color = Color(0xFFFFB74D),
                fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "am COMMAND",
            color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            text = cmd,
            color = neon.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

/** The exact command the built-in launcher fires for this game. */
private fun buildAmCommand(
    ctx: android.content.Context,
    intent: com.maiconerick.xgamelauncher.data.LaunchIntent?,
    platform: Platform,
    game: GameEntry,
): String {
    if (intent == null) return "No player set up — add one in the platform's Players tab."
    val vars = com.maiconerick.xgamelauncher.launch.LaunchVars.forGame(ctx, platform, game)
    return com.maiconerick.xgamelauncher.launch.AmArgs.toShell(intent, vars)
}

@Composable
private fun FieldLabel(text: String) {
    val neon = LocalNeon.current
    Text(text, color = neon.inkMuted, fontSize = 10.sp,
        letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PlainInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0xFF12191E) else Color(0xFF0B1014))
            .border(1.dp, if (focused) neon.accent else neon.stroke, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused },
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = neon.inkMuted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        com.maiconerick.xgamelauncher.ui.components.GamepadTextField(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = neon.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            singleLine = true,
            cursorBrush = SolidColor(neon.accent),
        )
    }
}

@Composable
private fun ChipOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected -> neon.accent.copy(alpha = 0.20f)
        focused -> Color(0x33FFFFFF)
        else -> Color(0x22FFFFFF)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = when {
                    selected -> neon.accent
                    focused -> neon.accent.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(3.dp),
            )
            .focusRing(focused, RoundedCornerShape(3.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) neon.accent else neon.ink,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionChip(label: String, accent: Boolean, onClick: () -> Unit) {
    val neon = LocalNeon.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        accent && focused -> neon.accent.copy(alpha = 0.36f)
        accent -> neon.accent.copy(alpha = 0.20f)
        focused -> Color(0x44FFFFFF)
        else -> Color(0x22FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = when {
                    accent -> neon.accent
                    focused -> neon.accent.copy(alpha = 0.5f)
                    else -> Color(0x33FFFFFF)
                },
                shape = RoundedCornerShape(4.dp),
            )
            .focusRing(focused, RoundedCornerShape(4.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onGamepadActivate(onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (accent) neon.accent else neon.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

// ---- SECTIONS (Daijishō-style list → page) ----

private enum class EditTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    General("General", androidx.compose.material.icons.Icons.Filled.Edit),
    Launch("Launch", androidx.compose.material.icons.Icons.Filled.PlayCircle),
    Art("Art", androidx.compose.material.icons.Icons.Filled.Image),
}

@Composable
private fun GameEditIndex(
    focusTab: EditTab?,
    summary: (EditTab) -> String,
    onOpen: (EditTab) -> Unit,
) {
    val requesters = remember { EditTab.values().associateWith { androidx.compose.ui.focus.FocusRequester() } }
    LaunchedEffect(focusTab) {
        kotlinx.coroutines.delay(50)
        runCatching { requesters.getValue(focusTab ?: EditTab.General).requestFocus() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditTab.values().forEach { t ->
            com.maiconerick.xgamelauncher.ui.components.ListRow(
                icon = t.icon, title = t.label, subtitle = summary(t),
                focusRequester = requesters.getValue(t),
                onClick = { onOpen(t) },
            )
        }
    }
}
