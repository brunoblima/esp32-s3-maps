package com.maisonsmd.catdrive.utils

internal fun isNotificationListenerEnabled(enabledComponents: String?, component: String): Boolean =
    enabledComponents
        ?.split(':')
        ?.any { it == component }
        ?: false
