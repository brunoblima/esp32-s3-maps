package com.maisonsmd.catdrive.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.util.Size
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.maisonsmd.catdrive.MainActivity
import com.maisonsmd.catdrive.R
import com.maisonsmd.catdrive.SHARED_PREFERENCES_FILE
import com.maisonsmd.catdrive.lib.BitmapHelper
import com.maisonsmd.catdrive.lib.BleCharacteristics
import com.maisonsmd.catdrive.lib.BlePayloadEncoder
import com.maisonsmd.catdrive.lib.BleWriteDispatcher
import com.maisonsmd.catdrive.lib.BleWriteQueue
import com.maisonsmd.catdrive.lib.BleWriteQueue.QueueItem
import com.maisonsmd.catdrive.lib.Intents
import com.maisonsmd.catdrive.lib.NavigationData
import com.maisonsmd.catdrive.utils.ExecutionLog
import com.maisonsmd.catdrive.utils.PermissionCheck
import timber.log.Timber
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.math.ceil


@SuppressLint("MissingPermission")
class BleService : Service(), LocationListener {
    companion object {
        private const val NOTIFICATION_ID = 1201
    }

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): BleService = this@BleService
    }


    private lateinit var mAdapter: BluetoothAdapter
    private var mNotificationBuilder: Notification.Builder? = null
    private var mRunInBackground: Boolean = false
    private var mPingTimer: Timer? = null
    private var mReconnectTimer: Timer? = null
    private var mFirstPing: Boolean = true
    private var mConnectionState = BluetoothProfile.STATE_DISCONNECTED
    private var mDevice: BluetoothDevice? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private val mBinder = LocalBinder()
    private var mLastNavigationData: NavigationData? = null
    private val mWriteDispatcher = BleWriteDispatcher(::sendQueuedWrite)
    private var mIconMap: MutableMap<String, ByteArray> = mutableMapOf()

    private val navigationReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            mLastNavigationData = intent.getParcelableExtra("navigation_data") as NavigationData?
            sendToDevice(mLastNavigationData)
        }
    }

    val connectedDevice: BluetoothDevice?
        get() = mDevice

    var runInBackground
        get() = mRunInBackground
        private set(value) {
            if (mRunInBackground == value)
                return
            mRunInBackground = value
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.BACKGROUND_SERVICE_STATUS).apply {
                    putExtra("service", this::class.java.simpleName)
                    putExtra("run_in_background", value)
                }
            )
        }

    inner class ReconnectTask : TimerTask() {
        override fun run() {
            Timber.d("reconnect timer elapsed")
            if (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                Timber.w("Why this timer is still running?")
//                stopReconnectTimer()
                return
            }
            if (!PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                Timber.w("No BT permissions, stop reconnect timer!")
                stopReconnectTimer()
                return
            }
            if (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                stopReconnectTimer()
                return
            }

            Timber.d("Trying to connect to last device...")
            if (PermissionCheck.isBluetoothEnabled(applicationContext))
                connectToLastDevice()
        }
    }

    inner class PingTask : TimerTask() {
        override fun run() {
            Timber.d("Ping timer elapsed")
            if (mConnectionState != BluetoothProfile.STATE_CONNECTED) {
                Timber.w("This timer should not be running!!")
//                stopPingTimer()
                return
            }
            if (mFirstPing) {
                // resend prefs just in case the device did not received the prefs at first connection
                sendPreferencesToDevice()
                mFirstPing = false
            } else if (mLastNavigationData != null) {
                sendToDevice(mLastNavigationData)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bind by activity
        if (intent?.action == Intents.BIND_LOCAL_SERVICE) {
            return mBinder
        }
        // Bind by OS
        return null
    }

    override fun onCreate() {
        ExecutionLog.initialize(applicationContext)
        ExecutionLog.record("BLE", "BLE service created")
        mAdapter =
            (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("onStartCommand: $intent")

        if (intent?.action == Intents.ENABLE_SERVICES) {
            ExecutionLog.record("BLE", "Background services enabled")
            runInBackground = true
            startForeground(NOTIFICATION_ID, buildForegroundNotification())

            LocalBroadcastManager.getInstance(this)
                .registerReceiver(navigationReceiver, IntentFilter(Intents.NAVIGATION_UPDATE))

            subscribeToLocationUpdates()

            if (mConnectionState == BluetoothProfile.STATE_DISCONNECTED)
                startReconnectTimer()
        }

        if (intent?.action == Intents.DISABLE_SERVICES) {
            ExecutionLog.record("BLE", "Background services disabled")
            runInBackground = false
            disconnect()
            unsubscribeFromLocationUpdates()

            LocalBroadcastManager.getInstance(this).unregisterReceiver(navigationReceiver)
            mNotificationBuilder = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopPingTimer()
            stopReconnectTimer()
        }

        if (intent?.action == Intents.CONNECT_DEVICE) {
            if (PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                val device = intent.getParcelableExtra<BluetoothDevice>("device")!!
                ExecutionLog.record(
                    "BLE",
                    "Connect requested: device=${device.name ?: "(unknown)"}"
                )
                connect(device)
            } else {
                ExecutionLog.record("BLE", "Connect request rejected: Bluetooth permissions missing")
            }

            if (!runInBackground)
                return START_NOT_STICKY
        }

        if (intent?.action == Intents.DISCONNECT_DEVICE) {
            ExecutionLog.record("BLE", "Disconnect requested")
            if (PermissionCheck.checkBluetoothPermissions(applicationContext)) {
                disconnect()
            }

            if (!runInBackground)
                return START_NOT_STICKY
        }

        return START_STICKY
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null || gatt !== mBluetoothGatt) {
                Timber.w("Ignoring connection callback from an old GATT connection")
                return
            }

            Timber.d("onConnectionStateChange: $status, $newState")
            ExecutionLog.record(
                "BLE",
                "Connection callback: status=$status, state=${connectionStateName(newState)}"
            )

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                // Attempts to discover services after successful connection.
                Timber.i("onConnectionStateChange: Connected! ${mDevice}, ${mBluetoothGatt}")
                mConnectionState = BluetoothProfile.STATE_CONNECTING
                gatt.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                // broadcastUpdate(ACTION_GATT_DISCONNECTED)
                Timber.i("onConnectionStateChange: Disconnected!")
                if (mConnectionState != BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect()
                }

                updateNotificationText("No device connected")
                startReconnectTimer()
                stopPingTimer()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (gatt == null || gatt !== mBluetoothGatt) {
                Timber.w("Ignoring MTU callback from an old GATT connection")
                return
            }
            ExecutionLog.record("BLE", "MTU changed: mtu=$mtu, status=$status")
            gatt.discoverServices()
            mConnectionState = BluetoothProfile.STATE_CONNECTING
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || gatt !== mBluetoothGatt) {
                Timber.w("Ignoring service discovery callback from an old GATT connection")
                return
            }
            ExecutionLog.record("BLE", "Service discovery callback: status=$status")

            // Discovery finishes after onServicesDiscovered is called
            if (mConnectionState == BluetoothProfile.STATE_DISCONNECTED)
                return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("onServicesDiscovered: Success!")

                mConnectionState = BluetoothProfile.STATE_CONNECTED

                mWriteDispatcher.reset()
                mIconMap.clear()

                updateNotificationText("Connected to ${mDevice!!.name}")
                stopReconnectTimer()
                startPingTimer()
                sendPreferencesToDevice()
                ExecutionLog.record(
                    "BLE",
                    "GATT ready: device=${mDevice?.name ?: "(unknown)"}"
                )

                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                    Intent(Intents.CONNECTION_UPDATE).apply {
                        putExtra("status", "connected")
                        putExtra("device_name", mDevice!!.name)
                        putExtra("device_address", mDevice!!.address)
                    }
                )
            } else {
                Timber.w("onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            if (gatt == null || gatt !== mBluetoothGatt) {
                Timber.w("Ignoring characteristic write callback from an old connection")
                return
            }

            Timber.d("onCharacteristicWrite: $status (0 means success)")
            ExecutionLog.record(
                "BLE",
                "Write callback: characteristic=${characteristic?.uuid}, status=$status"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("BLE write failed for ${characteristic?.uuid}: $status")
            }

            mWriteDispatcher.onWriteCompleted()
        }
    }

    private fun write(item: QueueItem) {
        Timber.d("writing ${item.uuid} (${item.data.size} bytes)")
        if (mConnectionState != BluetoothProfile.STATE_CONNECTED || mBluetoothGatt == null) {
            Timber.w("Cannot write ${item.uuid}: BLE is not connected")
            ExecutionLog.record(
                "BLE",
                "Write skipped: characteristic=${item.uuid}, reason=not connected"
            )
            return
        }

        ExecutionLog.record(
            "BLE",
            "Write enqueued: characteristic=${item.uuid}, bytes=${item.data.size}, alreadySending=${mWriteDispatcher.isSending}"
        )
        if (mWriteDispatcher.isSending) {
            Timber.d("BLE busy; queueing ${item.uuid}")
        }
        mWriteDispatcher.enqueue(item)
    }

    private fun sendQueuedWrite(item: QueueItem): Boolean {
        val gatt = mBluetoothGatt
        if (mConnectionState != BluetoothProfile.STATE_CONNECTED || gatt == null) {
            Timber.w("Cannot write ${item.uuid}: GATT connection is unavailable")
            ExecutionLog.record(
                "BLE",
                "Write could not start: characteristic=${item.uuid}, reason=GATT unavailable"
            )
            return false
        }

        val characteristic = findCharacteristic(item.uuid)
        if (characteristic == null) {
            Timber.e("No characteristic found for ${item.uuid}")
            ExecutionLog.record(
                "BLE",
                "Write could not start: characteristic=${item.uuid}, reason=not found"
            )
            return false
        }

        characteristic.value = item.data
        if (gatt.writeCharacteristic(characteristic)) {
            ExecutionLog.record(
                "BLE",
                "Write started: characteristic=${item.uuid}, bytes=${item.data.size}"
            )
            return true
        }

        Timber.e("Failed to start BLE write for ${item.uuid}")
        ExecutionLog.record(
            "BLE",
            "Write could not start: characteristic=${item.uuid}, reason=GATT rejected request"
        )
        return false
    }

    private fun findCharacteristic(uuid: String): BluetoothGattCharacteristic? {
        var characteristic: BluetoothGattCharacteristic? = null
        val service = mBluetoothGatt?.getService(UUID.fromString(BleCharacteristics.SERVICE_UUID))
        service?.characteristics?.forEach { ch ->
            if (ch.uuid.toString() == uuid)
                characteristic = ch
        }
        return characteristic
    }

    fun connectToLastDevice() {
        val sp = applicationContext.getSharedPreferences(
            SHARED_PREFERENCES_FILE,
            Context.MODE_PRIVATE
        )
        val name = sp.getString("last_device_name", null)
        val address = sp.getString("last_device_address", null)

        if (name != null && address != null) {
            Timber.i("trying connecting to $name address $address")
            ExecutionLog.record("BLE", "Reconnect attempt: device=$name")
            mAdapter.getRemoteDevice(address)?.let {
                connect(it)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        Timber.i("Connecting to device $device")
        ExecutionLog.record(
            "BLE",
            "Opening GATT connection: device=${device.name ?: "(unknown)"}"
        )
        mDevice = device
        mBluetoothGatt =
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE).also {
                it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }
    }

    fun disconnect() {
        stopPingTimer()
        stopReconnectTimer()

        val gatt = mBluetoothGatt
        val hadConnection = mConnectionState != BluetoothProfile.STATE_DISCONNECTED ||
                gatt != null || mDevice != null
        ExecutionLog.record(
            "BLE",
            "Disconnect cleanup: previousState=${connectionStateName(mConnectionState)}, hadGatt=${gatt != null}"
        )

        mWriteDispatcher.reset()
        mBluetoothGatt = null
        mConnectionState = BluetoothProfile.STATE_DISCONNECTED
        mDevice = null

        if (gatt != null) {
            Timber.i("Disconnecting from device")
            gatt.disconnect()
            gatt.close()
        }

        if (hadConnection) {
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                Intent(Intents.CONNECTION_UPDATE).apply {
                    putExtra("status", "disconnected")
                }
            )
        }
    }

    fun sendPreferencesToDevice() {
        val sp =
            applicationContext.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)

        write(
            QueueItem(
                BleCharacteristics.CHA_SETTINGS,
                BlePayloadEncoder.encodeSettings(
                    sp.getBoolean("display_light_theme", true),
                    sp.getInt("display_brightness", 50),
                    sp.getInt("speed_limit", 50)
                ).toByteArray(Charsets.UTF_8)
            )
        )
    }

    fun sendToDevice(data: NavigationData?) {
        val bitmap = data?.actionIcon?.bitmap

        var compressed: ByteArray? = bitmap?.let {
            val helper = BitmapHelper()
            helper.toBlackAndWhiteBuffer(
                helper.compressBitmap(
                    bitmap,
                    Size(64, 62)
                )
            )
        }

        var iconHash = ""
        if (compressed != null) {
            iconHash = md5(compressed)
            iconHash = iconHash.substring(iconHash.length - 10, iconHash.length)
        }

        val navigationPayload = BlePayloadEncoder.encodeNavigation(
            data?.nextDirection?.nextRoad,
            data?.nextDirection?.nextRoadAdditionalInfo,
            data?.nextDirection?.distance,
            data?.eta?.distance,
            data?.eta?.eta,
            data?.eta?.ete,
            iconHash
        ).toByteArray(Charsets.UTF_8)
        ExecutionLog.record(
            "BLE",
            "Navigation update forwarded: distance=${data?.nextDirection?.distance}; " +
                    "road=${data?.nextDirection?.nextRoad}; payloadBytes=${navigationPayload.size}; " +
                    "iconHash=${iconHash.ifEmpty { "none" }}"
        )
        write(
            QueueItem(
                BleCharacteristics.CHA_NAV,
                navigationPayload
            )
        )

        // Only send once
        if (iconHash != "" && compressed != null && !mIconMap.containsKey(iconHash)) {
            if (mConnectionState == BluetoothProfile.STATE_CONNECTED) {
                // Store the bitmap for later use
                mIconMap[iconHash] = compressed

                compressed.let {
                    val withIconHash = ("$iconHash;").toByteArray()
                    Timber.w("it size: ${it.size}")
                    write(QueueItem(BleCharacteristics.CHA_NAV_TBT_ICON, withIconHash + it, true))
                }
            }
        } else {
            Timber.i("Icon $iconHash already sent before")
        }
    }

    private fun md5(s: ByteArray): String {
        return try {
            // Create MD5 Hash
            val digest = MessageDigest.getInstance("MD5")
            digest.update(s)
            val messageDigest = digest.digest()

            // Create Hex String
            val hexString = StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) {
                    h = "0$h"
                }
                hexString.append(h)
            }
            hexString.toString()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            ""
        }
    }


    private fun subscribeToLocationUpdates() {
        if (PermissionCheck.checkLocationAccessPermission(applicationContext)) {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
        }
    }

    private fun unsubscribeFromLocationUpdates() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(this)
    }

    private fun startPingTimer() {
        stopPingTimer()
        mPingTimer = Timer()
        mFirstPing = true
        mPingTimer!!.schedule(PingTask(), 1000, 25000)
    }

    private fun stopPingTimer() {
        if (mPingTimer != null) {
            mPingTimer!!.cancel()
            mPingTimer!!.purge()
            mPingTimer = null
        }
    }

    private fun startReconnectTimer() {
        stopReconnectTimer()
        mReconnectTimer = Timer()
        mReconnectTimer!!.schedule(ReconnectTask(), 15000, 15000)
    }

    private fun stopReconnectTimer() {
        if (mReconnectTimer != null) {
            mReconnectTimer!!.cancel()
            mReconnectTimer!!.purge()
            mReconnectTimer = null
        }
    }

    override fun onLocationChanged(location: Location) {
        val speed = ceil(location.speed * 3600f / 1000f).toInt()
        Timber.d("Speed: $speed")
        ExecutionLog.record("GPS", "Speed update: $speed km/h")

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(Intents.GPS_UPDATE).apply {
                putExtra("speed", speed)
            }
        )

        write(QueueItem(BleCharacteristics.CHA_GPS_SPEED, speed.toString().toByteArray()))
    }

    private fun updateNotificationText(text: String) {
        if (mNotificationBuilder == null)
            return
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotificationBuilder!!.setContentText(text)
        notificationManager.notify(NOTIFICATION_ID, mNotificationBuilder!!.build())
    }

    private fun connectionStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val channel =
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
        return channelId
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = createNotificationChannel(
            this::class.java.simpleName,
            this::class.java.simpleName
        )
        val builder: Notification.Builder = Notification.Builder(this, channelId)
        val intent = Intent(this, MainActivity::class.java)
        intent.action = System.currentTimeMillis().toString()
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mNotificationBuilder = builder.setContentTitle("CatDrive")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.catface)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return mNotificationBuilder!!.build()
    }

}