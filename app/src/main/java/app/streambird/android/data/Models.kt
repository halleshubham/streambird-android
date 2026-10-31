package app.streambird.android.data

import kotlinx.serialization.Serializable

// Shapes mirror the backend DTOs (see docs/API-CONTRACT.md). Unknown fields are ignored on purpose:
// the backend returns more than the app needs (e.g. brand kits include createdAt/updatedAt/accountId).

@Serializable
data class AuthUser(
    val id: String = "",
    val email: String = "",
    val role: String = "user",
    val approvedAt: String? = null,
    val phone: String? = null,
    val needsPhone: Boolean = false,
) {
    /** Superadmins skip approval (AccountGuard); everybody else needs approvedAt. */
    val isApproved: Boolean get() = role == "superadmin" || approvedAt != null
}

@Serializable
data class AuthResponse(val user: AuthUser, val accountId: String = "")

@Serializable
data class PlatformConnection(
    val id: String,
    val platform: String = "",
    val externalAccountId: String = "",
    val label: String = "",
    val isActive: Boolean = true,
    val liveSlug: String? = null,
    val liveLinkUrl: String? = null,
)

@Serializable
data class EffectiveLimits(
    val planKey: String = "free",
    val planName: String = "",
    val includedHours: Double? = null,
    val maxDestinations: Int = 1,
    val maxGuests: Int = 0,
    /** sd | hd | fhd | qhd */
    val maxResolution: String = "sd",
    val canRecord: Boolean = false,
    val canChromaKey: Boolean = false,
    val canLowerThird: Boolean = false,
    /** 0 = no brand library (Free): brand kits are paid-plan-only. */
    val assetStorageMb: Int = 0,
    /** Show "Powered by StreamBird" on this account's streams. */
    val watermark: Boolean = false,
    val maxSessionHours: Double? = null,
    val planExpired: Boolean = false,
)

@Serializable
data class BrandKit(
    val id: String,
    val name: String,
    val logoAssetId: String? = null,
    val logoPosition: String = "left",
    val logoSize: Int = 90,
    /** Not editable in the app, but sent back unchanged on PATCH (see BrandKitPayload). */
    val wallpaperAssetId: String? = null,
    val wallpaperDim: Int = 0,
    val lowerThirdStyle: String = "accent",
    val isDefault: Boolean = false,
    val connectionIds: List<String> = emptyList(),
    /** Up to three ads (lower_third, side_third, full_screen), one per placement. Absent on older servers. */
    val ads: List<KitAd> = emptyList(),
)

/** One ad of a brand kit. `everySec` is the HIDDEN time between showings (not the period); continuous ads have null seconds. */
@Serializable
data class KitAd(
    val placement: String,
    val assetId: String,
    val enabled: Boolean = true,
    val mode: String = "continuous",
    val showSec: Int? = null,
    val everySec: Int? = null,
)

@Serializable
data class CreateStreamRequest(
    val title: String,
    val visibility: String = "public",
    val orientation: String = "portrait",
    val destinationConnectionIds: List<String>,
)

@Serializable
data class DestinationDto(
    val id: String,
    val platformConnectionId: String,
    val status: String,
    val viewerCount: Int? = null,
    val watchUrl: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class StreamDto(
    val id: String,
    val title: String,
    val status: String,
    val whipUrl: String? = null,
    val visibility: String? = null,
    val orientation: String = "landscape",
    val studioSessionId: String? = null,
    val destinations: List<DestinationDto> = emptyList(),
)

@Serializable
data class StreamStatusDestination(
    val id: String,
    val platformConnectionId: String,
    val status: String,
    val platformStatus: String? = null,
    val viewerCount: Int? = null,
    val watchUrl: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class StreamStatusResponse(
    val status: String,
    val relayInputStatus: String? = null,
    val destinations: List<StreamStatusDestination> = emptyList(),
)

@Serializable
data class IceServerDto(
    val urls: kotlinx.serialization.json.JsonElement,
    val username: String? = null,
    val credential: String? = null,
) {
    fun urlList(): List<String> = when (urls) {
        is kotlinx.serialization.json.JsonArray -> urls.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        is kotlinx.serialization.json.JsonPrimitive -> listOf(urls.content)
        else -> emptyList()
    }
}

@Serializable
data class TurnCredentialsResponse(val iceServers: List<IceServerDto> = emptyList())

/** Nest's default error body: {"statusCode":403,"message":"..." | ["..."],"error":"Forbidden"}. */
@Serializable
data class ErrorBody(
    val statusCode: Int? = null,
    val message: kotlinx.serialization.json.JsonElement? = null,
    val error: String? = null,
)
