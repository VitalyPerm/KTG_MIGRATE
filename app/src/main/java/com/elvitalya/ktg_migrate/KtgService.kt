package com.elvitalya.ktg_migrate

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.*
import com.elvitalya.ktg_migrate.EState.*
import com.luckcome.lmtpdecorder.LMTPDecoderListener
import com.luckcome.lmtpdecorder.data.FhrData
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.floor


@SuppressLint("MissingPermission")
class KtgService : Service() {
    //region Private fields
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mCallback: KtgCallback? = null
    private var mBtDevice: BluetoothDevice? = null
    private var mLMTPDecoder: PSDecoder? = null
    private var mLMTPDListener: LMTPDListener? = null
    private var mBinder: KtgBinder? = null
    private var mHandler: Handler? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mSocket: BluetoothSocket? = null
    private var mReadThread: Thread? = null
    private var mRecordFile: File? = null
    var recordLastStartTime: Long = 0
        private set
    private var mSerialNumber: String? = null
    private var mDeviceModel: String? = null
    private var mManufacturer: String? = null
    private var mCharQueue: LinkedList<BluetoothGattCharacteristic>? = null
    private var isCharsReceived = false
    private var mConnectionTimeoutHandler: Handler? = null
    private var mConnectionTimeoutRunnable: Runnable? = null
    private var mConnTime: Long = 0
    private var mState: EState? = null

