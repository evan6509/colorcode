package dev.colorcode.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatesTest {
    @Test fun comparesVersionComponentsNumerically() {
        assertTrue(ReleaseUpdates.isNewer("v1.10.0", "1.9.9"))
        assertTrue(ReleaseUpdates.isNewer("v2.0.0", "1.99.99"))
        assertFalse(ReleaseUpdates.isNewer("v1.2.3", "1.2.3"))
        assertFalse(ReleaseUpdates.isNewer("v1.2.2", "1.2.3"))
    }

    @Test fun rejectsMalformedAndOverflowingVersions() {
        assertFalse(ReleaseUpdates.isNewer("v01.2.3", "1.2.3"))
        assertFalse(ReleaseUpdates.isNewer("v999999999999999999999.2.3", "1.2.3"))
        assertFalse(ReleaseUpdates.isNewer("v1.2.3-beta", "1.2.2"))
        assertFalse(ReleaseUpdates.isNewer("v1.2.3", "unknown"))
    }
}
