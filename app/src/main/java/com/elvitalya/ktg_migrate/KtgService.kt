package com.elvitalya.ktg_migrate

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.*
import com.elvitalya.ktg_migrate.java.EState
import com.elvitalya.ktg_migrate.java.EState.*
import com.elvitalya.ktg_migrate.java.PSChartData
import com.elvitalya.ktg_migrate.java.PSDecoder
import com.luckcome.lmtpdecorder.LMTPDecoderListener
import com.luckcome.lmtpdecorder.data.FhrData
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.floor


@SuppressLint("MissingPermission")
class KtgService : Service() {
    private lateinit var mBluetoothAdapter: BluetoothAdapter
    private lateinit var mBtDevice: BluetoothDevice
    private var mBluetoothGatt: BluetoothGatt? = null
    lateinit var ktgCallback: KtgCallback
    private var mLMTPDecoder = PSDecoder()
    private var mLMTPDListener = LMTPDListener()
    private lateinit var mBinder: KtgBinder
    private lateinit var mHandler: Handler
    private var mSocket: BluetoothSocket? = null
    private val mReadThread = ReadThread()
    private lateinit var mRecordFile: File
    var recordLastStartTime: Long = 0
        private set
    private var mSerialNumber: String = ""
    private var mDeviceModel: String = ""
    private var mManufacturer: String = ""
    private var mCharQueue: LinkedList<BluetoothGattCharacteristic> = LinkedList()
    private var isCharsReceived = false
    private var mConnectionTimeoutHandler: Handler = Handler(Looper.getMainLooper())
    private lateinit var mConnectionTimeoutRunnable: Runnable
    private var mConnTime: Long = 0
    private var mState: EState = Empty()


