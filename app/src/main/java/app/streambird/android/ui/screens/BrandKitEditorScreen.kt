package app.streambird.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.streambird.android.ads.AdEditSlot
import app.streambird.android.ads.AdPlacement
import app.streambird.android.brand.BrandOrientation
import app.streambird.android.brand.BrandSlot
import app.streambird.android.brand.KitDeleteText
import app.streambird.android.ads.AdsAccess
import app.streambird.android.ads.adsAccess
import app.streambird.android.ads.adsEdited
import app.streambird.android.ads.kitAdsFromSlots
import app.streambird.android.ads.slotsFromKit
import app.streambird.android.ads.validateSlots
import app.streambird.android.data.BrandKitPayload
import app.streambird.android.data.KitDraft
import app.streambird.android.data.PlatformConnection
import app.streambird.android.ui.BrandViewModel
import app.streambird.android.ui.theme.Ok

private fun styleLabel(s: String) = when (s) { "bar" -> "Bar"; "accent" -> "Accent"; "glass" -> "Glass"; else -> s }

/** Where a picked file goes once uploaded. */
private sealed interface UploadTarget {
    data class Logo(val o: BrandOrientation) : UploadTarget
    data class Wallpaper(val o: BrandOrientation) : UploadTarget
    data class Ad(val o: BrandOrientation, val p: AdPlacement) : UploadTarget
}

/**
 * Create (kitId == null) or edit a brand kit. One unified editor: each slot (logo, wallpaper, lower / left third / full screen ad) is uploaded,
 * replaced, chosen from the uploaded files or removed in place, for the 16:9 set and the 9:16 set (toggle). Position, size, style and the
 * shrink switch are shared by both sets. The wallpaper dim of an existing kit is carried over untouched (see BrandKitPayload.patch).
 */
