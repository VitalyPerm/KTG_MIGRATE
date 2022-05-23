package com.elvitalya.ktg_migrate

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.elvitalya.ktg_migrate.KtgService.Companion.ERROR_CONNECTION_FAILED
import com.elvitalya.ktg_migrate.KtgService.Companion.ERROR_CONNECTION_LOST
import com.elvitalya.ktg_migrate.KtgService.Companion.ERROR_CONNECTION_UNSTABLE
import com.elvitalya.ktg_migrate.KtgService.Companion.STATUS_CONNECTION_STARTED
import com.elvitalya.ktg_migrate.KtgService.Companion.STATUS_CONNECTION_SUCCESSFUL
import com.elvitalya.ktg_migrate.KtgService.Companion.STATUS_RECONNECTED
import com.elvitalya.ktg_migrate.java.EState
import com.elvitalya.ktg_migrate.java.PSChartData

const val TAG = "check___"

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var ktgService: KtgService

    private lateinit var ktgCallBack: KtgCallback

    private var isServiceBound = false

    private lateinit var device: BluetoothDevice

    private val deviceName = "Doctis CTG"

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (it.value == false)
                    allGranted = false
            }
            if (allGranted) {
                enableBtAdapter()
                startBleScan()
            }
        }

    private val bleScanCallback: ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                try {
                    result?.device?.let {
                        if (it.name == deviceName) {
                            device = it
                            Log.d(TAG, "device found ${device.name} ")
                            stopBleScan()
                            bindBleReceivedService()
                        }
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bluetoothAdapter =
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        requestBtPermission()

        ktgCallBack = object : KtgCallback {
            override fun onMonitorDataReceived(newData: PSChartData?) {
                runOnUiThread {
                    //   setBatteryLevel(data.devicePower)
                    Log.d(TAG, "onMonitorDataReceived: ${newData?.devicePower}")
                    //      setFhrTocoValues(data.fhr, data.toco)
                    Log.d(TAG, "onMonitorDataReceived:fhr ${newData?.fhr}")
                    Log.d(TAG, "onMonitorDataReceived:toco ${newData?.toco}")
//                    if (mBluetoothMonitorService.getState() is EState.Recording) {
//                        addEntry(data, true)
//                        updateTimerValue()
//                    }
                }
            }

            override fun onConnectionStatusChanged(status: Int) {
                when (status) {
                    STATUS_CONNECTION_STARTED -> {
                        Log.d(TAG, "onConnectionStatusChanged: STATUS_CONNECTION_STARTED")
                    }
                    STATUS_CONNECTION_SUCCESSFUL -> {
                        Log.d(TAG, "onConnectionStatusChanged: STATUS_CONNECTION_SUCCESSFUL")
                    }
                    STATUS_RECONNECTED -> {
                        Log.d(TAG, "onConnectionStatusChanged: STATUS_RECONNECTED")
                    }
                    ERROR_CONNECTION_FAILED, ERROR_CONNECTION_LOST -> {
                        Log.d(TAG, "onConnectionStatusChanged: ERROR_CONNECTION_LOST")
                    }
                    ERROR_CONNECTION_UNSTABLE -> {
                        Log.d(TAG, "onConnectionStatusChanged: ERROR_CONNECTION_UNSTABLE")
                    }
                }
            }

            override fun onCharacteristicsReceived(serialNumber: String?, deviceModel: String?) {
                Log.d(TAG, "onCharacteristicsReceived: serialNumber $serialNumber")
                Log.d(TAG, "onCharacteristicsReceived: deviceModel $deviceModel")
            }

            override fun onStateChanged(state: EState?) {
                renderState(state)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopBleScan()
    }

    override fun onStart() {
        super.onStart()
        startBleScan()
    }

    private fun stopBleScan() {
        try {
            bluetoothAdapter.bluetoothLeScanner.stopScan(bleScanCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun bindBleReceivedService() {
        if (!isServiceBound) {
            Log.d(TAG, "bindBleReceivedService: bind service called")
            bindService(
                Intent(this, KtgService::class.java),
                mBleReceivedServiceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
        isServiceBound = true
    }

    private val mBleReceivedServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            isServiceBound = false
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "onServiceConnected: onServiceConnected")
            ktgService = (service as KtgService.KtgBinder).service
            ktgService.ktgCallback = ktgCallBack
            ktgService.setBluetoothDevice(device)
            val state = ktgService.state
            if (state is EState.Empty) {
                ktgService.start()
            }
            renderState(state)
        }
    }

    private fun renderState(state: EState?) {
        if (state?.connectionStatus == EState.STATUS_CONNECTING) {
            //showPreloader()
            Log.d(TAG, "renderState: STATUS_CONNECTING")
        } else {
            // dismissPreloader()
            Log.d(TAG, "renderState: STATUS_NOT_CONNECTING")
        }
        val monitorData = state?.monitorData
        val monitorList = monitorData?.data
        val movePoints = monitorData?.movePoints
        val reconnectPoints = monitorData?.reconnectPoints

        monitorList?.forEach {
            Log.d(TAG, "renderState:devicePower ${it.devicePower}")
            Log.d(TAG, "renderState:fhr ${it.fhr}")
            Log.d(TAG, "renderState:time ${it.time}")
        }
        movePoints?.forEach {
            Log.d(TAG, "renderState:movePoints $it")
        }

        reconnectPoints?.forEach {
            Log.d(TAG, "renderState:reconnectPoints $it")
        }

    }


    private fun checkPermissions(): Boolean {
        val locationPermissionsGranted = (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btPermissionsGranted = (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED)
                    && (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED)
            btPermissionsGranted && locationPermissionsGranted
        } else locationPermissionsGranted
    }

    private fun requestBtPermission() {
        if (checkPermissions()) {
            enableBtAdapter()
            startBleScan()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            } else {
                requestPermissions.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )
            }
        }
    }


    private fun enableBtAdapter() {
        try {
            if (!bluetoothAdapter.isEnabled) bluetoothAdapter.enable()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun startBleScan() {
        val scanFilter = ScanFilter.Builder().build()
        val scanFilters: MutableList<ScanFilter> = mutableListOf()
        scanFilters.add(scanFilter)
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
        try {
            bluetoothAdapter.bluetoothLeScanner.startScan(
                scanFilters,
                scanSettings,
                bleScanCallback
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}