    private val connectionTimeoutMillis = 10000
    private val unknownGattConnectionError = 133
    private val uuidServiceDeviceInfo = convertFromInteger(0x180A)
    private val uuidServiceMain = convertFromInteger(0xFFF0)
    private val uuidMainData = convertFromInteger(0xFFF1)
    private val uuidDeviceModel = convertFromInteger(0x2A24)
    private val uuidSerialNumber = convertFromInteger(0x2A25)
    private val uuidManufacturer = convertFromInteger(0x2A29)
    private val uuidMainDescriptor = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onCreate() {
        mBinder = KtgBinder()
        mHandler = object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_CONNECTION_STARTED -> if (mBluetoothAdapter.isEnabled) {
                        if (mBluetoothGatt != null) {
                            try {
                                mBluetoothGatt?.discoverServices()
                            } catch (e: SecurityException) {
                                e.printStackTrace()
                            }
                        }
                        mConnectionTimeoutHandler.removeCallbacks(mConnectionTimeoutRunnable)
                    }
                    MSG_CONNECTION_FINISHED -> if (mState.connectionStatus != STATUS_UNSTABLE) {
                        mConnTime = System.currentTimeMillis()
                        ktgCallback.onConnectionStatusChanged(STATUS_CONNECTION_SUCCESSFUL)
                        mState.connected()
                        if (mState is Pause) {
                            ktgCallback.onConnectionStatusChanged(STATUS_RECONNECTED)
                            val monitor: ExaminationData.Monitor =
                                (mState as Pause).monitorData
                            updateState(Recording(monitor))
                            monitor.reconnectPoints.add(recordDuration)
                        } else {
                            updateState(mState)
                        }
                        readNextCharacteristic()
                    } else {
                        recordLastStartTime = System.currentTimeMillis()
                        readDeviceData()
                    }
                    MSG_GOT_SERVICES -> {
                        val mainService = mBluetoothGatt?.getService(uuidServiceMain)
                        val deviceInfoService =
                            mBluetoothGatt?.getService(uuidServiceDeviceInfo)
                        if (mainService != null && deviceInfoService != null) {
                            val mainCharacteristic = mainService.getCharacteristic(uuidMainData)
                            if (mDeviceModel == "") {
                                val modelCharacteristic =
                                    deviceInfoService.getCharacteristic(uuidDeviceModel)
                                mCharQueue.add(modelCharacteristic)
                            }
                            if (mSerialNumber == "") {
                                val serialCharacteristic =
                                    deviceInfoService.getCharacteristic(uuidSerialNumber)
                                mCharQueue.add(serialCharacteristic)
                            }
                            if (mManufacturer == "") {
                                val manufacturerCharacteristic =
                                    deviceInfoService.getCharacteristic(uuidManufacturer)
                                mCharQueue.add(manufacturerCharacteristic)
                            }
                            mCharQueue.add(mainCharacteristic)
                            readNextCharacteristic()
                        }
                    }
                    MSG_GOT_CHARACTERISTICS -> {
                        val extra = msg.data
                        val serial = extra.getString(EXTRA_SERIAL_NUMBER)
                        val device = extra.getString(EXTRA_DEVICE_MODEL)
                        val manufacturer = extra.getString(EXTRA_MANUFACTURER)
                        if (serial != null) {
                            mSerialNumber = serial
                        }
                        if (device != null) {
                            mDeviceModel = device
                        }
                        if (manufacturer != null) {
                            mManufacturer = manufacturer
                        }
                        readNextCharacteristic()
                    }
                    MSG_DISCONNECTED -> {
                        mState.disconnected()
                        mSocket = null
                        mConnectionTimeoutHandler.removeCallbacks(mConnectionTimeoutRunnable)
                        if (mConnTime + 10000 > System.currentTimeMillis() && isCharsReceived && mBluetoothAdapter.isEnabled) {
                            mState.connectionUnstable()
                            if (mBluetoothGatt != null) {
                                try {
                                    mBluetoothGatt?.disconnect()
                                    mBluetoothGatt?.close()
                                } catch (e: SecurityException) {
                                    e.printStackTrace()
                                }
                            }
                            post { connect() }
                            ktgCallback.onConnectionStatusChanged(ERROR_CONNECTION_UNSTABLE)
                        } else {
                            if ((mState !is Complete) and (mState !is Empty)) {
                                updateState(Pause(mState.monitorData))
                            }
                            ktgCallback.onConnectionStatusChanged(ERROR_CONNECTION_LOST)
                        }
                    }
                    MSG_CONNECTION_ERROR -> {
                        mState.disconnected()
                        ktgCallback.onConnectionStatusChanged(ERROR_CONNECTION_FAILED)
                        mConnectionTimeoutHandler.removeCallbacks(mConnectionTimeoutRunnable)
                        mSocket = null
                    }
                }
            }
        }
        mBluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        mLMTPDecoder.setLMTPDecoderListener(mLMTPDListener)
        mLMTPDecoder.prepare()
        mConnectionTimeoutRunnable = Runnable {
            if (mBluetoothGatt != null) {
                try {
                    mBluetoothGatt?.disconnect()
                    mBluetoothGatt?.close()
                    mHandler.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    mState.disconnected()
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
        enableLog()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
        disableLog()
    }

    fun setBluetoothDevice(device: BluetoothDevice) {
        mBtDevice = device
    }

    fun start() {
        if (!mLMTPDecoder.isWorking) mLMTPDecoder.startWork()
        connect()
    }

    private fun cancel() {
        updateState(Empty())
        try {
            mSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }


        try {
            mBluetoothGatt?.disconnect()
            mBluetoothGatt?.close()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        if (this::mRecordFile.isInitialized && mRecordFile.exists()) mRecordFile.delete()
        mSocket = null
        if (mLMTPDecoder.isWorking) mLMTPDecoder.stopWork()
        mLMTPDecoder.release()
    }

    fun startRecording() {
        if (mState.connectionStatus == STATUS_CONNECTED) {
            if (mState !is Recording) {
                if (mState is Pause) {
                    val dur = recordDuration
                    updateState(Recording((mState as Pause).monitorData))
                    val monitor: ExaminationData.Monitor = (mState as Pause).monitorData
                    monitor.addReconnectPoint(dur)
                    if (!mLMTPDecoder.isRecording) {
                        mLMTPDecoder.continueRecordWave()
                    }
                } else {
                    mState.monitorData?.clear()
                    createWaveFile()
                    updateState(Recording(mState.monitorData))
                }
            }
        } else {
            if (!mState.isConnectingOrConnected) start()
        }
    }

    fun pauseRecording() {
        if (mState is Recording) {
            updateState(Pause(mState.monitorData))
        }
    }

    fun finishRecording() {
        if (mState is Recording || mState is Pause) {
            if (mLMTPDecoder.isRecording) {
                mLMTPDecoder.finishRecordWave()
            }
            val record: ExaminationData.Record =
                ExaminationData.Record(floor(recordDuration.toDouble()).toInt(), mRecordFile)
            updateState(Complete.Saving(mState.monitorData, record))
        }
    }

    fun clear() {
        if (mRecordFile.exists()) mRecordFile.delete()
        mState.monitorData?.clear()
    }

    fun dismissRecording() {
        updateState(Empty())
    }

    val recordDuration: Float
        get() = if (mState is Recording) {
            (mState as Recording).totalRecordDuration + (System.currentTimeMillis() - recordLastStartTime).toFloat() / 1000f
        } else {
            mState.monitorData?.datasetDuration ?: 0f
        }

    fun setSavingFailed() {
        val monitor: ExaminationData.Monitor = mState.monitorData
        monitor.addReconnectPoint(recordDuration)
        updateState(Pause(monitor))
    }

    fun examinationSaved(metaInfo: ExaminationMetaInfo?) {
        updateState(
            Complete.Success(
                mState.monitorData,
                (mState as Complete?)?.recordData,
                metaInfo
            )
        )
    }

    fun examinationDeleted() {
        updateState(Empty())
    }

    val state: EState
        get() = mState

    /**
     * Соединение с устройством, получение характеристик
     * и подписка на изменение данных монитора
     */
    private fun connect() {
        if (mLMTPDecoder.isWorking) {
            if (!mBluetoothAdapter.isEnabled) {
                //TODO show error
//                PSDialogUtils.doShowErrorFromResult(
//                    this,
//                    getString(R.string.request_bluetooth_required)
//                )
                return
            }
            val version = Build.VERSION.SDK_INT
            if (mState.connectionStatus == STATUS_UNSTABLE) {
                mHandler.postDelayed({
                    Thread { connectToDeviceSocket() }.start()
                }, 250)
            } else if (mState.connectionStatus == STATUS_DISCONNECTED) {
                mState.connectionStarted()
                if (version >= Build.VERSION_CODES.N || version < Build.VERSION_CODES.LOLLIPOP) {
                    connectToDeviceLe() //Для этих версий необходимо подключаться к gatt в UI потоке
                } else {
                    Thread { connectToDeviceLe() }.start()
                }
            }
        }
    }

    /**
     * Соединение с устройством по LE-каналу
     */
    private fun connectToDeviceLe() {

        try {
            mBluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (status == unknownGattConnectionError) {
                    mHandler.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        mHandler.sendEmptyMessage(MSG_DISCONNECTED)
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        mHandler.sendEmptyMessage(MSG_CONNECTION_STARTED)
                    }
                    else -> {
                        mHandler.sendEmptyMessage(MSG_DISCONNECTED)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                mHandler.sendEmptyMessage(MSG_GOT_SERVICES)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, status)
                val charUUID = characteristic.uuid
                val message = Message()
                message.what = MSG_GOT_CHARACTERISTICS
                val data = Bundle()
                val value = characteristic.getStringValue(0)
                when (charUUID) {
                    uuidDeviceModel -> {
                        data.putString(EXTRA_DEVICE_MODEL, value)
                    }
                    uuidSerialNumber -> {
                        data.putString(EXTRA_SERIAL_NUMBER, value)
                    }
                    uuidManufacturer -> {
                        data.putString(EXTRA_MANUFACTURER, value)
                    }
                }
                message.data = data
                mHandler.sendMessage(message)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                mLMTPDecoder.putData(characteristic.value)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                mHandler.sendEmptyMessage(MSG_CONNECTION_FINISHED)
            }
        }
        try {
            if (mBluetoothGatt == null) {
                ktgCallback.onConnectionStatusChanged(STATUS_CONNECTION_STARTED)
                mBluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mBtDevice.connectGatt(
                        this@KtgService,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE
                    ) //Для версий выше M необходимо вручную задавать transport. Причина неизвестна.
                } else {
                    mBtDevice.connectGatt(
                        this@KtgService,
                        true,
                        callback
                    ) //Lollipop и ниже не подлючаются без флага autoConnect = true. Причина неизвестна.
                }
            } else {
                mBluetoothGatt?.close()
                mState.disconnected()
                connect()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        mConnectionTimeoutHandler.removeCallbacks(mConnectionTimeoutRunnable)
        mConnectionTimeoutHandler.postDelayed(
            mConnectionTimeoutRunnable,
            connectionTimeoutMillis.toLong()
        )
    }

    /**
     * Подключение к устройству по сокету.
     * Необходимо сопяржение с bluetooth - устройством.
     */
    private fun connectToDeviceSocket() {
        try {
            if (mBtDevice.bondState == BluetoothDevice.BOND_NONE) {
                mBtDevice.createBond()
            }
            if (mBtDevice.bondState == BluetoothDevice.BOND_BONDING) {
                mHandler.postDelayed({ connectToDeviceSocket() }, 150)
                return
            }
            if (mSocket == null) {
                mSocket = try {
                    mBtDevice.createInsecureRfcommSocketToServiceRecord(myUuid)
                } catch (e: IOException) {
                    mHandler.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    return
                }
                try {
                    if (mBtDevice.name != null && mBtDevice.name.isNotEmpty()) {
                        mSocket?.connect()
                        mHandler.sendEmptyMessage(MSG_CONNECTION_FINISHED)
                    } else {
                        mHandler.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    }
                } catch (e: Exception) {
                    try {
                        val clazz: Class<*> = mBtDevice.javaClass
                        val paramTypes = arrayOf<Class<*>>(Integer.TYPE)
                        val m = clazz.getMethod("createRfcommSocket", *paramTypes)
                        val params = arrayOf<Any>(Integer.valueOf(1))
                        mSocket = m.invoke(mBtDevice, *params) as BluetoothSocket
                        mSocket?.connect()
                        mHandler.sendEmptyMessage(MSG_CONNECTION_FINISHED)
                    } catch (e1: Exception) {
                        mState.disconnected()
                        mHandler.sendEmptyMessage(MSG_CONNECTION_ERROR)
                        mHandler.post {
                            if (mState is Recording) {
                                updateState(Pause(mState.monitorData))
                            }
                        }
                        mSocket = null
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun readDeviceData() {
        if (mSocket == null) {
            connect()
        } else {
            mState.connected()
            mReadThread.start()
        }
    }

    private fun readNextCharacteristic() {
        try {
            if (!mCharQueue.isEmpty()) {
                val characteristic = mCharQueue.removeAt(0)
                if (characteristic.uuid == uuidMainData) {
                    val descriptor = characteristic.getDescriptor(uuidMainDescriptor)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    mBluetoothGatt?.setCharacteristicNotification(characteristic, true)
                    mBluetoothGatt?.writeDescriptor(descriptor)
                } else {
                    mBluetoothGatt?.readCharacteristic(characteristic)
                }
            } else if (mDeviceModel != "" && mManufacturer != "" && mSerialNumber != "") {
                isCharsReceived = true
                val deviceModel = "$mManufacturer $mDeviceModel"
                val deviceName = mBtDevice.name
                if (deviceName != null && deviceName.isNotEmpty()
                    && mSerialNumber.replace("0".toRegex(), "").isEmpty()
                ) {
                    mSerialNumber = deviceName
                }
                ktgCallback.onCharacteristicsReceived(mSerialNumber, deviceModel)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun convertFromInteger(i: Int): UUID {
        val msb = 0x0000000000001000L
        val lsb = -0x7fffff7fa064cb05L
        val value = (i and -0x1).toLong()
        return UUID(msb or (value shl 32), lsb)
    }

    private fun sleep() {
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun updateState(state: EState) {
        state.connectionStatus = mState.connectionStatus
        if (state is Pause && mState !is Pause) {
            state.setTotalRecordDuration(mState.monitorData?.datasetDuration!!)
        } else {
            state.totalRecordDuration = mState.totalRecordDuration
            if (state is Recording) {
                if (mState !is Pause) {
                    state.setTotalRecordDuration(0f)
                }
                recordLastStartTime = System.currentTimeMillis()
            }
        }
        mState = state
        ktgCallback.onStateChanged(state)
    }

    private fun createWaveFile() {
        val directory = filesDir
        val fname = "CTG_" + java.lang.Long.toHexString(System.currentTimeMillis())
        val child = "$fname.wav"
        mRecordFile = mLMTPDecoder.beginRecordWave(directory, child)
    }

    inner class KtgBinder : Binder() {
        val service: KtgService
            get() = this@KtgService
    }

    //endregion
    //region Private classes
    private inner class ReadThread : Thread() {
        private var mIs: InputStream? = null
        override fun run() {
            try {
                mIs = mSocket!!.inputStream
            } catch (e: IOException) {
                mState.disconnected()
            }
            var len: Int
            val buffer = ByteArray(2048)
            while (mState.connectionStatus == STATUS_CONNECTED) {
                try {
                    len = mIs!!.read(buffer)
                    mLMTPDecoder.putData(buffer, 0, len)
                    this@KtgService.sleep()
                } catch (e: IOException) {
                    mHandler.sendEmptyMessage(MSG_DISCONNECTED)
                }
            }
            try {
                mIs!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private inner class LMTPDListener : LMTPDecoderListener {
        override fun fhrDataChanged(fhrData: FhrData?) {
            val data: PSChartData = PSChartData.map(fhrData)
            data.time = recordDuration
            if (mState is Recording) {
                mState.monitorData?.data?.add(data)
            }
            ktgCallback.onMonitorDataReceived(data)
        }

        override fun sendCommand(cmd: ByteArray?) {}
    }

    companion object {
        const val STATUS_CONNECTION_STARTED = 13
        const val STATUS_RECONNECTED = 14
        const val STATUS_CONNECTION_SUCCESSFUL = 16
        const val ERROR_CONNECTION_FAILED = 20
        const val ERROR_CONNECTION_LOST = 21
        const val ERROR_CONNECTION_UNSTABLE = 22
        private const val MSG_CONNECTION_STARTED = 25
        private const val MSG_CONNECTION_FINISHED = 30
        private const val MSG_GOT_CHARACTERISTICS = 40
        private const val MSG_GOT_SERVICES = 50
        private const val MSG_DISCONNECTED = 60
        private const val MSG_CONNECTION_ERROR = 65
        private const val EXTRA_DEVICE_MODEL = "EXTRA_DEVICE_MODEL"
        private const val EXTRA_SERIAL_NUMBER = "EXTRA_SERIAL_NUMBER"
        private const val EXTRA_MANUFACTURER = "EXTRA_MANUFACTURER"
        val myUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}