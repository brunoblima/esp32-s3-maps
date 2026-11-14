package com.maisonsmd.catdrive.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.maisonsmd.catdrive.lib.GMAPS_PACKAGE
import com.maisonsmd.catdrive.lib.GMapsNotification
import com.maisonsmd.catdrive.lib.GoogleMapsNotificationDiagnostics
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.lib.NavigationNotification
import com.maisonsmd.catdrive.utils.ExecutionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

open class NavigationListener : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mNotificationParserCoroutine: Job? = null
    private var mDiagnosticCoroutine: Job? = null
    private var mLastNotification: StatusBarNotification? = null
    private var mNotificationVersion = 0L
    private var mDiagnosticVersion = 0L

    private var mCurrentNotification: NavigationNotification? = null
    private var mEnabled = false

    protected var enabled: Boolean
        get() = mEnabled
        set(value) {
            if (value == mEnabled)
                return
            if (value.also { mEnabled = it })
                checkActiveNotifications()
            else {
                mNotificationParserCoroutine?.cancel()
                mNotificationParserCoroutine = null
                mDiagnosticCoroutine?.cancel()
                mDiagnosticCoroutine = null
                mDiagnosticVersion++
                mLastNotification = null
                mNotificationVersion++
                mCurrentNotification = null
            }
        }

    val lastNavigationData: NavigationData? get() = mCurrentNotification?.navigationData

    override fun onListenerConnected() {
        super.onListenerConnected()
        ExecutionLog.record("MAPS", "Notification listener connected")
        checkActiveNotifications()
    }

    override fun onCreate() {
        super.onCreate()
        ExecutionLog.initialize(applicationContext)
        ExecutionLog.record("MAPS", "Notification listener service created")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun checkActiveNotifications() {
        try {
            Timber.d("Checking for active Navigation notifications")
            this.activeNotifications.forEach { statusBarNotification ->
                // Timber.v(statusBarNotification.toString())
                onNotificationPosted(
                    statusBarNotification
                )
            }
        } catch (e: Throwable) {
            Timber.e("Failed to check for active notifications: $e")
        }
    }

    private fun isGoogleMapsNotification(sbn: StatusBarNotification?): Boolean {
        // Timber.v("enabled ${mEnabled}, isOngoing: ${sbn!!.isOngoing}, id: ${sbn.id}")
        if (!enabled || sbn == null)
            return false

        if (!sbn.isOngoing || GMAPS_PACKAGE !in sbn.packageName)
            return false

        return (sbn.id == 1)
    }

    protected open fun onNavigationNotificationAdded(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
    }

    protected open fun onGoogleMapsNotificationDiagnostic(report: String) {
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Timber.v("onNotificationPosted ${sbn?.packageName}")

        if (enabled && sbn?.packageName == GMAPS_PACKAGE) {
            ExecutionLog.record(
                "MAPS",
                "Notification posted: id=${sbn.id}, ongoing=${sbn.isOngoing}, category=${sbn.notification.category}"
            )
            val version = ++mDiagnosticVersion
            mDiagnosticCoroutine?.cancel()
            mDiagnosticCoroutine = serviceScope.launch {
                val report = try {
                    withContext(Dispatchers.Default) {
                        GoogleMapsNotificationDiagnostics.createReport(applicationContext, sbn)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.e(error, "Failed to inspect Google Maps notification")
                    "Diagnostic collection failed: ${error.javaClass.simpleName}: ${error.message}"
                }
                if (enabled && version == mDiagnosticVersion) {
                    ExecutionLog.record("MAPS_RAW", report)
                    onGoogleMapsNotificationDiagnostic(report)
                }
            }
        }

        if (isGoogleMapsNotification(sbn))
            handleGoogleNotification(sbn!!)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Timber.v("onNotificationRemoved ${sbn?.packageName}")

        if (isGoogleMapsNotification(sbn)) {
            mNotificationParserCoroutine?.cancel()
            mNotificationParserCoroutine = null
            mLastNotification = null
            mNotificationVersion++

            onNavigationNotificationRemoved(
                if (mCurrentNotification != null) mCurrentNotification!!
                else NavigationNotification(applicationContext, sbn!!)
            )

            mCurrentNotification = null
            ExecutionLog.record("MAPS", "Navigation notification removed: id=${sbn?.id}")
        }
    }

    private fun handleGoogleNotification(statusBarNotification: StatusBarNotification) {
        mLastNotification = statusBarNotification
        mNotificationVersion++
        if (mNotificationParserCoroutine?.isActive == true)
            return

        mNotificationParserCoroutine = serviceScope.launch {
            var processedVersion = -1L
            while (isActive && enabled && processedVersion != mNotificationVersion) {
                val version = mNotificationVersion
                val notification = mLastNotification ?: break

                try {
                    val mapNotification = withContext(Dispatchers.Default) {
                        GMapsNotification(applicationContext, notification)
                    }
                    val lastNotification = mCurrentNotification
                    val updated = lastNotification == null ||
                            lastNotification.navigationData != mapNotification.navigationData

                    val data = mapNotification.navigationData
                    ExecutionLog.record(
                        "MAPS_PARSED",
                        "updated=$updated; road=${data.nextDirection.nextRoad}; " +
                                "description=${data.nextDirection.nextRoadAdditionalInfo}; " +
                                "distanceToTurn=${data.nextDirection.distance}; eta=${data.eta.eta}; " +
                                "timeRemaining=${data.eta.ete}; totalDistance=${data.eta.distance}; " +
                                "icon=${data.actionIcon.bitmap?.let { "${it.width}x${it.height}" } ?: "none"}"
                    )

                    if (lastNotification == null) {
                        onNavigationNotificationAdded(mapNotification)
                    }

                    if (updated) {
                        mCurrentNotification = mapNotification
                        onNavigationNotificationUpdated(mapNotification)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.e(error, "Got an error while parsing Google Maps notification")
                    ExecutionLog.record(
                        "MAPS",
                        "Notification parsing failed: ${error.javaClass.simpleName}: ${error.message}"
                    )
                }

                processedVersion = version
            }
        }
    }

}
