package app.streambird.android.ui.screens

import androidx.compose.foundation.clickable
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
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("${slot.label}: choose from uploaded") },
        text = {
            if (library.isEmpty()) Text("Nothing uploaded for this slot yet. Use \"Upload\".")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(library, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onChoose(a.id); picking = false }.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
    )
}

private fun AdPlacement.slot() = when (this) {
    AdPlacement.LOWER_THIRD -> BrandSlot.LOWER_THIRD
    AdPlacement.SIDE_THIRD -> BrandSlot.SIDE_THIRD
    AdPlacement.FULL_SCREEN -> BrandSlot.FULL_SCREEN
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
    onUpload: (AdPlacement) -> Unit,
    onRemoveAds: () -> Unit,
    onBilling: () -> Unit,
    busy: Boolean,
) {
    Section("Ads (${orientation.label})") {
        when (access) {
            AdsAccess.LOADING -> Text("Checking your plan...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdsAccess.FREE_PLAN, AdsAccess.NO_STORAGE -> {
                Text(
                    if (access == AdsAccess.FREE_PLAN) "Your own ads are on the paid plans. The Free plan shows a StreamBird banner instead."
                    else "Your plan has no brand library, so ads cannot be edited.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (savedAds.isEmpty()) Text("No ${orientation.label} ads saved on this kit.", style = MaterialTheme.typography.bodySmall)
                savedAds.forEach { a ->
                    val p = AdPlacement.fromWire(a.placement) ?: return@forEach
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssetImage(assetUrl(a.assetId), loader, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text((if (a.enabled) "" else "Off, ") + describeSchedule(scheduleOf(a)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (savedAds.isNotEmpty()) OutlinedButton(onClick = onRemoveAds, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Remove ${orientation.label} ads", color = MaterialTheme.colorScheme.error) }
                if (access == AdsAccess.FREE_PLAN) Button(onClick = onBilling) { Text("See plans") }
            }
            AdsAccess.EDITABLE -> {
                Text("One ad per placement, a picture or an animated GIF, shown all the time or now and then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fun change(p: AdPlacement, f: (AdEditSlot) -> AdEditSlot) = onSlots(slots.map { if (it.placement == p) f(it) else it })
                slots.forEach { s ->
                    SlotCard(
                        orientation, s.placement.slot(), s.assetId, library, loader, assetUrl, storageEnabled, busy,
                        onChoose = { id -> change(s.placement) { it.copy(assetId = id) } },
                        onUpload = { onUpload(s.placement) },
                        onRemove = { change(s.placement) { it.copy(assetId = null) } },
                        headerEnd = { if (s.assetId != null) Switch(checked = s.enabled, onCheckedChange = { v -> change(s.placement) { it.copy(enabled = v) } }) },
                        extra = {
                            if (s.assetId != null) {
                                ScheduleEditor(s.schedule, { sch -> change(s.placement) { it.copy(schedule = sch) } })
                                if (s.schedule is AdSchedule.Interval) Text(describeSchedule(s.schedule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}
