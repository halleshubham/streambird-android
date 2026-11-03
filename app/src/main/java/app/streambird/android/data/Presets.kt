package app.streambird.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What a preset remembers (backend `config`). Every field is optional both ways: a field the server did not send is null here,
 * and a null field is not sent (the server rejects unknown keys, so [presetConfigJson] writes exactly these six and nothing else).
 */
@Serializable
data class PresetConfig(
    /** "landscape" | "portrait". */
    val orientation: String? = null,
    /** Platform connection ids. */
    val destinationIds: List<String>? = null,
    /** null = automatic brand kit. */
    val brandKitId: String? = null,
    /** Quality as the plan resolution names the web uses: "sd" | "hd" | "fhd" (see [app.streambird.android.preset.tierToWire]). */
    val quality: String? = null,
    val tickerEnabled: Boolean? = null,
    val tickerText: String? = null,
)

/** One saved preset (GET /api/presets row). `lastUsedAt` is an ISO-8601 instant or null when never used. */
@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val config: PresetConfig = PresetConfig(),
    val lastUsedAt: String? = null,
) {
    /** The card the app shows when the server has no presets feature (or no presets yet): not stored anywhere. */
    val isImplicit get() = id.isEmpty()

    companion object {
        const val MAX_NAME = 80
        const val MAX_TICKER = 200
    }
}

/** Exactly the keys the backend accepts. Nulls are left out; the ticker text is capped at 200 characters. */
fun presetConfigJson(c: PresetConfig): JsonObject = buildJsonObject {
    c.orientation?.let { put("orientation", it) }
    c.destinationIds?.let { ids -> put("destinationIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }
    c.brandKitId?.let { put("brandKitId", it) }
    c.quality?.let { put("quality", it) }
    c.tickerEnabled?.let { put("tickerEnabled", it) }
    c.tickerText?.let { put("tickerText", it.take(Preset.MAX_TICKER)) }
}

/** POST /api/presets body. */
fun presetCreateJson(name: String, c: PresetConfig): JsonObject = buildJsonObject {
    put("name", name.trim().take(Preset.MAX_NAME))
    put("config", presetConfigJson(c))
}

/** PATCH /api/presets/:id body: only what is given. */
fun presetPatchJson(name: String?, c: PresetConfig?): JsonObject = buildJsonObject {
    name?.let { put("name", it.trim().take(Preset.MAX_NAME)) }
    c?.let { put("config", presetConfigJson(it)) }
}

/** A list that tolerates broken rows: rows without an id are skipped, unknown fields ignored, a non-array answer is "no presets". */
fun decodePresets(text: String): List<Preset> {
    val root = ApiJson.parseToJsonElement(text)
    if (root !is JsonArray && root !is JsonObject) throw kotlinx.serialization.SerializationException("not a preset list")
    val arr = root as? JsonArray ?: ((root as? JsonObject)?.get("items") as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { el ->
        runCatching { ApiJson.decodeFromJsonElement(Preset.serializer(), el) }.getOrNull()?.takeIf { it.id.isNotBlank() }
    }
}

private fun json(body: JsonObject) = body.toString().toRequestBody("application/json".toMediaType())

suspend fun ApiClient.listPresets(): List<Preset> = call(get("/presets")) { decodePresets(it) }

suspend fun ApiClient.createPreset(name: String, c: PresetConfig): Preset =
    call(post("/presets", json(presetCreateJson(name, c)))) { ApiJson.decodeFromString(it) }

suspend fun ApiClient.updatePreset(id: String, name: String?, c: PresetConfig?): Preset =
    call(Request.Builder().url(url("/presets/$id")).patch(json(presetPatchJson(name, c))).build()) { ApiJson.decodeFromString(it) }

suspend fun ApiClient.deletePreset(id: String) = call(Request.Builder().url(url("/presets/$id")).delete().build()) { }

/** Marks the preset as just used (sets lastUsedAt) and returns it. */
suspend fun ApiClient.usePreset(id: String): Preset = call(post("/presets/$id/use")) { ApiJson.decodeFromString(it) }
