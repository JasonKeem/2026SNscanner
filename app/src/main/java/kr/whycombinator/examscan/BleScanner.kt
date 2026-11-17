package kr.whycombinator.examscan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/** 필터 없는 최고속 BLE 스캔. 콜백은 메인 스레드로 들어온다. */
class BleScanner(
    ctx: Context,
    private val onResult: (ScanResult) -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var running = false

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) onResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            running = false
            onError(errorCode)
        }
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)          // 수신 창 최대 → RSSI 갱신 최속
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)  // 같은 기기의 패킷도 매번 보고
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)         // 약한 신호도 보고
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .setLegacy(false)                                         // 확장 광고(BLE 5)까지 수신
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        return try {
            scanner.startScan(null, settings, cb)
            running = true
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }
}
