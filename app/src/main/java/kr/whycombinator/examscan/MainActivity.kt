package kr.whycombinator.examscan

import android.Manifest
import android.app.Activity
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val devs = HashMap<String, Dev>()
    private val excluded = HashSet<String>()
    private var shown: List<Dev> = emptyList()
    private var tracked: Dev? = null
    private var minRssi = -90
    private var soundOn = false          // 시험장은 정숙해야 하므로 기본은 진동만
    private var scanning = false
    private var scanStartedAt = 0L
    private var lastError: String? = null

    private lateinit var scanner: BleScanner
    private val h = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var vib: Vibrator? = null

    private lateinit var status: TextView
    private lateinit var btnScan: Button
    private lateinit var filterLabel: TextView
    private lateinit var listPanel: LinearLayout
    private lateinit var trackPanel: LinearLayout
    private lateinit var tTitle: TextView
    private lateinit var tRssi: TextView
    private lateinit var tBand: TextView
    private lateinit var tHint: TextView
    private lateinit var graph: TrackView
    private lateinit var overview: OverviewView
    private val riseAlerted = HashMap<String, Long>()
    private lateinit var btnSound: Button
    private val listAdapter = DevAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 화면이 꺼지면 필터 없는 스캔이 멈춘다
        scanner = BleScanner(this, ::onScan) { code ->
            scanning = false
            lastError = "스캔 실패 (코드 $code). 블루투스를 껐다 켠 뒤 다시 시도하세요."
            updateScanButton()
        }
        vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: RuntimeException) {
            null
        }
        buildUi()
        h.post(refresher)
    }

    // ---------- 화면 구성 ----------

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status, LinearLayout.LayoutParams(match, wrap))

        // 목록 화면
        listPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this)
        btnScan = Button(this).apply { setOnClickListener { toggleScan() } }
        val btnBase = Button(this).apply {
            text = "현재 기기 제외"
            setOnClickListener { excludeCurrent() }
        }
        val btnReset = Button(this).apply {
            text = "초기화"
            setOnClickListener {
                devs.clear()
                excluded.clear()
                toast("목록과 제외 기준을 모두 지웠습니다")
            }
        }
        row.addView(btnScan, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(btnBase, LinearLayout.LayoutParams(0, wrap, 1.3f))
        row.addView(btnReset, LinearLayout.LayoutParams(0, wrap, 0.9f))
        listPanel.addView(row, LinearLayout.LayoutParams(match, wrap))

        filterLabel = TextView(this).apply { textSize = 13f }
        listPanel.addView(filterLabel, LinearLayout.LayoutParams(match, wrap))
        val seek = SeekBar(this).apply {
            max = 60                       // -100 ~ -40 dBm
            progress = minRssi + 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    minRssi = -100 + p
                    updateFilterLabel()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        listPanel.addView(seek, LinearLayout.LayoutParams(match, wrap))

        val list = ListView(this).apply {
            adapter = listAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { startTracking(it) }
            }
        }
        overview = OverviewView(this)
        listPanel.addView(overview, LinearLayout.LayoutParams(match, 0, 1.3f))
        listPanel.addView(list, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(listPanel, LinearLayout.LayoutParams(match, 0, 1f))

        // 추적 화면
        trackPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tTitle = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        tRssi = TextView(this).apply {
            textSize = 84f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        tBand = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        tHint = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        graph = TrackView(this)
        val row2 = LinearLayout(this)
        val btnPeak = Button(this).apply {
            text = "최고점 리셋"
            setOnClickListener { tracked?.peak = -127 }
        }
        btnSound = Button(this).apply {
            setOnClickListener {
                soundOn = !soundOn
                updateSoundButton()
            }
        }
        val btnBack = Button(this).apply {
            text = "목록으로"
            setOnClickListener { stopTracking() }
        }
        row2.addView(btnPeak, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(btnSound, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(btnBack, LinearLayout.LayoutParams(0, wrap, 1f))

        trackPanel.addView(tTitle, LinearLayout.LayoutParams(match, wrap))
        trackPanel.addView(tRssi, LinearLayout.LayoutParams(match, wrap))
        trackPanel.addView(tBand, LinearLayout.LayoutParams(match, wrap))
        trackPanel.addView(tHint, LinearLayout.LayoutParams(match, wrap))
        trackPanel.addView(graph, LinearLayout.LayoutParams(match, 0, 1f))
        trackPanel.addView(row2, LinearLayout.LayoutParams(match, wrap))
        root.addView(trackPanel, LinearLayout.LayoutParams(match, 0, 1f))

        setContentView(root)
        updateScanButton()
        updateFilterLabel()
        updateSoundButton()
    }

    private fun updateScanButton() {
        btnScan.text = if (scanning) "스캔 중지" else "스캔 시작"
    }

    private fun updateFilterLabel() {
        filterLabel.text = "표시 기준: $minRssi dBm 이상 (오른쪽으로 밀수록 가까운 기기만)"
    }

    private fun updateSoundButton() {
        btnSound.text = if (soundOn) "소리 끄기" else "소리 켜기"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- 권한과 스캔 ----------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private fun hasPermissions(): Boolean {
        val scanOk = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val locOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return scanOk && locOk
    }

    private fun locationOn(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        return lm?.isLocationEnabled ?: true
    }

    private fun toggleScan() {
        if (scanning) {
            scanner.stop()
            scanning = false
            updateScanButton()
            return
        }
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), 1)
            return
        }
        startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (hasPermissions()) {
            startScan()
        } else {
            lastError = "권한이 필요합니다. '근처 기기'와 '정확한 위치'를 모두 허용해야 스캔 결과가 나옵니다."
        }
    }

    private fun startScan() {
        lastError = null
        if (!scanner.bluetoothOn) {
            lastError = "블루투스가 꺼져 있습니다. 켠 뒤 다시 누르세요."
            return
        }
        if (scanner.start()) {
            scanning = true
            scanStartedAt = SystemClock.elapsedRealtime()
        } else {
            lastError = "스캔을 시작하지 못했습니다."
        }
        updateScanButton()
    }

    private fun onScan(r: ScanResult) {
        val rssi = r.rssi
        if (rssi >= 20 || rssi < -126) return          // 127 = 측정 불가
        val addr = r.device?.address ?: return
        val now = SystemClock.elapsedRealtime()
        val d = devs.getOrPut(addr) { Dev(addr) }
        d.add(now, rssi)
        val rec = r.scanRecord ?: return
        rec.deviceName?.let { if (it.isNotBlank()) d.name = it }
        val m = rec.manufacturerSpecificData
        if (m != null && m.size() > 0) d.vendorId = m.keyAt(0)
        rec.serviceUuids?.forEach { d.uuids.add(it.uuid.toString()) }
        rec.serviceData?.keys?.forEach { d.uuids.add(it.uuid.toString()) }
    }

    /** 지금 보이는 기기를 전부 '환경'으로 간주해 목록에서 뺀다 (제출된 폰 보관함, 학교 장비 등). */
    private fun excludeCurrent() {
        val now = SystemClock.elapsedRealtime()
        val cur = devs.values.filter { now - it.lastSeen < 20_000 }.map { it.address }
        excluded.addAll(cur)
        toast("${cur.size}개 기기를 제외했습니다")
    }

    // ---------- 추적 ----------

    private fun startTracking(d: Dev) {
        d.peak = -127
        tracked = d
        graph.dev = d
        tTitle.text = "${d.label()}\n${d.address}"
        listPanel.visibility = View.GONE
        trackPanel.visibility = View.VISIBLE
        h.removeCallbacks(geiger)
        h.post(geiger)
    }

    private fun stopTracking() {
        tracked = null
        graph.dev = null
        h.removeCallbacks(geiger)
        trackPanel.visibility = View.GONE
        listPanel.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tracked != null) stopTracking() else super.onBackPressed()
    }

    /** 신호가 강할수록 진동(과 소리) 간격이 짧아진다: -95dBm 1.1초 → -35dBm 0.08초. */
    private val geiger = object : Runnable {
        override fun run() {
            val d = tracked ?: return
            val now = SystemClock.elapsedRealtime()
            var delay = 300L
            if (scanning && now - d.lastSeen < 2500) {
                val s = d.smoothed(now).coerceIn(-95, -35)
                val f = (s + 95) / 60f
                delay = (1100 - 1020 * f).toLong()
                if (soundOn) {
                    try {
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
                    } catch (_: Exception) {
                    }
                }
                try {
                    vib?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {
                }
            }
            h.postDelayed(this, delay)
        }
    }

    // ---------- 주기 갱신 ----------

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()

        // 안드로이드는 30분 넘게 돈 스캔을 저전력 모드로 강등하므로 20분마다 다시 건다
        if (scanning && now - scanStartedAt > 20 * 60 * 1000L) {
            scanner.stop()
            if (scanner.start()) scanStartedAt = now else scanning = false
            updateScanButton()
        }

        val active = devs.values.filter { it.address !in excluded && now - it.lastSeen < 20_000 }
        val sb = StringBuilder()
        sb.append(if (scanning) "● 스캔 중" else "○ 정지")
        sb.append(" · 감지 ${active.size}개 · 제외 ${excluded.size}개")
        lastError?.let { sb.append("\n").append(it) }
        if (scanning && !locationOn()) sb.append("\n위치 서비스가 꺼져 있으면 결과가 안 나올 수 있습니다. 위치를 켜세요.")
        status.text = sb

        val d = tracked
        if (d == null) {
            shown = active
                .filter { it.smoothed(now) >= minRssi }
                .sortedByDescending { if (now - it.lastSeen > 3000) it.smoothed(now) - 200 else it.smoothed(now) }
            listAdapter.notifyDataSetChanged()
            overview.devs = shown
            overview.minRssi = minRssi
            overview.invalidate()
            alertRising(now)
            return
        }

        val age = now - d.lastSeen
        if (age > 3000) {
            tRssi.text = "—"
            tRssi.setTextColor(0xFF9E9E9E.toInt())
            tBand.text = "신호 끊김 (${age / 1000}초 전)"
            tHint.text = "멀어졌거나 기기가 신호를 드물게 보냅니다. 잠시 멈춰서 기다려 보세요."
        } else {
            val s = d.smoothed(now)
            if (s > d.peak) d.peak = s
            val drop = d.peak - s
            tRssi.text = "$s"
            tRssi.setTextColor(
                when {
                    s >= -50 -> 0xFFD32F2F.toInt()
                    s >= -65 -> 0xFFF57C00.toInt()
                    else -> 0xFF1565C0.toInt()
                }
            )
            tBand.text = "dBm · ${Dev.band(s)}"
            tHint.text = when {
                drop <= 2 -> "▲ 지금이 최고점입니다. 이 근처입니다."
                drop >= 8 -> "지나쳤습니다. 최고점(${d.peak} dBm) 지점으로 돌아가세요."
                else -> "최고점 ${d.peak} dBm · 현재 ${drop} dB 낮음"
            }
        }
        graph.invalidate()
    }

    /** 새로 급상승하기 시작한 기기가 있으면 짧게 두 번 진동 (기기당 6초에 한 번). */
    private fun alertRising(now: Long) {
        if (!scanning) return
        var fire = false
        for (d in shown) {
            if (d.rise(now) < Dev.RISE_DB) continue
            val last = riseAlerted[d.address] ?: 0L
            if (now - last > 6000) {
                riseAlerted[d.address] = now
                fire = true
            }
        }
        if (fire) {
            try {
                vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        scanner.stop()
        tone?.release()
        super.onDestroy()
    }

    // ---------- 목록 어댑터 ----------

    private class Row(val title: TextView, val sub: TextView, val bar: ProgressBar)

    private inner class DevAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: LinearLayout
            val row: Row
            if (convertView == null) {
                view = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                }
                val title = TextView(this@MainActivity).apply {
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                }
                val sub = TextView(this@MainActivity).apply { textSize = 13f }
                val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 70
                }
                view.addView(title)
                view.addView(sub)
                view.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                row = Row(title, sub, bar)
                view.tag = row
            } else {
                view = convertView as LinearLayout
                row = view.tag as Row
            }
            val d = shown[position]
            val now = SystemClock.elapsedRealtime()
            val s = d.smoothed(now)
            val age = (now - d.lastSeen) / 1000
            val rise = d.rise(now)
            val hot = rise >= Dev.RISE_DB
            row.title.text = (if (hot) "▲+$rise 급상승 · " else "") + d.label()
            row.title.setTextColor(if (hot || d.isGlasses()) 0xFFD32F2F.toInt() else 0xFF212121.toInt())
            row.sub.text = "$s dBm · ${Dev.band(s)} · ${d.address}" + if (age >= 3) " · ${age}초 전" else ""
            row.bar.progress = (s + 100).coerceIn(0, 70)
            return view
        }
    }
}
