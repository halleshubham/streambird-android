package app.streambird.android.brand

import org.junit.Assert.assertEquals
import org.junit.Test

class KitDeleteTextTest {
    @Test fun texts() {
        assertEquals("Delete \"Main\"?", KitDeleteText.title("Main"))
        assertEquals("Deleted \"Main\".", KitDeleteText.done("Main"))
        assertEquals("Delete Main", KitDeleteText.iconDescription("Main"))
        assertEquals("Delete kit", KitDeleteText.CONFIRM)
        assertEquals("Cancel", KitDeleteText.CANCEL)
        assertEquals("Channels that use this kit go back to the account default. Your uploaded files stay in storage.", KitDeleteText.BODY)
    }
}
