package app.streambird.android

import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Play release numbering: versionCode must stay above every earlier debug build (18) and the name must be x.y.z. */
class ReleaseVersionTest {
    @Test
    fun versionCodeIsAboveEarlierDebugBuilds() {
        assertTrue("versionCode ${BuildConfig.VERSION_CODE}", BuildConfig.VERSION_CODE >= 100)
    }

    @Test
    fun versionNameIsSemver() {
        assertTrue(BuildConfig.VERSION_NAME, Regex("""\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?""").matches(BuildConfig.VERSION_NAME))
    }
}