@Composable
fun BrandKitEditorScreen(kitId: String?, connections: List<PlatformConnection>, onBack: () -> Unit) {
    val vm: BrandViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.clearMsg(); if (!st.loaded) vm.refresh() }
    val original = st.kits.firstOrNull { it.id == kitId }
    val key = original?.id ?: "new"

    var orientation by remember { mutableStateOf(BrandOrientation.LANDSCAPE) }
    var name by remember(key) { mutableStateOf(original?.name ?: "") }
    var logoId by remember(key) { mutableStateOf(original?.logoAssetId) }
    var logoIdP by remember(key) { mutableStateOf(original?.logoAssetIdPortrait) }
    var wallId by remember(key) { mutableStateOf(original?.wallpaperAssetId) }
    var wallIdP by remember(key) { mutableStateOf(original?.wallpaperAssetIdPortrait) }
    var position by remember(key) { mutableStateOf(original?.logoPosition ?: "left") }
    var size by remember(key) { mutableStateOf((original?.logoSize ?: 90).toFloat()) }
    var style by remember(key) { mutableStateOf(original?.lowerThirdStyle ?: "accent") }
    var shrink by remember(key) { mutableStateOf(original?.adsShrinkPicture ?: true) }
    var isDefault by remember(key) { mutableStateOf(original?.isDefault ?: false) }
    var channels by remember(key) { mutableStateOf(original?.connectionIds?.toSet() ?: emptySet()) }
    var adSlots by remember(key) { mutableStateOf(slotsFromKit(original?.ads.orEmpty())) }
    var adSlotsP by remember(key) { mutableStateOf(slotsFromKit(original?.adsPortrait.orEmpty())) }
    var target by remember { mutableStateOf<UploadTarget?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val t = target; target = null
        if (uri == null || t == null) return@rememberLauncherForActivityResult
        when (t) {
            is UploadTarget.Logo -> vm.upload(uri, "logo") { id -> if (t.o == BrandOrientation.LANDSCAPE) logoId = id else logoIdP = id }
            is UploadTarget.Wallpaper -> vm.upload(uri, "wallpaper") { id -> if (t.o == BrandOrientation.LANDSCAPE) wallId = id else wallIdP = id }
            is UploadTarget.Ad -> vm.upload(uri, "ad") { id ->
                val f = { l: List<AdEditSlot> -> l.map { if (it.placement == t.p) it.copy(assetId = id) else it } }
                if (t.o == BrandOrientation.LANDSCAPE) adSlots = f(adSlots) else adSlotsP = f(adSlotsP)
            }
        }
    }
    fun pick(t: UploadTarget) { target = t; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val kitNames = st.kits.flatMap { k -> k.connectionIds.map { it to k } }.toMap()
    val access = adsAccess(st.plan)
    val portrait = orientation == BrandOrientation.PORTRAIT
    val adsError = if (access == AdsAccess.EDITABLE) validateSlots(if (portrait) adSlotsP else adSlots) else null
    val adsErrorOther = if (access == AdsAccess.EDITABLE) validateSlots(if (portrait) adSlots else adSlotsP) else null
    val canSave = name.isNotBlank() && name.length <= 80 && !busy && !st.blocked && adsError == null && adsErrorOther == null
    val title = if (kitId == null) "New brand kit" else "Edit brand kit"

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubTopBar(title, onBack)
        if (kitId != null && st.loaded && original == null) Notice("This brand kit no longer exists.", MaterialTheme.colorScheme.error)
        msg?.let { Notice(it.text, if (it.error) MaterialTheme.colorScheme.error else Ok) }
        if (!st.loaded) CircularProgressIndicator()
        if (st.blocked) Notice("Brand kits are not included in this account's plan.", MaterialTheme.colorScheme.error)
        if (!st.storageEnabled) Text("Brand storage is not enabled on this server yet, so uploads are off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Section("Picture shape") {
            Segmented(BrandOrientation.entries, orientation, { it.label }, { orientation = it })
            Text(
                "Each kit has a 16:9 set (landscape streams) and a 9:16 set (vertical streams). A stream uses the set that matches its shape." +
                    if (portrait) " A 9:16 set without its own logo uses the 16:9 logo; wallpaper and ads have no fallback." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val curLogo = if (portrait) logoIdP else logoId
        Section("Logo (" + orientation.label + ")") {
            val logoPreview: @Composable () -> Unit = {
                KitPreview(
                    (curLogo ?: if (portrait) logoId else null)?.let { vm.repo.assetUrl(it) }, vm.repo.imageLoader, position, size.toInt(),
                    if (portrait) 200.dp else 112.dp, portrait = portrait,
                )
            }
            val logoCard: @Composable (Modifier) -> Unit = { mod ->
                SlotCard(
                    orientation, BrandSlot.LOGO, curLogo, st.library?.logos.orEmpty(), vm.repo.imageLoader, vm.repo::assetUrl, st.storageEnabled, busy,
                    onChoose = { if (portrait) logoIdP = it else logoId = it },
                    onUpload = { pick(UploadTarget.Logo(orientation)) },
                    onRemove = { if (portrait) logoIdP = null else logoId = null },
                    modifier = mod,
                    extra = {
                        if (portrait && logoIdP == null && logoId != null) Text("Using the 16:9 logo.", style = MaterialTheme.typography.bodySmall)
                        if (st.library?.items?.firstOrNull { it.id == curLogo }?.isGif == true) Text("Animated GIF: shown animated here; the broadcast uses its first frame.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
            // A wide 16:9 frame would squeeze the card beside it, so in 16:9 the preview sits above the card.
            if (portrait) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { logoPreview(); logoCard(Modifier.weight(1f)) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { logoPreview(); logoCard(Modifier.fillMaxWidth()) }
            }
        }

        Section("Wallpaper (" + orientation.label + ")") {
            SlotCard(
                orientation, BrandSlot.WALLPAPER, if (portrait) wallIdP else wallId, st.library?.wallpapers.orEmpty(), vm.repo.imageLoader, vm.repo::assetUrl, st.storageEnabled, busy,
                onChoose = { if (portrait) wallIdP = it else wallId = it },
                onUpload = { pick(UploadTarget.Wallpaper(orientation)) },
                onRemove = { if (portrait) wallIdP = null else wallId = null },
                extra = { Text("Saved on the kit for the website studio; the app does not draw wallpapers yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }

        Section("Logo position") {
            Segmented(listOf("left", "right"), position, { if (it == "left") "Left" else "Right" }, { position = it })
        }
        Section("Logo size: ${size.toInt()} px") {
            Slider(value = size, onValueChange = { size = it }, valueRange = BrandKitPayload.MIN_LOGO.toFloat()..BrandKitPayload.MAX_LOGO.toFloat())
            Text("Height on the picture, in pixels of the 1280 px tall frame (like the website).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("Lower-third style") {
            Segmented(BrandKitPayload.STYLES, style, ::styleLabel, { style = it })
            Text("Saved on the kit for the website studio; the app does not draw lower thirds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Use as account default", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (original?.isDefault == true) "To change the default, make another kit the default." else "Used for channels that have no kit of their own.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isDefault, onCheckedChange = { isDefault = it }, enabled = original?.isDefault != true)
        }

        Section("Channels that use this kit") {
            if (connections.isEmpty()) Text("No channels connected yet.", style = MaterialTheme.typography.bodyMedium)
            connections.forEach { c ->
                val other = kitNames[c.id]?.takeIf { it.id != original?.id }
                Card(Modifier.fillMaxWidth().clickable { channels = if (c.id in channels) channels - c.id else channels + c.id }) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.id in channels, onCheckedChange = { channels = if (it) channels + c.id else channels - c.id })
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (other != null) Text(
                                if (c.id in channels) "Will move from \"${other.name}\"" else "Now uses \"${other.name}\"",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PlatformBadge(c.platform)
                    }
                }
            }
            Text("Unticked channels go back to the account default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Shrink picture while ads show") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Lower and left third ads get their own space instead of covering the picture. Full screen ads always cover it.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = shrink, onCheckedChange = { shrink = it })
            }
        }

        KitAdsSection(
            orientation = orientation, access = access,
            savedAds = (if (portrait) original?.adsPortrait else original?.ads).orEmpty(),
            slots = if (portrait) adSlotsP else adSlots, onSlots = { if (portrait) adSlotsP = it else adSlots = it },
            library = st.library?.adFiles.orEmpty(), loader = vm.repo.imageLoader, assetUrl = vm.repo::assetUrl,
            storageEnabled = st.storageEnabled,
            onUpload = { p -> pick(UploadTarget.Ad(orientation, p)) },
            onRemoveAds = { original?.let { o ->
                val d = BrandKitPayload.draftOf(o)
                vm.save(o, if (portrait) d.copy(adsPortrait = emptyList()) else d.copy(ads = emptyList()), o.connectionIds.toSet()) { }
            } },
            busy = busy,
        )
        (adsError ?: adsErrorOther)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Button(
            onClick = {
                // Each set's ads are sent ONLY when the user changed them (omitted otherwise: other edits never touch them).
                val ads = if (access == AdsAccess.EDITABLE && adsEdited(original?.ads.orEmpty(), adSlots)) kitAdsFromSlots(adSlots) else null
                val adsP = if (access == AdsAccess.EDITABLE && adsEdited(original?.adsPortrait.orEmpty(), adSlotsP)) kitAdsFromSlots(adSlotsP) else null
                vm.save(
                    original,
                    KitDraft(
                        name, logoId, position, size.toInt(), style, isDefault, ads, wallpaperAssetId = wallId,
                        logoAssetIdPortrait = logoIdP, wallpaperAssetIdPortrait = wallIdP, adsPortrait = adsP, adsShrinkPicture = shrink,
                    ),
                    channels,
                ) { onBack() }
            },
            enabled = canSave && (kitId == null || original != null), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (kitId == null) "Create brand kit" else "Save changes") }
        if (original != null) OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete this kit", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete && original != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(KitDeleteText.title(original.name)) },
        text = { Text(KitDeleteText.BODY) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteKit(original.id, original.name) { onBack() } }) { Text(KitDeleteText.CONFIRM, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(KitDeleteText.CANCEL) } },
    )
}
