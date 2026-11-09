package app.streambird.android.ui.screens

import androidx.compose.foundation.clickable
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
 * Setup screen: kit ads apply automatically with the kit; per placement the user can switch an ad off, change its schedule, or use a picture from
 * the phone for THIS stream only (kept in memory, not uploaded). Free plan: only an explanation.
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ads", style = MaterialTheme.typography.titleSmall)
        if (freePlan) {
            Text("On this account's plan a StreamBird banner shows now and then and a small \"Powered by StreamBird\" mark stays on the picture. Your own ads are not included in this account's plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var pickFor by remember { mutableStateOf<AdPlacement?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var scheduleFor by remember { mutableStateOf<AdPlacement?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val p = pickFor; pickFor = null
            if (uri == null || p == null) return@rememberLauncherForActivityResult
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
                if (res.first == null) res.second?.let { ph -> onChange(overrides + (p to (overrides[p] ?: AdOverride()).copy(phone = ph, enabled = true))) }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.warn) Amber else MaterialTheme.colorScheme.onSurfaceVariant) }
        ShrinkSwitch(shrink ?: kitShrink, { v -> onShrink(if (v == kitShrink) null else v) })
        AdPlacement.entries.forEach { p ->
            val kit = kitAds.firstOrNull { it.placement == p.wire }
            val o = overrides[p]
            val hasAd = kit != null || o?.phone != null
            val enabled = o?.enabled ?: kit?.enabled ?: true
            val sched = o?.schedule ?: kit?.let { app.streambird.android.ads.scheduleOf(it) } ?: AdSchedule.Continuous
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (!hasAd) "No ad"
                                else adStatusText(
                                    null, AdSlot(p, enabled, sched), if (o?.phone != null) AdSource.PHONE else AdSource.KIT,
                                    shrinkOn = shrink ?: kitShrink, hasWindow = if (p == AdPlacement.L_SHAPE && o?.phone == null) frameHasWindow else null,
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hasAd) Switch(checked = enabled, onCheckedChange = { v -> onChange(overrides + (p to (o ?: AdOverride()).copy(enabled = v))) })
                    }
                    if (p == AdPlacement.L_SHAPE && hasAd && !(shrink ?: kitShrink)) Text(app.streambird.android.ads.FRAME_NEEDS_SHRINK, style = MaterialTheme.typography.bodySmall, color = Amber)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickFor = p; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (o?.phone != null) "Change picture" else "Use a picture from this phone") }
                        if (hasAd) TextButton(onClick = { scheduleFor = p }) { Text("Schedule") }
                        if (o?.phone != null) TextButton(onClick = { onChange(overrides + (p to o.copy(phone = null))) }) { Text("Use kit") }
                    }
                }
            }
        }
        if (AdPlacement.entries.any { p -> (overrides[p]?.schedule ?: kitAds.firstOrNull { it.placement == p.wire }?.let { app.streambird.android.ads.scheduleOf(it) }) is AdSchedule.Interval }) {
            Text(AD_CLOCK_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        scheduleFor?.let { p ->
            val cur = overrides[p]?.schedule ?: kitAds.firstOrNull { it.placement == p.wire }?.let { app.streambird.android.ads.scheduleOf(it) } ?: AdSchedule.Continuous
            var draft by remember(p) { mutableStateOf(cur) }
            AlertDialog(
                onDismissRequest = { scheduleFor = null },
                title = { Text("${p.label}: schedule for this stream") },
                text = { ScheduleEditor(draft, { draft = it }) },
                confirmButton = { TextButton(onClick = { if (scheduleError(draft) == null) { onChange(overrides + (p to (overrides[p] ?: AdOverride()).copy(schedule = draft))); scheduleFor = null } }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { scheduleFor = null }) { Text("Cancel") } },
            )
        }
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
            Text("Lower and left third ads get their own space instead of covering the picture. Full screen ads always cover it. L-shaped ads always resize the picture.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ads.forEach { a ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.placement.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        adStatusText(elapsed, AdSlot(a.placement, a.enabled, a.schedule, a.manual), a.source, shrinkOn = shrink, hasWindow = a.hasWindow, problem = a.problem),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (a.placement == AdPlacement.L_SHAPE && !shrink) Text(app.streambird.android.ads.FRAME_NEEDS_SHRINK, style = MaterialTheme.typography.bodySmall, color = Amber)
                    if (a.problem == null) Segmented(listOf("show", "hide", "auto"), when (a.manual) { AdManual.SHOWN -> "show"; AdManual.HIDDEN -> "hide"; null -> "auto" }, { when (it) { "show" -> "Show now"; "hide" -> "Hide"; else -> "Follow schedule" } }, { v ->
                        onSet(a.placement, when (v) { "show" -> AdManual.SHOWN; "hide" -> AdManual.HIDDEN; else -> null })
                    })
                }
            }
            Text("Show now keeps an ad on the picture until you pick Follow schedule. Changes here are for this stream only and are never saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.height(16.dp)) {}
        }
    }
}

@Composable
fun AdsNoteBar(note: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Notice(note, Amber, Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
