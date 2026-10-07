package com.arthur.bgollee

import android.app.*
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.math.abs

class BleService : Service() {

    private lateinit var notificationManager: NotificationManager
    private var gatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private lateinit var prefs: SharedPreferences

    private var isConnecting = false
    private var isConnected = false
    private var servicesReady = false

    private var pendingBg: String? = null

    private var lastSent: String? = null
    private var isInErrorState = false
    private var weekdayHeader: ByteArray? = null
    private var isAwaitingWeekdayHeader = false
    private var incomingFrameBytes = ByteArray(0)
    private var writeInFlight = false
    private var writeRetryCount = 0
    private val writeQueue = ArrayDeque<ByteArray>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val weekdayHeaderTimeout = Runnable {
        if (isAwaitingWeekdayHeader) {
            isAwaitingWeekdayHeader = false
            log("World Time header read timed out; skipping upper-field update")
            trySend()
        }
    }

    companion object {
        const val CHANNEL_ID = "ble_service_channel"
        private const val TIMEOUT_MS = 15 * 60 * 1000L
        private const val WRITE_CHUNK_SIZE = 20
        private const val WRITE_RETRY_MS = 100L
        private const val MAX_WRITE_RETRIES = 40
        private const val WEEKDAY_TARGET = 0x34
        private const val WEEKDAY_READ_TARGET = 0x35
        private const val WEEKDAY_REPLY_TARGET = 0x55
        private const val RESPONSE_TARGET_OFFSET = 0x20
        private const val WEEKDAY_HEADER_SIZE = 4
        private const val WEEKDAY_TABLE_SIZE = 14
        private const val WEEKDAY_HEADER_TIMEOUT_MS = 5_000L
        private val DEFAULT_WEEKDAYS = "MOTUWETHFRSASU".toByteArray(Charsets.US_ASCII)

        val SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        val CHAR_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        private val NOTIFY_CHAR_UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // ========================
    // LIFECYCLE
    // ========================

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("data", MODE_PRIVATE)
        deviceAddress = prefs.getString("device_address", null)

        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        startForeground(1, createNotification("🔄 Initialisation..."))

        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        log("🚀 Service créé")

        Handler(Looper.getMainLooper()).post { connect() }

        startTimeoutWatcher()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(btReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val bg = intent?.getStringExtra("bg")
        val delta = intent?.getIntExtra("delta", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }

        intent?.getStringExtra("device_address")?.let {
            deviceAddress = it
            prefs.edit().putString("device_address", it).apply()
        }

        if (bg != null) {
            handleBg(bg, delta)
        }

        if (gatt == null && !isConnecting) {
            connect()
        }

        return START_STICKY
    }

    // ========================
    // GESTION BG
    // ========================

    private fun handleBg(bg: String, delta: Int?) {

        val now = System.currentTimeMillis()

        val formatted = formatBg(bg, delta)

        pendingBg = formatted
        isInErrorState = false

        prefs.edit()
            .putString("last_watch", formatted.trim())
            .putLong("last_time", now)
            .apply()

        sendBroadcast(Intent("BG_UPDATED").setPackage(packageName))

        trySend()
    }

    private fun formatBg(bg: String?, delta: Int?): String {

        if (bg.isNullOrBlank()) return "Err   "

        val clean = bg.replace(",", ".")

        val isMmol = clean.contains(".")

        val valueStr = if (isMmol) {
            val mmol = clean.toFloatOrNull() ?: return "Err   "
            String.format("%.1f", mmol.coerceIn(0f, 99.9f))
        } else {
            val mgdl = clean.replace("[^0-9]".toRegex(), "")
                .toIntOrNull() ?: return "Err   "
            mgdl.coerceIn(0, 999).toString()
        }

        val deltaText = delta?.let {
            val boundedDelta = it.coerceIn(-99, 99)
            if (isMmol) {
                val magnitude = String.format(Locale.US, "%.1f", abs(boundedDelta) / 18.0)
                when {
                    boundedDelta > 0 -> "+$magnitude"
                    boundedDelta < 0 -> "-$magnitude"
                    else -> magnitude
                }
            } else {
                val magnitude = abs(boundedDelta).toString().padStart(2, '0')
                when {
                    boundedDelta > 0 -> "+$magnitude"
                    boundedDelta < 0 -> "-$magnitude"
                    else -> magnitude
                }
            }
        } ?: "--"

        return (valueStr + deltaText).take(6)
    }

    // ========================
    // TIMEOUT
    // ========================

    private fun startTimeoutWatcher() {

        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {

                val lastTime = prefs.getLong("last_time", 0L)
                val now = System.currentTimeMillis()

                if (now - lastTime > TIMEOUT_MS) {

                    if (!isInErrorState) {
                        log("⏱ Timeout → ERROR")

                        pendingBg = "Err   "
                        isInErrorState = true

                        trySend()
                    }
                }

                handler.postDelayed(this, 60_000)
            }
        }

        handler.post(runnable)
    }

