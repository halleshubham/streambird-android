package app.streambird.android.ui.screens

import androidx.compose.foundation.clickable
import app.streambird.android.ads.adSwitchedOffText
import app.streambird.android.ads.AD_OFF_CHIP
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.streambird.android.ads.AdEditSlot
import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdOverride
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AD_CLOCK_NOTE
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSlot
import app.streambird.android.ads.AdSource
import app.streambird.android.ads.KitAdsNotice
import app.streambird.android.ads.adElapsedSec
import app.streambird.android.ads.adStatusText
import app.streambird.android.ads.formatCountdown
import app.streambird.android.ads.AdsAccess
import app.streambird.android.ads.MAX_ADS_PER_PLACEMENT
import app.streambird.android.ads.SeqAd
import app.streambird.android.ads.SeqState
import app.streambird.android.ads.adSecondsError
import app.streambird.android.ads.adSequenceState
import app.streambird.android.ads.adTalkBackLabel
import app.streambird.android.ads.overrideAddPhone
import app.streambird.android.ads.currentAds
import app.streambird.android.ads.formatDuration
import app.streambird.android.ads.overrideMoveAd
import app.streambird.android.ads.placementEdited
import app.streambird.android.ads.overrideRemoveAd
import app.streambird.android.ads.overrideReplaceWithPhone
import app.streambird.android.ads.resetPlacement
import app.streambird.android.ads.seqScheduleOf
import app.streambird.android.ads.sequenceSetupText
import app.streambird.android.ads.sequenceStatusText
import app.streambird.android.ads.overrideSetOn
import app.streambird.android.ads.overrideSetSeconds
import app.streambird.android.ads.overrideSetSchedule
import app.streambird.android.ads.PhoneAd
import app.streambird.android.ads.SecUnit
import app.streambird.android.ads.describeSchedule
import app.streambird.android.ads.phoneAdError
import app.streambird.android.ads.scheduleError
import app.streambird.android.ads.splitSeconds
import app.streambird.android.ads.toSeconds
import app.streambird.android.brand.ImageFormat
import app.streambird.android.brand.sniffImage
import app.streambird.android.data.AssetItem
import app.streambird.android.data.KitAd
import app.streambird.android.stream.LiveAd
import app.streambird.android.ui.theme.Amber
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Continuous | Show for [n][sec|min] then hide for [n][sec|min]". Emits a schedule on every edit; invalid numbers become 0 so [scheduleError] names them. */
@Composable
fun ScheduleEditor(schedule: AdSchedule, onChange: (AdSchedule) -> Unit, modifier: Modifier = Modifier) {
    val interval = schedule as? AdSchedule.Interval
    var showText by remember { mutableStateOf(interval?.let { splitSeconds(it.showSec).value.toString() } ?: "15") }
    var showUnit by remember { mutableStateOf(interval?.let { splitSeconds(it.showSec).unit } ?: SecUnit.SEC) }
    var everyText by remember { mutableStateOf(interval?.let { splitSeconds(it.everySec).value.toString() } ?: "3") }
    var everyUnit by remember { mutableStateOf(interval?.let { splitSeconds(it.everySec).unit } ?: SecUnit.MIN) }
    fun emit() = onChange(AdSchedule.Interval(toSeconds(showText.toDoubleOrNull() ?: 0.0, showUnit), toSeconds(everyText.toDoubleOrNull() ?: 0.0, everyUnit)))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segmented(listOf("continuous", "interval"), if (interval == null) "continuous" else "interval", { if (it == "continuous") "Continuous" else "Show, then hide" }, { m ->
            if (m == "continuous") onChange(AdSchedule.Continuous) else emit()
        })
        if (interval != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show for", Modifier.weight(1f))
                OutlinedTextField(showText, { showText = it.filter { c -> c.isDigit() }.take(5); emit() }, Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Segmented(SecUnit.entries, showUnit, { it.label }, { showUnit = it; emit() }, Modifier.weight(1.4f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("then hide for", Modifier.weight(1f))
                OutlinedTextField(everyText, { everyText = it.filter { c -> c.isDigit() }.take(6); emit() }, Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Segmented(SecUnit.entries, everyUnit, { it.label }, { everyUnit = it; emit() }, Modifier.weight(1.4f))
            }
            scheduleError(interval)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun describeScheduleOf(a: KitAd) = describeSchedule(app.streambird.android.ads.scheduleOf(a))

/**
 * Setup screen: kit ads apply automatically with the kit; per placement the user sees the ordered LIST of its ads (1.0.18: up to 5 played in turn) and can switch
 * each one off, give it its own seconds, reorder, remove, add another picture from the phone, change the placement's schedule, or use a picture from the phone
 * for THIS stream only (kept in memory, not uploaded). [kitAds] = every ad of the kit's set (flat, placement by placement). Free plan: only an explanation.
 */
@Composable
fun AdsSetupSection(
    freePlan: Boolean,
    kitAds: List<KitAd>,
    overrides: Map<AdPlacement, AdOverride>,
    onChange: (Map<AdPlacement, AdOverride>) -> Unit,
    shrink: Boolean? = null,
    kitShrink: Boolean = true,
    onShrink: (Boolean?) -> Unit = {},
    /** Why the rows look as they do (kit loading / not loaded / no ads for this shape / kit ads switched off by the logo choice). */
    notice: KitAdsNotice? = null,
    /** The kit's L-shaped graphic: true = a clear window was found, false = none, null = not known (yet). */
    frameHasWindow: Boolean? = null,
    /** Names and pictures of the kit's ad files (the brand library), to show a name / thumbnail per ad. */
    library: List<AssetItem> = emptyList(),
    loader: ImageLoader? = null,
    assetUrl: (String) -> String = { it },
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ads", style = MaterialTheme.typography.titleSmall)
        if (freePlan) {
            Text("On this account's plan a StreamBird banner shows now and then and a small \"Powered by StreamBird\" mark stays on the picture. Your own ads are not included in this account's plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        /** What the picker is for: (placement, index to replace or null = add another). */
        var pickFor by remember { mutableStateOf<Pair<AdPlacement, Int?>?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var scheduleFor by remember { mutableStateOf<AdPlacement?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val target = pickFor; pickFor = null
            if (uri == null || target == null) return@rememberLauncherForActivityResult
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readNBytesLimited(app.streambird.android.ads.MAX_PHONE_AD_BYTES + 1) }
                        val info = sniffImage(bytes)
                        when {
                            info == null -> "The file must be a PNG, JPG, WebP or GIF picture." to null
                            else -> phoneAdError(bytes.size, info.width, info.height) to PhoneAd(bytes, uri.lastPathSegment ?: "picture", info.format == ImageFormat.GIF)
                        }
                    }.getOrElse { "Could not read that picture." to null }
                }
                message = res.first
                val (p, index) = target
                if (res.first == null) res.second?.let { ph ->
                    val cur = currentAds(kitAds, overrides, p)
                    when {
                        overrides[p]?.sequence == null && cur.size <= 1 && (index != null || cur.isEmpty()) -> onChange(overrides + (p to (overrides[p] ?: AdOverride()).copy(phone = ph, enabled = true)))   // one ad or none: the single-ad form, as before
                        index != null -> onChange(overrideReplaceWithPhone(kitAds, overrides, p, index, ph))
                        else -> onChange(overrideAddPhone(kitAds, overrides, p, ph))
                    }
                }
            }
        }
        fun pick(p: AdPlacement, index: Int?) { pickFor = p to index; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.warn) Amber else MaterialTheme.colorScheme.onSurfaceVariant) }
        ShrinkSwitch(shrink ?: kitShrink, { v -> onShrink(if (v == kitShrink) null else v) })
        var anyInterval = false
        AdPlacement.entries.forEach { p ->
            val ads = currentAds(kitAds, overrides, p)
            val hasAd = ads.isNotEmpty()
            val first = ads.firstOrNull()
            if (first?.schedule is AdSchedule.Interval) anyInterval = true
            val edited = placementEdited(overrides, p)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ads.size <= 1) {
                        // One ad (or none): exactly the card of 1.0.17.
                        val o = overrides[p]
                        val enabled = first?.enabled ?: true
                        val sched = first?.schedule ?: AdSchedule.Continuous
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.label, style = MaterialTheme.typography.titleSmall)
                                    if (hasAd && !enabled) AdOffChip()
                                }
                                Text(
                                    if (!hasAd) "No ad"
                                    else adStatusText(
                                        null, AdSlot(p, enabled, sched), first!!.source,
                                        hasWindow = if (p == AdPlacement.L_SHAPE && first.source == AdSource.KIT) frameHasWindow else null,
                                    ),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (hasAd) Switch(
                                checked = enabled, onCheckedChange = { v -> onChange(if (o?.sequence == null) overrides + (p to (o ?: AdOverride()).copy(enabled = v)) else overrideSetOn(kitAds, overrides, p, 0, v)) },
                                modifier = Modifier.semantics { contentDescription = "${adTalkBackLabel(p, 1)} On"; stateDescription = if (enabled) "On" else "Off, switched off" },
                            )
                        }
                        if (hasAd && !enabled) AdOffWarning(adSwitchedOffText(p))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text("${ads.size} ads", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (ads.none { it.enabled }) AdOffChip()
                        }
                        Text(sequenceSetupText(ads), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ads.forEachIndexed { i, r ->
                            val n = i + 1
                            val item = r.assetId?.let { id -> library.firstOrNull { it.id == id } }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (r.assetId != null && loader != null) AssetImage(assetUrl(r.assetId), loader, Modifier.size(40.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Ad $n", style = MaterialTheme.typography.titleSmall)
                                        if (!r.enabled) AdOffChip()
                                    }
                                    Text(
                                        (r.phone?.name ?: item?.name?.ifBlank { null } ?: "Brand kit picture") + if (r.source == AdSource.PHONE) " (this phone, this stream only)" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    )
                                }
                                Switch(
                                    checked = r.enabled, onCheckedChange = { v -> onChange(overrideSetOn(kitAds, overrides, p, i, v)) },
                                    modifier = Modifier.semantics { contentDescription = "${adTalkBackLabel(p, n)} On"; stateDescription = if (r.enabled) "On" else "Off, switched off" },
                                )
                            }
                            if (!r.enabled) AdOffWarning(adSwitchedOffText(p, adNumber = n))
                            if (p == AdPlacement.L_SHAPE && r.source == AdSource.KIT && frameHasWindow == false && i == 0) Text("No clear window in this graphic, so it covers the picture instead of framing it.", style = MaterialTheme.typography.bodySmall, color = Amber)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AdSecondsField(r.schedule, if (r.schedule is AdSchedule.Interval) r.showSec else r.dwellSec, adTalkBackLabel(p, n), Modifier.weight(1f)) { sec -> onChange(overrideSetSeconds(kitAds, overrides, p, i, sec)) }
                                TextButton(onClick = { onChange(overrideMoveAd(kitAds, overrides, p, i, -1)) }, enabled = i > 0, modifier = Modifier.semantics { contentDescription = "Move ${adTalkBackLabel(p, n)} up" }) { Text("Up") }
                                TextButton(onClick = { onChange(overrideMoveAd(kitAds, overrides, p, i, 1)) }, enabled = i < ads.size - 1, modifier = Modifier.semantics { contentDescription = "Move ${adTalkBackLabel(p, n)} down" }) { Text("Down") }
                                TextButton(onClick = { onChange(overrideRemoveAd(kitAds, overrides, p, i)) }, modifier = Modifier.semantics { contentDescription = "Remove ${adTalkBackLabel(p, n)}" }) { Text("Remove") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ads.size <= 1) OutlinedButton(onClick = { pick(p, if (hasAd) 0 else null) }) { Text(if (first?.source == AdSource.PHONE) "Change picture" else "Use a picture from this phone") }
                        if (hasAd && ads.size < MAX_ADS_PER_PLACEMENT) OutlinedButton(onClick = { pick(p, null) }, modifier = Modifier.semantics { contentDescription = "Add another ${p.label} ad from this phone" }) { Text("+ Add another") }
                        if (hasAd) TextButton(onClick = { scheduleFor = p }) { Text("Schedule") }
                    }
                    if (edited) TextButton(onClick = { onChange(resetPlacement(overrides, p)) }) { Text(if (kitAds.any { it.placement == p.wire }) "Use the kit's ads" else "Remove phone pictures") }
                    if (hasAd && ads.size >= MAX_ADS_PER_PLACEMENT) Text("Up to $MAX_ADS_PER_PLACEMENT ads per placement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (anyInterval) {
            Text(AD_CLOCK_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        scheduleFor?.let { p ->
            val cur = currentAds(kitAds, overrides, p).firstOrNull()?.schedule ?: AdSchedule.Continuous
            var draft by remember(p) { mutableStateOf(cur) }
            AlertDialog(
                onDismissRequest = { scheduleFor = null },
                title = { Text("${p.label}: schedule for this stream") },
                text = { ScheduleEditor(draft, { draft = it }) },
                confirmButton = { TextButton(onClick = { if (scheduleError(draft) == null) { onChange(overrideSetSchedule(kitAds, overrides, p, draft)); scheduleFor = null } }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { scheduleFor = null }) { Text("Cancel") } },
            )
        }
    }
}

/**
 * The seconds of one ad of a sequence: "Shown for [n] s" (interval) or "Seconds before the next ad [n]" (continuous). Empty = the default. Only a valid number is
 * passed on ([adSecondsError] says what is wrong, in place), so a half-typed value never changes the stream.
 */
@Composable
fun AdSecondsField(schedule: AdSchedule, value: Int?, talkBack: String, modifier: Modifier = Modifier, onValid: (Int?) -> Unit) {
    var text by remember(value, schedule is AdSchedule.Interval) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = text.toIntOrNull()
    val error = if (text.isNotBlank()) (if (parsed == null) "Enter whole seconds." else adSecondsError(schedule, parsed)) else null
    Column(modifier) {
        OutlinedTextField(
            text, { t -> text = t.filter { c -> c.isDigit() }.take(5); val v = text.toIntOrNull(); if (text.isBlank()) onValid(null) else if (v != null && adSecondsError(schedule, v) == null) onValid(v) },
            Modifier.fillMaxWidth().semantics { contentDescription = "$talkBack seconds" }, singleLine = true, isError = error != null,
            label = { Text(if (schedule is AdSchedule.Interval) "Shown for (s)" else "Before next (s)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun java.io.InputStream.readNBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}

/** "Shrink picture while ads show": the picture makes room for a lower / left third ad instead of being covered. */
@Composable
fun ShrinkSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Shrink picture while ads show", style = MaterialTheme.typography.titleSmall)
            Text("Shrink picture while lower-third and left-third ads show. The L-shaped frame always fits the picture in its window.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Live screen: for each ad of this stream one line saying what it is doing right now (updated every second, counted from the go-live clock),
 * and Show now / Hide / Follow schedule. [clockStartMs] = SystemClock.elapsedRealtime() at go-live (0 = not started).
 */
@Composable
fun AdsLiveSheet(ads: List<LiveAd>, shrink: Boolean, clockStartMs: Long, onShrink: (Boolean) -> Unit, onSet: (AdPlacement, AdManual?) -> Unit, onDismiss: () -> Unit) {
    // One cheap tick per second while the sheet is open (a state read, a few strings); the compositor keeps its own per-frame clock.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val elapsed = adElapsedSec(now, clockStartMs)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ads", style = MaterialTheme.typography.titleMedium)
            Text(AD_CLOCK_NOTE + " On the air for ${formatCountdown(elapsed.toLong())}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShrinkSwitch(shrink, onShrink)
            ads.groupBy { it.placement }.toSortedMap().values.forEach { g0 ->
                val g = g0.sortedBy { it.index }
                if (g.size == 1) {
                    val a = g[0]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.placement.label, style = MaterialTheme.typography.titleSmall)
                            if (!a.enabled && a.problem == null) AdOffChip()
                        }
                        Text(
                            adStatusText(elapsed, AdSlot(a.placement, a.enabled, a.schedule, a.manual), a.source, hasWindow = a.hasWindow, problem = a.problem, hasOnSwitch = false),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!a.enabled && a.problem == null) AdOffWarning(adSwitchedOffText(a.placement, hasOnSwitch = false))
                        PlacementManualControl(a.placement, a.manual, a.problem == null, onSet)
                    }
                } else LivePlacementSequence(g, elapsed, onSet)
            }
            Text("Show now keeps an ad on the picture until you pick Follow schedule. Changes here are for this stream only and are never saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.height(16.dp)) {}
        }
    }
}

/** Show now / Hide / Follow schedule: acts on the WHOLE placement (every ad of it). */
@Composable
private fun PlacementManualControl(p: AdPlacement, manual: AdManual?, usable: Boolean, onSet: (AdPlacement, AdManual?) -> Unit) {
    if (!usable) return
    Segmented(listOf("show", "hide", "auto"), when (manual) { AdManual.SHOWN -> "show"; AdManual.HIDDEN -> "hide"; null -> "auto" }, { when (it) { "show" -> "Show now"; "hide" -> "Hide"; else -> "Follow schedule" } }, { v ->
        onSet(p, when (v) { "show" -> AdManual.SHOWN; "hide" -> AdManual.HIDDEN; else -> null })
    })
}

/**
 * Live sheet card of a placement with SEVERAL ads (1.0.18): which one is on the picture ("Now showing 2 of 3", counted from the go-live clock like the compositor), every ad
 * with its state and seconds (the On switch is not here: it is set in the kit or on Setup before going live), and Show now / Hide / Follow schedule for the whole placement.
 */
@Composable
private fun LivePlacementSequence(g: List<LiveAd>, elapsed: Double, onSet: (AdPlacement, AdManual?) -> Unit) {
    val p = g[0].placement
    val lead = g.firstOrNull { it.problem == null } ?: g[0]
    val seq = g.map { SeqAd(it.problem == null && it.enabled, it.showSec) }
    val usable = g.any { it.problem == null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, style = MaterialTheme.typography.titleSmall)
            Text("${g.size} ads", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (usable && g.none { it.problem == null && it.enabled }) AdOffChip()
        }
        if (usable) {
            val st = adSequenceState(elapsed, seq, seqScheduleOf(lead.schedule), seq.size, SeqState())
            Text(
                sequenceStatusText(elapsed, seq, lead.manual, seqScheduleOf(lead.schedule)) + if (st.index >= 0 && lead.hasWindow == false) " No clear window in the frame graphic, so it covers the picture instead of framing it." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "${p.label}: " + sequenceStatusText(elapsed, seq, lead.manual, seqScheduleOf(lead.schedule)) },
            )
        }
        g.forEachIndexed { i, a ->
            val n = i + 1
            val state = when { a.problem != null -> "Not on the picture: ${a.problem}"; !a.enabled -> "Switched off"; else -> if (a.schedule is AdSchedule.Interval) "Shown for ${formatDuration(a.showSec)}" else "Stays ${formatDuration(a.showSec)}" }
            Text("${adTalkBackLabel(p, n)}: $state" + if (a.source == AdSource.PHONE) " (picture from this phone)" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (a.problem == null && !a.enabled) AdOffWarning(adSwitchedOffText(p, hasOnSwitch = false, adNumber = n))
        }
        PlacementManualControl(p, lead.manual, usable, onSet)
    }
}

@Composable
fun AdsNoteBar(note: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Notice(note, Amber, Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** Small "Off" chip next to an ad's name (text, so it does not rely on colour). */
@Composable
fun AdOffChip(modifier: Modifier = Modifier) {
    Text(
        AD_OFF_CHIP, modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Switched off" },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The warning line for a switched-off ad: warning icon + text in the contrast-safe warning colour; read as one item by TalkBack. */
@Composable
fun AdOffWarning(text: String, modifier: Modifier = Modifier) {
    val warn = warningTextColor()
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = warn, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = warn)
    }
}
