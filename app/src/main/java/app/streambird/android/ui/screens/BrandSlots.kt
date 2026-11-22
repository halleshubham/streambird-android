package app.streambird.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.streambird.android.ads.adSwitchedOffText
import app.streambird.android.ads.MAX_ADS_PER_PLACEMENT
import app.streambird.android.ads.adLabel
import app.streambird.android.ads.adTalkBackLabel
import app.streambird.android.ads.addAd
import app.streambird.android.ads.moveAd
import app.streambird.android.ads.removeAd
import app.streambird.android.ads.replaceAd
import app.streambird.android.ads.setAdEnabled
import app.streambird.android.ads.setAdSeconds
import app.streambird.android.ads.withSchedule
import app.streambird.android.ads.FrameWindowState
import app.streambird.android.ads.frameWindowText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.streambird.android.ads.AdEditSlot
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdsAccess
import app.streambird.android.ads.describeSchedule
import app.streambird.android.ads.scheduleOf
import app.streambird.android.brand.BrandOrientation
import app.streambird.android.brand.BrandSlot
import app.streambird.android.brand.slotAspectWarning
import app.streambird.android.brand.slotHelp
import app.streambird.android.brand.slotSpec
import app.streambird.android.data.AssetItem
import app.streambird.android.data.KitAd
import app.streambird.android.ui.theme.Amber
import coil.ImageLoader

/**
 * One slot of the unified brand-kit editor (logo, wallpaper, lower third, left third, full screen): preview, the suggested size and file rules,
 * a non-blocking warning when the picture's shape is far from the suggestion, and upload / replace / choose from uploaded / remove in place.
 */
@Composable
fun SlotCard(
    orientation: BrandOrientation,
    slot: BrandSlot,
    assetId: String?,
    library: List<AssetItem>,
    loader: ImageLoader,
    assetUrl: (String) -> String,
    storageEnabled: Boolean,
    busy: Boolean,
    onChoose: (String) -> Unit,
    onUpload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    headerEnd: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    var picking by remember { mutableStateOf(false) }
    val spec = slotSpec(orientation, slot)
    val item = assetId?.let { id -> library.firstOrNull { it.id == id } }
    val warning = item?.let { slotAspectWarning(orientation, slot, it.width, it.height) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                headerEnd?.invoke()
            }
            Text("Suggested size ${spec.sizeText}", style = MaterialTheme.typography.labelMedium)
            Text(slotHelp(orientation, slot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AssetImage(assetId?.let(assetUrl), loader, Modifier.size(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onUpload, enabled = storageEnabled && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (assetId == null) "Upload" else "Replace") }
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose from uploaded") }
                    if (assetId != null) TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
            if (item != null) Text(
                "${item.name.ifBlank { "Untitled" }}, ${item.width} x ${item.height}" + if (item.isGif) ", animated GIF" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            if (warning != null) Text(warning, style = MaterialTheme.typography.bodySmall, color = Amber)
            extra?.invoke()
        }
    }
    if (picking) AssetPickerDialog("${slot.label}: choose from uploaded", library, loader, assetUrl, onChoose = { onChoose(it); picking = false }, onDismiss = { picking = false })
}

/** "Choose from uploaded": the library files of one kind, tap one to pick it. */
@Composable
fun AssetPickerDialog(title: String, library: List<AssetItem>, loader: ImageLoader, assetUrl: (String) -> String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (library.isEmpty()) Text("Nothing uploaded for this slot yet. Use \"Upload\".")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(library, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onChoose(a.id) }.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetImage(assetUrl(a.id), loader, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name.ifBlank { "Untitled" } + if (a.isGif) " (GIF)" else "", maxLines = 1)
                            Text("${a.width} x ${a.height}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun AdPlacement.slot() = when (this) {
    AdPlacement.LOWER_THIRD -> BrandSlot.LOWER_THIRD
    AdPlacement.SIDE_THIRD -> BrandSlot.SIDE_THIRD
    AdPlacement.FULL_SCREEN -> BrandSlot.FULL_SCREEN
    AdPlacement.L_SHAPE -> BrandSlot.L_SHAPE
}

/**
 * The picture of an L-shaped frame with the detected window outlined, and the verdict as a line of text. A missing window is a NON-BLOCKING warning
 * (the ad still saves; on air it is drawn as a full-screen overlay). The picture is fetched once per file ([frameWindow] caches).
 */
@Composable
fun FrameWindowPreview(assetId: String, item: AssetItem?, loader: ImageLoader, assetUrl: (String) -> String, frameWindow: suspend (String) -> FrameWindowState) {
    val state by produceState<FrameWindowState>(FrameWindowState.Checking, assetId) { value = FrameWindowState.Checking; value = frameWindow(assetId) }
    val aspect = item?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    val outline = MaterialTheme.colorScheme.primary
    Box(Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(aspect)) {
        AssetImage(assetUrl(assetId), loader, Modifier.fillMaxWidth().aspectRatio(aspect))
        (state as? FrameWindowState.Found)?.let { f ->
            Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                drawRect(
                    outline, Offset((f.window.x * size.width).toFloat(), (f.window.y * size.height).toFloat()),
                    Size((f.window.w * size.width).toFloat(), (f.window.h * size.height).toFloat()), style = Stroke(2.dp.toPx()),
                )
            }
        }
    }
    val text = frameWindowText(state)
    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = if (state is FrameWindowState.Found) MaterialTheme.colorScheme.onSurfaceVariant else Amber)
}

