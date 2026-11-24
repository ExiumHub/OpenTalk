package com.opentalk.android.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun isVersionNewer_matchesExpectedSemverLogic() {
        assertTrue(UpdateManager.isVersionNewer("v2.1.1", "2.1.0"))
        assertTrue(UpdateManager.isVersionNewer("2.2.0", "2.1.0"))
        assertTrue(UpdateManager.isVersionNewer("v3.0.0", "2.1.0"))
        assertTrue(UpdateManager.isVersionNewer("v2.1.0.1", "2.1.0"))
        assertFalse(UpdateManager.isVersionNewer("v2.1.0", "2.1.0"))
        assertFalse(UpdateManager.isVersionNewer("v2.0.9", "2.1.0"))
        assertFalse(UpdateManager.isVersionNewer("v1.9.5", "2.1.0"))
    }
}
