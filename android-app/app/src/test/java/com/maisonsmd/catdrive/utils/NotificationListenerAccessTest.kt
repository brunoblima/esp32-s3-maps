package com.maisonsmd.catdrive.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerAccessTest {
    @Test
    fun detectsListenerAmongMultipleEnabledComponents() {
        assertTrue(
            isNotificationListenerEnabled(
                "other.app/.Listener:com.maisonsmd.catdrive/com.maisonsmd.catdrive.GoogleMapNotificationListener",
                "com.maisonsmd.catdrive/com.maisonsmd.catdrive.GoogleMapNotificationListener"
            )
        )
    }

    @Test
    fun doesNotMatchPartialComponentNames() {
        assertFalse(
            isNotificationListenerEnabled(
                "com.maisonsmd.catdrive/com.maisonsmd.catdrive.GoogleMapNotificationListenerExtra",
                "com.maisonsmd.catdrive/com.maisonsmd.catdrive.GoogleMapNotificationListener"
            )
        )
    }

    @Test
    fun missingEnabledComponentsMeansAccessIsDisabled() {
        assertFalse(isNotificationListenerEnabled(null, "com.example/.Listener"))
    }
}
