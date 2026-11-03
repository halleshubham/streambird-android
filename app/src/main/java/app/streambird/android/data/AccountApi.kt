package app.streambird.android.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The paid plan that autopay would be cancelled for. [nextRenewal] is an ISO-8601 instant or null. */
data class ActiveSubscription(val plan: String = "", val nextRenewal: String? = null)

/**
 * GET /api/account/delete/preview: what deleting the signed-in account would remove. Every field is optional on the wire
 * (a missing or wrongly typed one falls back to the harmless default), see [decodeDeletionPreview].
 */
data class DeletionPreview(
    val isOnlyAdmin: Boolean = false,
    val memberCount: Int = 1,
    val activeSubscription: ActiveSubscription? = null,
    val liveStream: Boolean = false,
    val connectionsCount: Int = 0,
    val assetsCount: Int = 0,
) {
    /** The whole company account goes away together with other members' access: the user must tick the confirmation. */
    val companyDeletionNeedsConfirm get() = isOnlyAdmin && memberCount > 1
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive
private fun JsonElement?.bool(): Boolean? = prim()?.takeIf { !it.isString }?.booleanOrNull
private fun JsonElement?.int(): Int? = prim()?.takeIf { !it.isString }?.intOrNull
private fun JsonElement?.text(): String? = prim()?.takeIf { it !is JsonNull }?.contentOrNull

/** Tolerant parser: unknown keys ignored, nulls and wrong types become defaults, counts never negative. Throws SerializationException when the body is not a JSON object. */
fun decodeDeletionPreview(text: String): DeletionPreview {
    val root = ApiJson.parseToJsonElement(text) as? JsonObject ?: throw kotlinx.serialization.SerializationException("not an object")
    val sub = (root["activeSubscription"] as? JsonObject)?.let { ActiveSubscription(plan = it["plan"].text().orEmpty(), nextRenewal = it["nextRenewal"].text()) }
    return DeletionPreview(
        isOnlyAdmin = root["isOnlyAdmin"].bool() ?: false,
        memberCount = (root["memberCount"].int() ?: 1).coerceAtLeast(1),
        activeSubscription = sub,
        liveStream = root["liveStream"].bool() ?: false,
        connectionsCount = (root["connectionsCount"].int() ?: 0).coerceAtLeast(0),
        assetsCount = (root["assetsCount"].int() ?: 0).coerceAtLeast(0),
    )
}

/** DELETE /api/account body: exactly `code` and, only when the user ticked the company confirmation, `confirmCompanyDeletion: true`. */
fun deleteAccountJson(code: String, confirmCompanyDeletion: Boolean): JsonObject = buildJsonObject {
    put("code", code.trim())
    if (confirmCompanyDeletion) put("confirmCompanyDeletion", true)
}

suspend fun ApiClient.deletionPreview(): DeletionPreview = call(get("/account/delete/preview")) { decodeDeletionPreview(it) }

/** Emails a 6-digit confirmation code to the signed-in user (204). */
suspend fun ApiClient.requestDeletionCode() = call(post("/account/delete/request")) { }

/** Deletes the account (204). 409 = the company confirmation is required but missing; 400/401 = wrong or expired code; 403 = not allowed. */
suspend fun ApiClient.deleteAccount(code: String, confirmCompanyDeletion: Boolean) {
    val body = deleteAccountJson(code, confirmCompanyDeletion).toString().toRequestBody("application/json".toMediaType())
    call(Request.Builder().url(url("/account")).delete(body).build()) { }
}

/** https://streambird.app/delete-account for the production build (the base URL baked into the app). */
fun deleteAccountWebUrl(base: String = app.streambird.android.BuildConfig.DEFAULT_BASE_URL) = base.trimEnd('/') + "/delete-account"