    //endregion
    //region Constants
    private val CONNECTION_TIMEOUT_MILLIS = 10000
    private val UNKNOWN_GATT_CONNECTION_ERROR = 133
    private val UUID_SERVICE_DEVICE_INFO = convertFromInteger(0x180A)
    private val UUID_SERVICE_MAIN = convertFromInteger(0xFFF0)
    private val UUID_MAIN_DATA = convertFromInteger(0xFFF1)
    private val UUID_DEVICE_MODEL = convertFromInteger(0x2A24)
    private val UUID_SERIAL_NUMBER = convertFromInteger(0x2A25)
    private val UUID_MANUFACTURER = convertFromInteger(0x2A29)
    private val UUID_MAIN_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    //endregion
    //region Public methods
    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    override fun onCreate() {
        mBinder = KtgBinder()
        mHandler = object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_CONNECTION_STARTED -> if (mBluetoothAdapter!!.isEnabled) {
                        if (mBluetoothGatt != null) {
                            try {
                                mBluetoothGatt!!.discoverServices()
                            } catch (e: SecurityException) {
                                e.printStackTrace()
                            }
                        }
                        mConnectionTimeoutHandler!!.removeCallbacks(mConnectionTimeoutRunnable!!)
                    }
                    MSG_CONNECTION_FINISHED -> if (mState?.connectionStatus != STATUS_UNSTABLE) {
                        mConnTime = System.currentTimeMillis()
                        mCallback?.onConnectionStatusChanged(STATUS_CONNECTION_SUCCESSFUL)
                        mState?.connected()
                        if (mState is EState.Pause) {
                            mCallback?.onConnectionStatusChanged(STATUS_RECONNECTED)
                            val monitor: ExaminationData.Monitor =
                                (mState as EState.Pause).monitorData
                            updateState(EState.Recording(monitor))
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
                        val mainService = mBluetoothGatt!!.getService(UUID_SERVICE_MAIN)
                        val deviceInfoService =
                            mBluetoothGatt!!.getService(UUID_SERVICE_DEVICE_INFO)
                        if (mainService != null && deviceInfoService != null) {
                            val mainCharacteristic = mainService.getCharacteristic(UUID_MAIN_DATA)
                            if (mDeviceModel == null) {
                                val modelCharacteristic =
                                    deviceInfoService.getCharacteristic(UUID_DEVICE_MODEL)
                                mCharQueue!!.add(modelCharacteristic)
                            }
                            if (mSerialNumber == null) {
                                val serialCharacteristic =
                                    deviceInfoService.getCharacteristic(UUID_SERIAL_NUMBER)
                                mCharQueue!!.add(serialCharacteristic)
                            }
                            if (mManufacturer == null) {
                                val manufacturerCharacteristic =
                                    deviceInfoService.getCharacteristic(UUID_MANUFACTURER)
                                mCharQueue!!.add(manufacturerCharacteristic)
                            }
                            mCharQueue!!.add(mainCharacteristic)
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
                        mState?.disconnected()
                        mReadThread = null
                        mSocket = null
                        mConnectionTimeoutHandler!!.removeCallbacks(mConnectionTimeoutRunnable!!)
                        if (mConnTime + 10000 > System.currentTimeMillis() && isCharsReceived && mBluetoothAdapter!!.isEnabled) {
                            mState?.connectionUnstable()
                            if (mBluetoothGatt != null) {
                                try {
                                    mBluetoothGatt!!.disconnect()
                                    mBluetoothGatt!!.close()
                                } catch (e: SecurityException) {
                                    e.printStackTrace()
                                }
                            }
                            post { connect() }
                            mCallback?.onConnectionStatusChanged(ERROR_CONNECTION_UNSTABLE)
                        } else {
                            if ((mState !is EState.Complete) and (mState !is EState.Empty)) {
                                updateState(EState.Pause(mState?.monitorData))
                            }
                            mCallback?.onConnectionStatusChanged(ERROR_CONNECTION_LOST)
                        }
                    }
                    MSG_CONNECTION_ERROR -> {
                        mState?.disconnected()
                        mCallback?.onConnectionStatusChanged(ERROR_CONNECTION_FAILED)
                        mConnectionTimeoutHandler!!.removeCallbacks(mConnectionTimeoutRunnable!!)
                        mReadThread = null
                        mSocket = null
                    }
                }
            }
        }
        mBluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        mCharQueue = LinkedList()
        mLMTPDecoder = PSDecoder()
        mLMTPDListener = LMTPDListener()
        mLMTPDecoder?.setLMTPDecoderListener(mLMTPDListener)
        mLMTPDecoder?.prepare()
        mConnectionTimeoutHandler = Handler()
        mConnectionTimeoutRunnable = Runnable {
            if (mBluetoothGatt != null) {
                try {
                    mBluetoothGatt!!.disconnect()
                    mBluetoothGatt!!.close()
                    mHandler?.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    mState?.disconnected()
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
        enableLog()
        mState = EState.Empty()
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
        startRecordWave()
        connect()
    }

    private fun tryToReconnect() {
        if (!mState?.isConnectingOrConnected!!) {
            start()
        }
    }

    fun cancel() {
        updateState(Empty())
        if (mSocket != null) {
            try {
                mSocket!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt!!.disconnect()
                mBluetoothGatt!!.close()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        if (mRecordFile != null && mRecordFile!!.exists()) {
            mRecordFile!!.delete()
        }
        mSocket = null
        mReadThread = null
        stopRecordWave()
        mLMTPDecoder?.release()
        mLMTPDecoder = null
        mLMTPDListener = null
    }

    fun setCallback(cb: KtgCallback?) {
        mCallback = cb
    }

    fun startRecording() {
        if (mState?.connectionStatus == STATUS_CONNECTED) {
            if (mState !is Recording) {
                if (mState is Pause) {
                    val dur = recordDuration
                    updateState(Recording((mState as Pause).monitorData))
                    val monitor: ExaminationData.Monitor = (mState as Pause).monitorData
                    monitor.addReconnectPoint(dur)
                    if (!mLMTPDecoder?.isRecording!!) {
                        mLMTPDecoder!!.continueRecordWave()
                    }
                } else {
                    mState?.monitorData?.clear()
                    createWaveFile()
                    updateState(Recording(mState?.monitorData))
                }
            }
        } else {
            tryToReconnect()
        }
    }

    fun pauseRecording() {
        if (mState is Recording) {
            updateState(Pause(mState?.monitorData))
        }
    }

    fun finishRecording() {
        if (mState is Recording ||
            mState is Pause
        ) {
            if (mLMTPDecoder != null && mLMTPDecoder?.isRecording == true) {
                mLMTPDecoder?.finishRecordWave()
            }
            val record: ExaminationData.Record =
                ExaminationData.Record(floor(recordDuration.toDouble()).toInt(), mRecordFile)
            updateState(Complete.Saving(mState?.monitorData, record))
        }
    }

    fun clear() {
        if (mRecordFile != null && mRecordFile!!.exists()) {
            mRecordFile!!.delete()
        }
        mRecordFile = null
        mState?.monitorData?.clear()
    }

    fun dismissRecording() {
        updateState(Empty())
    }

    val recordDuration: Float
        get() = if (mState is Recording) {
            (mState as Recording).totalRecordDuration + (System.currentTimeMillis() - recordLastStartTime).toFloat() / 1000f
        } else {
            mState?.monitorData?.datasetDuration!!
        }

    fun setSavingFailed() {
        val monitor: ExaminationData.Monitor = mState?.monitorData!!
        monitor.addReconnectPoint(recordDuration)
        updateState(Pause(monitor))
    }

    fun examinationSaved(metaInfo: ExaminationMetaInfo?) {
        updateState(
            Complete.Success(
                mState?.monitorData,
                (mState as Complete?)?.recordData,
                metaInfo
            )
        )
    }

    fun examinationDeleted() {
        updateState(Empty())
    }

    private fun stopRecordWave() {
        if (mLMTPDecoder != null && mLMTPDecoder?.isWorking == true) {
            mLMTPDecoder?.stopWork()
        }
    }

    private fun startRecordWave() {
        if (mLMTPDecoder != null && mLMTPDecoder?.isWorking == false) {
            mLMTPDecoder?.startWork()
        }
    }

    val state: EState?
        get() = mState
    //endregion
    //region Private methods
    /**
     * Соединение с устройством, получение характеристик
     * и подписка на изменение данных монитора
     */
    private fun connect() {
        if (mLMTPDecoder != null && mLMTPDecoder?.isWorking == true) {
            if (!mBluetoothAdapter!!.isEnabled) {
                //TODO show error
//                PSDialogUtils.doShowErrorFromResult(
//                    this,
//                    getString(R.string.request_bluetooth_required)
//                )
                return
            }
            val version = Build.VERSION.SDK_INT
            if (mState?.connectionStatus == STATUS_UNSTABLE) {
                mHandler!!.postDelayed({
                    Thread { connectToDeviceSocket() }.start()
                }, 250)
            } else if (mState?.connectionStatus == STATUS_DISCONNECTED) {
                mState?.connectionStarted()
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
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt!!.disconnect()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (status == UNKNOWN_GATT_CONNECTION_ERROR) {
                    mHandler!!.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    return
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mHandler!!.sendEmptyMessage(MSG_DISCONNECTED)
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mHandler!!.sendEmptyMessage(MSG_CONNECTION_STARTED)
                } else {
                    mHandler!!.sendEmptyMessage(MSG_DISCONNECTED)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                mHandler!!.sendEmptyMessage(MSG_GOT_SERVICES)
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
                if (charUUID == UUID_DEVICE_MODEL) {
                    data.putString(EXTRA_DEVICE_MODEL, value)
                } else if (charUUID == UUID_SERIAL_NUMBER) {
                    data.putString(EXTRA_SERIAL_NUMBER, value)
                } else if (charUUID == UUID_MANUFACTURER) {
                    data.putString(EXTRA_MANUFACTURER, value)
                }
                message.data = data
                mHandler!!.sendMessage(message)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                super.onCharacteristicChanged(gatt, characteristic)
                mLMTPDecoder?.putData(characteristic.value)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(gatt, descriptor, status)
                mHandler!!.sendEmptyMessage(MSG_CONNECTION_FINISHED)
            }
        }
        try {
            if (mBluetoothGatt == null) {
                mCallback?.onConnectionStatusChanged(STATUS_CONNECTION_STARTED)
                mBluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mBtDevice!!.connectGatt(
                        this@KtgService,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE
                    ) //Для версий выше M необходимо вручную задавать transport. Причина неизвестна.
                } else {
                    mBtDevice!!.connectGatt(
                        this@KtgService,
                        true,
                        callback
                    ) //Lollipop и ниже не подлючаются без флага autoConnect = true. Причина неизвестна.
                }
            } else {
                mBluetoothGatt!!.close()
                mBluetoothGatt = null
                mState?.disconnected()
                connect()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        mConnectionTimeoutHandler!!.removeCallbacks(mConnectionTimeoutRunnable!!)
        mConnectionTimeoutHandler!!.postDelayed(
            mConnectionTimeoutRunnable!!,
            CONNECTION_TIMEOUT_MILLIS.toLong()
        )
    }

    /**
     * Подключение к устройству по сокету.
     * Необходимо сопяржение с bluetooth - устройством.
     */
    private fun connectToDeviceSocket() {
        try {
            if (mBtDevice!!.bondState == BluetoothDevice.BOND_NONE) {
                mBtDevice!!.createBond()
            }
            if (mBtDevice!!.bondState == BluetoothDevice.BOND_BONDING) {
                mHandler!!.postDelayed({ connectToDeviceSocket() }, 150)
                return
            }
            if (mSocket == null) {
                mSocket = try {
                    mBtDevice!!.createInsecureRfcommSocketToServiceRecord(MY_UUID)
                } catch (e: IOException) {
                    mHandler!!.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    return
                }
                try {
                    if (mBtDevice!!.name != null && mBtDevice!!.name.isNotEmpty()) {
                        mSocket?.connect()
                        mHandler!!.sendEmptyMessage(MSG_CONNECTION_FINISHED)
                    } else {
                        mHandler!!.sendEmptyMessage(MSG_CONNECTION_ERROR)
                    }
                } catch (e: Exception) {
                    try {
                        val clazz: Class<*> = mBtDevice!!.javaClass
                        val paramTypes = arrayOf<Class<*>>(Integer.TYPE)
                        val m = clazz.getMethod("createRfcommSocket", *paramTypes)
                        val params = arrayOf<Any>(Integer.valueOf(1))
                        mSocket = m.invoke(mBtDevice, *params) as BluetoothSocket
                        mSocket!!.connect()
                        mHandler!!.sendEmptyMessage(MSG_CONNECTION_FINISHED)
                    } catch (e1: Exception) {
                        mState?.disconnected()
                        mHandler!!.sendEmptyMessage(MSG_CONNECTION_ERROR)
                        mHandler!!.post {
                            if (mState is Recording) {
                                updateState(Pause(mState?.monitorData))
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
            mState?.connected()
            mReadThread = ReadThread()
            mReadThread!!.start()
        }
    }

    private fun readNextCharacteristic() {
        try {
            if (!mCharQueue!!.isEmpty()) {
                val characteristic = mCharQueue!!.removeAt(0)
                if (characteristic.uuid == UUID_MAIN_DATA) {
                    val descriptor = characteristic.getDescriptor(UUID_MAIN_DESCRIPTOR)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    mBluetoothGatt!!.setCharacteristicNotification(characteristic, true)
                    mBluetoothGatt!!.writeDescriptor(descriptor)
                } else {
                    mBluetoothGatt!!.readCharacteristic(characteristic)
                }
            } else if (mCallback != null && mDeviceModel != null && mManufacturer != null && mSerialNumber != null) {
                isCharsReceived = true
                val deviceModel = "$mManufacturer $mDeviceModel"
                val deviceName = mBtDevice!!.name
                if (deviceName != null && deviceName.isNotEmpty() && mSerialNumber!!.replace(
                        "0".toRegex(),
                        ""
                    ).isEmpty()
                ) {
                    mSerialNumber = deviceName
                }
                mCallback?.onCharacteristicsReceived(mSerialNumber, deviceModel)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun convertFromInteger(i: Int): UUID {
        val MSB = 0x0000000000001000L
        val LSB = -0x7fffff7fa064cb05L
        val value = (i and -0x1).toLong()
        return UUID(MSB or (value shl 32), LSB)
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun updateState(state: EState?) {
        state?.connectionStatus = mState?.connectionStatus!!
        if (state is Pause && mState !is Pause) {
            state.setTotalRecordDuration(mState?.monitorData?.datasetDuration!!)
        } else {
            state?.totalRecordDuration = mState?.totalRecordDuration!!
            if (state is Recording) {
                if (mState !is Pause) {
                    state.setTotalRecordDuration(0f)
                }
                recordLastStartTime = System.currentTimeMillis()
            }
        }
        mState = state
        mCallback?.onStateChanged(state)
    }

    private fun createWaveFile() {
        val directory = filesDir
        val fname = "CTG_" + java.lang.Long.toHexString(System.currentTimeMillis())
        val child = "$fname.wav"
        mRecordFile = mLMTPDecoder?.beginRecordWave(directory, child)
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
                mState?.disconnected()
            }
            var len: Int
            val buffer = ByteArray(2048)
            while (mState?.connectionStatus == STATUS_CONNECTED) {
                try {
                    len = mIs!!.read(buffer)
                    mLMTPDecoder?.putData(buffer, 0, len)
                    this@KtgService.sleep(20)
                } catch (e: IOException) {
                    mHandler!!.sendEmptyMessage(MSG_DISCONNECTED)
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
            if (mState is EState.Recording) {
                mState?.monitorData?.data?.add(data)
            }
            mCallback?.onMonitorDataReceived(data)
        }

        override fun sendCommand(cmd: ByteArray?) {}
    } //endregion

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
        val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}