package com.maiconerick.xgamelauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xgamelauncher.daijisho.DaijishoPlayerDb
import com.maiconerick.xgamelauncher.data.IntentExtra
import com.maiconerick.xgamelauncher.data.LaunchIntent
import com.maiconerick.xgamelauncher.data.Platform
import com.maiconerick.xgamelauncher.launch.AmArgs
import com.maiconerick.xgamelauncher.launch.AppIntrospect
import com.maiconerick.xgamelauncher.launch.LaunchVars
import com.maiconerick.xgamelauncher.ui.LocalDeviceProfile
import com.maiconerick.xgamelauncher.ui.LocalPicker
import com.maiconerick.xgamelauncher.ui.PickerOption
import com.maiconerick.xgamelauncher.ui.PickerRequest
import com.maiconerick.xgamelauncher.ui.theme.LocalNeon
import kotlinx.coroutines.launch

/**
 * Platform editor → Players tab. A player is Daijishō's "am start arguments" in structured
 * form: pick the app (→ package), pick or type the activity class, then action / data /
 * extras / flags. The raw am text is always shown and can be edited or pasted directly,
 * and whole players can be imported from Daijishō's database.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayersTab(
    intents: List<LaunchIntent>,
    defaultIntentId: String?,
    onIntentsChange: (List<LaunchIntent>) -> Unit,
    onDefaultChange: (String?) -> Unit,
    platformName: String,
    romsPath: String,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val picker = LocalPicker.current
    val scope = rememberCoroutineScope()
    val curIntents by rememberUpdatedState(intents)
    val setIntents by rememberUpdatedState(onIntentsChange)
    var expandedId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val sample = remember(platformName, romsPath) { Platform(id = "", name = platformName, romsPath = romsPath) }

    fun addImported(list: List<Pair<LaunchIntent, List<String>>>) {
        if (list.isEmpty()) return
        setIntents(curIntents + list.map { it.first })
        if (curIntents.isEmpty() && defaultIntentId == null) onDefaultChange(list.first().first.id)
        expandedId = list.singleOrNull()?.first?.id
        val warns = list.flatMap { it.second }.distinct()
        status = "Imported ${list.size} player${if (list.size > 1) "s" else ""}" +
            (if (warns.isNotEmpty()) " · ${warns.joinToString("; ")}" else "") +
            " — remember to SAVE."
    }

    fun showPlayers(def: DaijishoPlayerDb.PlatformDef) {
        val entries = def.players
            .map { it to AppIntrospect.isInstalled(ctx, it.pkg) }
            .sortedByDescending { it.second }
        val installed = entries.filter { it.second }
        val opts = buildList {
            if (installed.size > 1) add(PickerOption("__all", "Import all installed (${installed.size})"))
            entries.forEachIndexed { i, (p, ok) ->
                add(
                    PickerOption(
                        id = i.toString(), label = p.name,
                        subtitle = (if (ok) "✓ installed · " else "not installed · ") + (p.pkg ?: "?"),
                    )
                )
            }
        }
        picker(
            PickerRequest(
                title = def.displayName,
                message = "${def.players.size} players · installed apps first",
                options = opts,
                filterable = true,
                onPick = { id ->
                    picker(null)
                    val chosen = if (id == "__all") installed.map { it.first }
                                 else listOf(entries[id.toInt()].first)
                    addImported(chosen.map { DaijishoPlayerDb.toLaunchIntent(it, def.shortname) })
                },
                onCancel = { picker(null) },
            )
        )
    }

    fun showPlatforms(snap: DaijishoPlayerDb.Snapshot) {
        val ranked = DaijishoPlayerDb.rank(snap.platforms, platformName, romsPath)
        picker(
            PickerRequest(
                title = "Import from Daijishō",
                message = "Player database snapshot ${snap.snapshot} · best matches for \"$platformName\" first",
                filterable = true,
                options = listOf(
                    PickerOption("__refresh", "↻ Refresh from GitHub", "Download the latest Daijishō player database"),
                ) + ranked.mapIndexed { i, d ->
                    PickerOption("p$i", d.displayName, "${d.players.size} players · ${d.shortname ?: d.file}")
                },
                onPick = { id ->
                    if (id == "__refresh") {
                        picker(null)
                        status = "Downloading Daijishō database…"
                        scope.launch {
                            runCatching { DaijishoPlayerDb.refreshFromGitHub(ctx) }
                                .onSuccess { status = "Database updated (${it.platforms.size} platforms)."; showPlatforms(it) }
                                .onFailure { status = "Download failed: ${it.message}" }
                        }
                    } else {
                        showPlayers(ranked[id.removePrefix("p").toInt()])
                    }
                },
                onCancel = { picker(null) },
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("PLAYERS")
        Text(
            "How games start — Daijishō-compatible am start arguments. Pick an app, then its " +
                "activity, then add extras. Tokens: " + LaunchVars.TOKENS_HELP.joinToString(" ") { it.first },
            color = neon.inkMuted, fontSize = 11.sp, lineHeight = 15.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("+ NEW PLAYER", accent = true, onClick = {
                val id = "int_" + System.currentTimeMillis() + "_" + (0..9999).random()
                setIntents(curIntents + LaunchIntent(id = id, name = "New player", killBefore = false))
                expandedId = id
            })
            SmallBtn("IMPORT FROM DAIJISHŌ", accent = true, onClick = {
                scope.launch {
                    runCatching { DaijishoPlayerDb.load(ctx) }
                        .onSuccess { showPlatforms(it) }
                        .onFailure { status = "Couldn't read the player database: ${it.message}" }
                }
            })
        }
        status?.let { Text(it, color = neon.accent, fontSize = 11.sp, lineHeight = 15.sp) }
        intents.forEachIndexed { idx, intent ->
            PlayerCard(
                intent = intent,
                isDefault = intent.id == defaultIntentId || (defaultIntentId == null && idx == 0),
                expanded = expandedId == intent.id,
                sample = sample,
                onToggle = { expandedId = if (expandedId == intent.id) null else intent.id },
                onChange = { u -> setIntents(curIntents.map { if (it.id == u.id) u else it }) },
                onDelete = {
                    val next = curIntents.filterNot { it.id == intent.id }
                    setIntents(next)
                    if (defaultIntentId == intent.id) onDefaultChange(next.firstOrNull()?.id)
                },
                onDuplicate = {
                    val copy = intent.copy(
                        id = "int_" + System.currentTimeMillis() + "_" + (0..9999).random(),
                        name = intent.name + " (copy)",
                    )
                    setIntents(curIntents.toMutableList().also { it.add(idx + 1, copy) })
                    expandedId = copy.id
                },
                onMakeDefault = { onDefaultChange(intent.id) },
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerCard(
    intent: LaunchIntent,
    isDefault: Boolean,
    expanded: Boolean,
    sample: Platform,
    onToggle: () -> Unit,
    onChange: (LaunchIntent) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val pkg = intent.targetPackage()
    val installed = remember(pkg) { AppIntrospect.isInstalled(ctx, pkg) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22000000))
            .border(1.dp, if (expanded) neon.accent.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(if (LocalDeviceProfile.current.compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(intent.name, color = neon.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    text = (pkg ?: "no app picked") + when {
                        pkg == null -> ""
                        installed -> " · ✓ installed"
                        else -> " · not installed"
                    },
                    color = if (pkg != null && !installed) Color(0xFFFFB74D) else neon.inkMuted,
                    fontSize = 10.sp, maxLines = 1,
                )
            }
            if (isDefault) Text("DEFAULT", color = neon.accent, fontSize = 9.sp,
                letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn(if (expanded) "CLOSE" else "EDIT", accent = true, onClick = onToggle)
            if (!isDefault) SmallBtn("SET DEFAULT", onClick = onMakeDefault)
            SmallBtn("DUPLICATE", onClick = onDuplicate)
            SmallBtn("DELETE", onClick = onDelete)
        }
        if (expanded) PlayerFields(intent, sample, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerFields(intent: LaunchIntent, sample: Platform, onChange: (LaunchIntent) -> Unit) {
    val neon = LocalNeon.current
    val ctx = LocalContext.current
    val picker = LocalPicker.current
    val scope = rememberCoroutineScope()
    val compact = LocalDeviceProfile.current.compact
    val cur by rememberUpdatedState(intent)
    val emit by rememberUpdatedState(onChange)

    val pkg = intent.targetPackage().orEmpty()
    val cls = intent.component?.let { c -> if ('/' in c) c.substringAfter('/') else c }.orEmpty()
    fun compose(p: String, c: String): String? = when {
        c.isBlank() -> null
        p.isBlank() -> c
        else -> "$p/$c"
    }

    fun pickActivity(p: String) {
        scope.launch {
            val acts = AppIntrospect.activities(ctx, p)
            picker(
                PickerRequest(
                    title = "Activity · ${AppIntrospect.label(ctx, p) ?: p}",
                    message = if (acts.isEmpty()) "Couldn't list activities — type the class yourself."
                              else "Not-exported activities launch through root am start.",
                    filterable = acts.size > 8,
                    options = listOf(PickerOption("__manual", "Type it myself")) + acts.mapIndexed { i, a ->
                        PickerOption(
                            id = i.toString(),
                            label = a.shortName(p),
                            subtitle = listOfNotNull(
                                "launcher".takeIf { a.isLauncher },
                                if (a.exported) "exported" else "not exported · root",
                                a.label?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                        )
                    },
                    onPick = { id ->
                        picker(null)
                        if (id != "__manual") {
                            val a = acts[id.toInt()]
                            emit(cur.copy(pkg = p, component = "$p/${a.shortName(p)}"))
                        }
                    },
                    onCancel = { picker(null) },
                )
            )
        }
    }

    fun pickApp() {
        scope.launch {
            val apps = AppIntrospect.installedApps(ctx)
            picker(
                PickerRequest(
                    title = "Pick the emulator app",
                    filterable = true,
                    options = apps.map { PickerOption(it.pkg, it.label, it.pkg) },
                    onPick = { p ->
                        picker(null)
                        val label = apps.firstOrNull { it.pkg == p }?.label ?: p
                        val rename = cur.name.isBlank() || cur.name == "New player" || cur.name == "New launcher"
                        emit(cur.copy(pkg = p, component = null, name = if (rename) label else cur.name))
                        pickActivity(p)
                    },
                    onCancel = { picker(null) },
                )
            )
        }
    }

    fun pickRetroArchCore(p: String) {
        scope.launch {
            val cores = AppIntrospect.retroArchCores(p)
            val presetKeys = setOf("ROM", "LIBRETRO", "CONFIGFILE", "DATADIR", "SDCARD", "EXTERNAL")
            fun apply(core: String) {
                val keep = cur.extras.filter { it.key !in presetKeys }
                val rename = cur.name.isBlank() || cur.name == "New player" || cur.name.startsWith("RetroArch")
                emit(
                    cur.copy(
                        pkg = p,
                        component = "$p/${AppIntrospect.RETROARCH_ACTIVITY}",
                        extras = AppIntrospect.retroArchExtras(p, core) + keep,
                        killBefore = true,
                        name = if (rename && core.isNotBlank()) "RetroArch · $core" else cur.name,
                    )
                )
            }
            picker(
                PickerRequest(
                    title = "RetroArch core",
                    message = if (cores.isEmpty())
                        "No cores found in /data/data/$p/cores (needs root and cores downloaded in RetroArch). " +
                            "You can still add the extras and type LIBRETRO yourself."
                    else "Sets -n RetroActivityFuture plus ROM / LIBRETRO / CONFIGFILE / DATADIR / SDCARD / EXTERNAL.",
                    filterable = cores.size > 8,
                    options = if (cores.isEmpty()) listOf(PickerOption("", "Add extras with an empty core"))
                              else cores.map { PickerOption(it, it) },
                    onPick = { core -> picker(null); apply(core) },
                    onCancel = { picker(null) },
                )
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        EditField(label = "NAME", value = intent.name, onChange = { onChange(intent.copy(name = it)) })

        // ---- app + activity (-n)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EditField(
                modifier = Modifier.weight(1f), label = "APP PACKAGE", value = pkg,
                placeholder = "com.github.stenzek.duckstation",
                onChange = { p -> onChange(intent.copy(pkg = p.trim().ifBlank { null }, component = compose(p.trim(), cls))) },
            )
            SmallBtn("PICK APP", accent = true, onClick = { pickApp() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EditField(
                modifier = Modifier.weight(1f), label = "ACTIVITY CLASS", value = cls,
                placeholder = ".EmulationActivity",
                onChange = { c -> onChange(intent.copy(component = compose(pkg, c.trim()))) },
            )
            SmallBtn("PICK", accent = pkg.isNotBlank(), onClick = { if (pkg.isNotBlank()) pickActivity(pkg) else pickApp() })
        }
        if (AppIntrospect.isRetroArch(pkg)) {
            SmallBtn("RETROARCH CORE PRESET…", accent = true, onClick = { pickRetroArchCore(pkg) })
        }

        // ---- action / data / type / categories
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EditField(
                modifier = Modifier.weight(1f), label = "ACTION (-a)", value = intent.action.orEmpty(),
                placeholder = "android.intent.action.VIEW",
                onChange = { onChange(intent.copy(action = it.ifBlank { null })) },
            )
            if (!compact) {
                SmallBtn("MAIN", onClick = { onChange(intent.copy(action = "android.intent.action.MAIN")) })
                SmallBtn("VIEW", onClick = { onChange(intent.copy(action = "android.intent.action.VIEW")) })
            }
        }
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("MAIN", onClick = { onChange(intent.copy(action = "android.intent.action.MAIN")) })
            SmallBtn("VIEW", onClick = { onChange(intent.copy(action = "android.intent.action.VIEW")) })
        }
        EditField(
            label = "DATA (-d)", value = intent.dataTemplate.orEmpty(), placeholder = "{file.uri}",
            onChange = { onChange(intent.copy(dataTemplate = it.ifBlank { null })) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("{file.uri}", onClick = { onChange(intent.copy(dataTemplate = "{file.uri}")) })
            SmallBtn("{file.path}", onClick = { onChange(intent.copy(dataTemplate = "{file.path}")) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditField(
                modifier = Modifier.weight(1f), label = "MIME TYPE (-t)", value = intent.mimeType.orEmpty(),
                onChange = { onChange(intent.copy(mimeType = it.ifBlank { null })) },
            )
            EditField(
                modifier = Modifier.weight(1f), label = "CATEGORIES (-c)", value = intent.categories.joinToString(","),
                placeholder = "comma separated",
                onChange = { v -> onChange(intent.copy(categories = v.split(',').map { it.trim() }.filter { it.isNotEmpty() })) },
            )
        }

        // ---- extras
        Text("EXTRAS (-e / --ez / --ei …)", color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        intent.extras.forEachIndexed { i, e ->
            ExtraRow(
                extra = e, compact = compact,
                onChange = { u -> onChange(intent.copy(extras = intent.extras.toMutableList().also { it[i] = u })) },
                onDelete = { onChange(intent.copy(extras = intent.extras.toMutableList().also { it.removeAt(i) })) },
                onPickType = {
                    picker(
                        PickerRequest(
                            title = "Extra type",
                            options = AmArgs.EXTRA_TYPES.map { (t, label) ->
                                PickerOption(t, label, if (t == "es") "-e / --es" else "--$t")
                            },
                            onPick = { t ->
                                picker(null)
                                emit(cur.copy(extras = cur.extras.toMutableList().also { it[i] = it[i].copy(type = t) }))
                            },
                            onCancel = { picker(null) },
                        )
                    )
                },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("+ EXTRA", accent = true, onClick = { onChange(intent.copy(extras = intent.extras + IntentExtra())) })
            SmallBtn("+ PATH EXTRA", onClick = {
                onChange(intent.copy(extras = intent.extras + IntentExtra("es", "", "{file.path}")))
            })
            SmallBtn("+ URI EXTRA", onClick = {
                onChange(intent.copy(extras = intent.extras + IntentExtra("es", "", "{file.uri}")))
            })
        }

        // ---- flags + options
        Text("FLAGS", color = neon.inkMuted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (AmArgs.COMMON_FLAGS + intent.flags.filterNot { it in AmArgs.COMMON_FLAGS }).forEach { f ->
                val on = f in intent.flags
                SmallBtn((if (on) "✓ " else "") + f.removePrefix("--activity-").removePrefix("--"), accent = on, onClick = {
                    onChange(intent.copy(flags = if (on) intent.flags - f else intent.flags + f))
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            EditField(
                modifier = Modifier.weight(1f), label = "RAW FLAGS (-f)", value = intent.flagsHex.orEmpty(),
                placeholder = "0x10008000",
                onChange = { onChange(intent.copy(flagsHex = it.ifBlank { null })) },
            )
            SmallBtn(
                (if (intent.killBefore) "✓ " else "") + "KILL APP FIRST",
                accent = intent.killBefore,
                onClick = { onChange(intent.copy(killBefore = !intent.killBefore)) },
            )
        }
        // ---- raw am text + preview
        RawArgsBlock(intent = intent, sample = sample, onChange = onChange)
    }
}

@Composable
private fun ExtraRow(
    extra: IntentExtra,
    compact: Boolean,
    onChange: (IntentExtra) -> Unit,
    onDelete: () -> Unit,
    onPickType: () -> Unit,
) {
    val typeLabel = if (extra.type == "es") "-e" else "--${extra.type}"
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallBtn(typeLabel, accent = true, onClick = onPickType)
                EditField(modifier = Modifier.weight(1f), label = "", value = extra.key, placeholder = "KEY",
                    onChange = { onChange(extra.copy(key = it)) })
                SmallBtn("✕", onClick = onDelete)
            }
            if (extra.type != "esn") EditField(label = "", value = extra.value, placeholder = "value",
                onChange = { onChange(extra.copy(value = it)) })
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallBtn(typeLabel, accent = true, onClick = onPickType)
            EditField(modifier = Modifier.weight(0.8f), label = "", value = extra.key, placeholder = "KEY",
                onChange = { onChange(extra.copy(key = it)) })
            if (extra.type != "esn") EditField(modifier = Modifier.weight(1.4f), label = "", value = extra.value,
                placeholder = "value", onChange = { onChange(extra.copy(value = it)) })
            SmallBtn("✕", onClick = onDelete)
        }
    }
}

@Composable
private fun RawArgsBlock(intent: LaunchIntent, sample: Platform, onChange: (LaunchIntent) -> Unit) {
    val neon = LocalNeon.current
    var editing by remember(intent.id) { mutableStateOf(false) }
    var draft by remember(intent.id) { mutableStateOf("") }
    var warnings by remember(intent.id) { mutableStateOf<List<String>>(emptyList()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B1014))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AM START ARGUMENTS", color = neon.inkMuted, fontSize = 10.sp,
                letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (!editing) SmallBtn("EDIT / PASTE", onClick = { draft = AmArgs.format(intent); warnings = emptyList(); editing = true })
        }
        if (editing) {
            EditField(label = "", value = draft, onChange = { draft = it }, singleLine = false, mono = true,
                placeholder = "-n com.pkg/.Activity\n-e ROM {file.path}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallBtn("APPLY", accent = true, onClick = {
                    val r = AmArgs.parse(draft, intent)
                    warnings = r.warnings
                    onChange(r.intent)
                    if (r.warnings.isEmpty()) editing = false
                })
                SmallBtn("CANCEL", onClick = { editing = false; warnings = emptyList() })
            }
        } else {
            Text(
                text = AmArgs.format(intent).ifBlank { "(empty)" },
                color = neon.ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            )
        }
        warnings.forEach { Text("⚠ $it", color = Color(0xFFFFB74D), fontSize = 11.sp) }
        Spacer(Modifier.width(1.dp))
        Text("EXAMPLE COMMAND", color = neon.inkMuted, fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium)
        Text(
            text = AmArgs.toShell(intent, LaunchVars.sample(sample)),
            color = neon.inkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
        )
    }
}