/**
 * The three ad slots of ONE set (16:9 or 9:16) of the kit. Editable only when the plan allows custom ads and its limits have loaded;
 * otherwise the saved ads of this set are shown read-only with a way to remove them (sends `ads: []` / `adsPortrait: []`) and an explanation.
 */
@Composable
fun KitAdsSection(
    orientation: BrandOrientation,
    access: AdsAccess,
    savedAds: List<KitAd>,
    slots: List<AdEditSlot>,
    onSlots: (List<AdEditSlot>) -> Unit,
    library: List<AssetItem>,
    loader: ImageLoader,
    assetUrl: (String) -> String,
    storageEnabled: Boolean,
    /** (placement, index of the ad to replace, null = add another). */
    onUpload: (AdPlacement, Int?) -> Unit,
    onRemoveAds: () -> Unit,
    busy: Boolean,
    shrink: Boolean = true,
    frameWindow: suspend (String) -> FrameWindowState = { FrameWindowState.Checking },
) {
    Section("Ads (${orientation.label})") {
        when (access) {
            AdsAccess.LOADING -> Text("Checking your plan...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdsAccess.FREE_PLAN, AdsAccess.NO_STORAGE -> {
                Text(
                    if (access == AdsAccess.FREE_PLAN) "Your own ads are not included in this account's plan. A StreamBird banner is shown instead."
                    else "This account's plan has no brand library, so ads cannot be edited.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (savedAds.isEmpty()) Text("No ${orientation.label} ads saved on this kit.", style = MaterialTheme.typography.bodySmall)
                savedAds.forEachIndexed { idx, a ->
                    val p = AdPlacement.fromWire(a.placement) ?: return@forEachIndexed
                    val num = savedAds.take(idx + 1).count { it.placement == a.placement }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetImage(assetUrl(a.assetId), loader, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(adLabel(p, num), style = MaterialTheme.typography.titleSmall)
                                if (!a.enabled) AdOffChip()
                            }
                            Text((if (a.enabled) "" else "Off, ") + describeSchedule(scheduleOf(a)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!a.enabled) AdOffWarning(adSwitchedOffText(p, adNumber = num))
                        }
                    }
                }
                if (savedAds.isNotEmpty()) OutlinedButton(onClick = onRemoveAds, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Remove ${orientation.label} ads", color = MaterialTheme.colorScheme.error) }
            }
            AdsAccess.EDITABLE -> {
                Text("Up to $MAX_ADS_PER_PLACEMENT ads per placement (lower third, left third, full screen, L-shaped frame), each a picture or an animated GIF, shown all the time or now and then. Several ads of one placement play in turn.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fun change(p: AdPlacement, f: (AdEditSlot) -> AdEditSlot) = onSlots(slots.map { if (it.placement == p) f(it) else it })
                slots.forEach { s ->
                    var addingFor by remember(s.placement) { mutableStateOf(false) }
                    val n = s.count
                    SlotCard(
                        orientation, s.placement.slot(), s.assetId, library, loader, assetUrl, storageEnabled, busy,
                        onChoose = { id -> change(s.placement) { if (it.assetId == null) it.addAd(id) else it.replaceAd(0, id) } },
                        onUpload = { onUpload(s.placement, 0) },
                        onRemove = { change(s.placement) { it.removeAd(0) } },
                        headerEnd = {
                            if (n >= 2) Text("$n ads", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.assetId != null) {
                                if (!s.enabled) AdOffChip(Modifier.padding(end = 8.dp))
                                Switch(
                                    checked = s.enabled, onCheckedChange = { v -> change(s.placement) { it.setAdEnabled(0, v) } },
                                    modifier = Modifier.semantics { contentDescription = "${adTalkBackLabel(s.placement, 1)} On"; stateDescription = if (s.enabled) "On" else "Off, switched off" },
                                )
                            }
                        },
                        extra = {
                            if (s.assetId != null && !s.enabled) AdOffWarning(adSwitchedOffText(s.placement))
                            if (s.placement == AdPlacement.L_SHAPE && s.assetId != null) {
                                FrameWindowPreview(s.assetId, library.firstOrNull { it.id == s.assetId }, loader, assetUrl, frameWindow)
                            }
                            if (s.assetId != null) {
                                ScheduleEditor(s.schedule, { sch -> change(s.placement) { it.withSchedule(sch) } })
                                if (s.schedule is AdSchedule.Interval) Text(describeSchedule(s.schedule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (n >= 2) AdSequenceControls(s, 0, onSeconds = { sec -> change(s.placement) { it.setAdSeconds(0, sec) } }, onMove = { d -> change(s.placement) { it.moveAd(0, d) } }, onRemove = null)
                                // The 2nd..5th ads of the placement, in play order.
                                s.more.forEachIndexed { k, a ->
                                    val i = k + 1; val num = i + 1
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            AssetImage(assetUrl(a.assetId), loader, Modifier.size(48.dp))
                                            Column(Modifier.weight(1f)) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Ad $num", style = MaterialTheme.typography.titleSmall)
                                                    if (!a.enabled) AdOffChip()
                                                }
                                                Text(library.firstOrNull { it.id == a.assetId }?.name?.ifBlank { null } ?: "Picture", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                            Switch(
                                                checked = a.enabled, onCheckedChange = { v -> change(s.placement) { it.setAdEnabled(i, v) } },
                                                modifier = Modifier.semantics { contentDescription = "${adTalkBackLabel(s.placement, num)} On"; stateDescription = if (a.enabled) "On" else "Off, switched off" },
                                            )
                                        }
                                        if (!a.enabled) AdOffWarning(adSwitchedOffText(s.placement, adNumber = num))
                                        AdSequenceControls(s, i, onSeconds = { sec -> change(s.placement) { it.setAdSeconds(i, sec) } }, onMove = { d -> change(s.placement) { it.moveAd(i, d) } }, onRemove = { change(s.placement) { it.removeAd(i) } })
                                    }
                                }
                                if (s.canAddAnother) OutlinedButton(
                                    onClick = { addingFor = true }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add another ${s.placement.label} ad" },
                                ) { Text("+ Add another") }
                                else Text("Up to $MAX_ADS_PER_PLACEMENT ads per placement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                    if (addingFor) AlertDialog(
                        onDismissRequest = { addingFor = false },
                        title = { Text("${s.placement.label}: add another ad") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { addingFor = false; onUpload(s.placement, null) }, enabled = storageEnabled && !busy, modifier = Modifier.fillMaxWidth()) { Text("Upload a picture") }
                                Text("or choose one you uploaded:", style = MaterialTheme.typography.bodySmall)
                                if (library.isEmpty()) Text("Nothing uploaded yet.", style = MaterialTheme.typography.bodySmall)
                                library.take(30).forEach { a ->
                                    Row(Modifier.fillMaxWidth().clickable { change(s.placement) { it.addAd(a.id) }; addingFor = false }.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AssetImage(assetUrl(a.id), loader, Modifier.size(40.dp))
                                        Text(a.name.ifBlank { "Untitled" } + if (a.isGif) " (GIF)" else "", Modifier.weight(1f), maxLines = 1)
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { addingFor = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

/** Seconds field plus Up / Down / Remove for ONE ad of a placement's sequence in the kit editor ([onRemove] null = no Remove: the first ad has its own on the card). */
@Composable
private fun AdSequenceControls(s: AdEditSlot, index: Int, onSeconds: (Int?) -> Unit, onMove: (Int) -> Unit, onRemove: (() -> Unit)?) {
    val n = index + 1
    val label = adTalkBackLabel(s.placement, n)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        AdSecondsField(s.schedule, s.ads.getOrNull(index)?.showSec, label, Modifier.weight(1f), onSeconds)
        TextButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = "Move $label up" }) { Text("Up") }
        TextButton(onClick = { onMove(1) }, enabled = index < s.count - 1, modifier = Modifier.semantics { contentDescription = "Move $label down" }) { Text("Down") }
        if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove $label" }) { Text("Remove") }
    }
}