    // ========================
    // BLUETOOTH
    // ========================

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {

                when (intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )) {

                    BluetoothAdapter.STATE_OFF -> {
                        log("🔴 Bluetooth OFF")
                        isConnected = false
                        servicesReady = false
                        clearPendingWrites()
                        gatt?.close()
                        gatt = null
                    }

                    BluetoothAdapter.STATE_ON -> {
                        log("🟢 Bluetooth ON → reconnexion")
                        Handler(Looper.getMainLooper()).postDelayed({
                            connect()
                        }, 1000)
                    }
                }
            }
        }
    }

    private fun connect() {

        if (isConnecting) return

        val addr = deviceAddress ?: return

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(addr)

        gatt?.close()
        gatt = null

        log("🔗 Connexion à $addr")

        isConnecting = true
        weekdayHeader = null
        lastSent = null
        incomingFrameBytes = ByteArray(0)

        gatt = device.connectGatt(
            this,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {

            isConnecting = false

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                servicesReady = false
                gatt = g

                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()

                updateNotification("🟢 Connecté")
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                servicesReady = false
                clearPendingWrites()
                weekdayHeader = null
                isAwaitingWeekdayHeader = false
                mainHandler.removeCallbacks(weekdayHeaderTimeout)

                gatt?.close()
                gatt = null

                updateNotification("🔴 Reconnexion...")

                Handler(Looper.getMainLooper()).postDelayed({
                    connect()
                }, 3000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }

            val service = g.getService(SERVICE_UUID)
            val notifyCharacteristic = service?.getCharacteristic(NOTIFY_CHAR_UUID)
            val descriptor = notifyCharacteristic?.getDescriptor(CCCD_UUID)
            if (notifyCharacteristic == null || descriptor == null) {
                log("Notify characteristic unavailable; upper-field updates disabled")
                servicesReady = true
                trySend()
                return
            }

            g.setCharacteristicNotification(notifyCharacteristic, true)
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
            }
            if (!started) {
                log("Failed to enable watch notifications; upper-field updates disabled")
                servicesReady = true
                trySend()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            servicesReady = true
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestWeekdayHeader()
            } else {
                log("Failed to enable watch notifications: $status")
                trySend()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("BLE write failed: $status")
                clearPendingWrites()
                return
            }
            writeQueue.pollFirst()
            writeInFlight = false
            writeRetryCount = 0
            pumpWriteQueue()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NOTIFY_CHAR_UUID) {
                receiveNotification(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NOTIFY_CHAR_UUID) {
                receiveNotification(value)
            }
        }
    }

    // ========================
    // ENVOI
    // ========================

    private fun trySend() {

        val bg = pendingBg ?: return

        if (!isConnected || !servicesReady) return

        if (bg != lastSent) {
            sendToWatch(bg)
            lastSent = bg
            pendingBg = null
        }

    }

    private fun sendToWatch(bg: String) {

        enqueuePacket(buildFrame(0x2f, bg.toByteArray(Charsets.US_ASCII)))
        log("📤 Envoyé → '$bg'")
    }

    private fun requestWeekdayHeader() {
        isAwaitingWeekdayHeader = true
        enqueuePacket(buildFrame(WEEKDAY_READ_TARGET, ByteArray(0)))
        mainHandler.removeCallbacks(weekdayHeaderTimeout)
        mainHandler.postDelayed(weekdayHeaderTimeout, WEEKDAY_HEADER_TIMEOUT_MS)
    }

    private fun buildFrame(target: Int, payload: ByteArray): ByteArray {
        val inner = byteArrayOf(0x02, target.toByte()) + payload
        val crc = crc16(inner)
        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    private fun enqueuePacket(packet: ByteArray) {
        packet.toList().chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            writeQueue.addLast(chunk.toByteArray())
        }
        pumpWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWriteQueue() {
        if (!isConnected || !servicesReady || writeInFlight) return

        val bytes = writeQueue.peekFirst() ?: return
        val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: return
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt?.writeCharacteristic(characteristic) == true
            }
        }

        if (started) {
            writeInFlight = true
            writeRetryCount = 0
        } else if (++writeRetryCount >= MAX_WRITE_RETRIES) {
            log("BLE write queue stalled; dropping pending packet fragments")
            clearPendingWrites()
        } else {
            mainHandler.postDelayed({ pumpWriteQueue() }, WRITE_RETRY_MS)
        }
    }

    private fun clearPendingWrites() {
        writeQueue.clear()
        writeInFlight = false
        writeRetryCount = 0
    }

    private fun receiveNotification(value: ByteArray) {
        incomingFrameBytes += value

        while (incomingFrameBytes.size >= 2) {
            if (incomingFrameBytes[0] != 0x00.toByte()) {
                incomingFrameBytes = incomingFrameBytes.copyOfRange(1, incomingFrameBytes.size)
                continue
            }

            val frameSize = (incomingFrameBytes[1].toInt() and 0xff) + 2
            if (incomingFrameBytes.size < frameSize) return

            val frame = incomingFrameBytes.copyOfRange(0, frameSize)
            incomingFrameBytes = incomingFrameBytes.copyOfRange(frameSize, incomingFrameBytes.size)
            acceptWeekdayHeader(frame)
        }
    }

    private fun acceptWeekdayHeader(frame: ByteArray) {
        if (!isAwaitingWeekdayHeader ||
            frame.size < 8 + WEEKDAY_HEADER_SIZE + WEEKDAY_TABLE_SIZE
        ) return
        if (frame[6] != 0x02.toByte()) return
        if ((frame[7].toInt() and 0xff) != WEEKDAY_READ_TARGET + RESPONSE_TARGET_OFFSET) return

        val expectedCrc = ((frame[4].toInt() and 0xff) shl 8) or (frame[5].toInt() and 0xff)
        val actualCrc = crc16(frame.copyOfRange(6, frame.size))
        if (actualCrc != expectedCrc) {
            log("Invalid CRC in World Time header response")
            return
        }

        weekdayHeader = frame.copyOfRange(8, 8 + WEEKDAY_HEADER_SIZE)
        val currentWeekdays = frame.copyOfRange(
            8 + WEEKDAY_HEADER_SIZE,
            8 + WEEKDAY_HEADER_SIZE + WEEKDAY_TABLE_SIZE
        )
        isAwaitingWeekdayHeader = false
        mainHandler.removeCallbacks(weekdayHeaderTimeout)
        if (!currentWeekdays.contentEquals(DEFAULT_WEEKDAYS)) {
            enqueuePacket(buildFrame(WEEKDAY_TARGET, weekdayHeader!! + DEFAULT_WEEKDAYS))
            log("Restoring weekday labels in the upper field")
        }
        trySend()
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF

        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)

            repeat(8) {
                crc = if ((crc and 0x8000) != 0)
                    (crc shl 1) xor 0x1021
                else crc shl 1

                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================
    // NOTIF
    // ========================

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("xDrip → Watch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE Service",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(1, createNotification(text))
    }

    private fun log(msg: String) {
        Log.d("BleService", msg)
    }
}