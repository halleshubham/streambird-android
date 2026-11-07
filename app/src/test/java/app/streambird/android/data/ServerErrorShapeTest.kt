package app.streambird.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error bodies the REAL server sends (captured from the Nest app with its AllExceptionsFilter, src/common/filters/http-exception.filter.ts):
 * the filter wraps Nest's own response, so `message` is an OBJECT one level down, and a validation failure carries an ARRAY inside it.
 * The app used to show "Request failed (400)" for every validation failure because it only read a string at that level.
 */
class ServerErrorShapeTest {
    @Test fun `validation failure with an array one level down is shown joined`() {
        val body = """{"statusCode":400,"message":{"message":["config.each value in destinationIds must be a UUID","name must be shorter than or equal to 80 characters"],"error":"Bad Request","statusCode":400},"timestamp":"2026-11-07T18:31:35.366Z"}"""
        assertEquals("config.each value in destinationIds must be a UUID, name must be shorter than or equal to 80 characters", ApiClient.errorMessage(400, body))
    }

    @Test fun `string messages one level down`() {
        assertEquals("You can keep up to 20 presets. Delete one to save another.",
            ApiClient.errorMessage(409, """{"statusCode":409,"message":{"message":"You can keep up to 20 presets. Delete one to save another.","error":"Conflict","statusCode":409},"timestamp":"x"}"""))
        assertEquals("One or more destinationIds do not belong to this account",
            ApiClient.errorMessage(422, """{"statusCode":422,"message":{"message":"One or more destinationIds do not belong to this account","error":"Unprocessable Entity","statusCode":422},"timestamp":"x"}"""))
        assertEquals("Missing x-api-key header or session cookie",
            ApiClient.errorMessage(401, """{"statusCode":401,"message":{"message":"Missing x-api-key header or session cookie","error":"Unauthorized","statusCode":401},"timestamp":"x"}"""))
        assertEquals("Cannot GET /api/presetsxyz", ApiClient.errorMessage(404, """{"statusCode":404,"message":{"message":"Cannot GET /api/presetsxyz","error":"Not Found","statusCode":404},"timestamp":"x"}"""))
    }

    @Test fun `plain string, plain array and the unexpected shapes still work`() {
        assertEquals("Internal server error", ApiClient.errorMessage(500, """{"statusCode":500,"message":"Internal server error","timestamp":"x"}"""))
        assertEquals("a, b", ApiClient.errorMessage(400, """{"message":["a","b"]}"""))
        assertEquals("Request failed (500)", ApiClient.errorMessage(500, """{"statusCode":500,"message":null}"""))
        assertEquals("Request failed (502)", ApiClient.errorMessage(502, "<html>bad gateway</html>"))
        assertEquals("Request failed (400)", ApiClient.errorMessage(400, """{"message":{"message":[]}}"""))
        assertEquals("Request failed (400)", ApiClient.errorMessage(400, """{"message":{"message":{"message":{"message":{"message":"too deep"}}}}}"""))
    }

    @Test fun `plan steering in a nested message is still neutralised`() {
        assertEquals(ApiClient.NOT_IN_PLAN, ApiClient.errorMessage(403, """{"statusCode":403,"message":{"message":"Upgrade to a paid plan to use brand kits","error":"Forbidden","statusCode":403}}"""))
    }

    @Test fun `ApiException keeps a body snippet for the diagnostics only`() {
        val e = ApiException(400, "x", "y".repeat(500).take(300))
        assertEquals(300, e.body.length)
        assertTrue(ApiException(500, "x").body.isEmpty())
    }
}
