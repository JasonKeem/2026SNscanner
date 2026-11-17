package kr.whycombinator.examscan

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class Sample(val t: Long, val rssi: Float)

/** 폰 수신 감도 보정값(dB). '1m 보정'으로 정해지고 모든 기기의 거리 계산에 더해진다. */
object Cal {
    var offset = 0f
}

/** 광고 패킷으로 관측된 BLE 발신원 하나. 키는 MAC 주소(랜덤 주소 포함). */
class Dev(val address: String) {
    var name: String? = null
    var vendorId: Int = -1
    val uuids = LinkedHashSet<String>()
    var txPower: Int = TX_UNKNOWN      // 광고에 실린 송신 출력(dBm). 없으면 0dBm으로 가정
    var lastRssi = -127
    var lastSeen = 0L
    var peak = -127
    var packets = 0
    var inRange = false

    val history = ArrayDeque<Sample>() // 필터를 거친 값, 그래프용 30초

    // --- 칼만 필터 상태 (상태 = RSSI dBm, 랜덤워크 모델) ---
    private var x = -127.0
    private var p = R_BASE
    private var init = false
    private var upRun = 0

    /**
     * 측정 한 건을 필터에 넣는다.
     * - 폰이 움직이는 중이면 과정 잡음을 키워 빠르게 따라가고, 멈춰 있으면 줄여 강하게 평활한다.
     * - 값이 갑자기 꺼지는 쪽(다중경로 페이딩)은 덜 믿고, 한 번뿐인 급등도 덜 믿는다.
     */
    fun add(t: Long, rssi: Int, moving: Boolean) {
        packets++
        if (!init || t - lastSeen > 8000) {
            x = rssi.toDouble()
            p = R_BASE
            init = true
            upRun = 0
        } else {
            val dt = (t - lastSeen).coerceIn(1, 3000) / 1000.0
            p += (if (moving) Q_MOVING else Q_STILL) * dt
            val innov = rssi - x
            var r = R_BASE
            if (innov < -6.0) r = R_DIP else if (innov > 10.0 && upRun == 0) r = R_SPIKE
            upRun = if (innov > 6.0) upRun + 1 else 0
            val k = p / (p + r)
            x += k * innov
            p *= (1 - k)
        }
        lastRssi = rssi
        lastSeen = t
        history.addLast(Sample(t, x.toFloat()))
        while (history.isNotEmpty() && t - history.first().t > 30_000) history.removeFirst()
    }

    fun value(): Double = x
    fun smoothed(@Suppress("UNUSED_PARAMETER") now: Long): Int = x.roundToInt()

    /** 1m에서 기대되는 RSSI. */
    fun ref1m(): Double = (if (txPower != TX_UNKNOWN) txPower else 0) + REF_1M + Cal.offset

    /** 로그-거리 경로손실 모델: d = 10^((P1m − RSSI) / (10·n)) */
    fun distOf(rssi: Double): Double = 10.0.pow((ref1m() - rssi) / (10 * PATH_N))
    fun dist(): Double = distOf(x)

    /** 반경 안/밖 판정. 경계에서 깜빡이지 않도록 나갈 때는 반경의 1.3배를 넘어야 한다. */
    fun updateRange(radius: Double): Boolean {
        val d = dist()
        inRange = if (inRange) d <= radius * 1.3 else d <= radius
        return inRange
    }

    /** 최근 약 4초 동안 신호가 몇 dB 올랐는지. 4초 전 기록이 없거나 신호가 끊겼으면 0. */
    fun rise(now: Long): Int {
        if (now - lastSeen > 3000) return 0
        var old: Sample? = null
        for (s in history) {
            val age = now - s.t
            if (age in RISE_WINDOW_MS..(RISE_WINDOW_MS * 2)) old = s
            if (age < RISE_WINDOW_MS) break
        }
        val o = old ?: return 0
        return (x - o.rssi).roundToInt()
    }

    fun shortLabel(): String {
        val base = name ?: Vendors.names[vendorId] ?: address.takeLast(5)
        return if (base.length > 10) base.take(10) else base
    }

    fun isGlasses(): Boolean {
        if (vendorId in Vendors.glassesIds) return true
        if (uuids.any { u -> Vendors.glassesUuidPrefixes.any { u.startsWith(it) } }) return true
        val n = name?.lowercase() ?: return false
        return Vendors.glassesWords.any { n.contains(it) }
    }

    fun label(): String {
        val v = Vendors.names[vendorId]
        val n = name
        val base = when {
            n != null && v != null -> "$n ($v)"
            n != null -> n
            v != null -> "$v 기기"
            vendorId >= 0 -> "제조사 0x%04X".format(vendorId)
            else -> "미확인 기기"
        }
        return if (isGlasses()) "⚠ 스마트안경 의심 · $base" else base
    }

    companion object {
        const val TX_UNKNOWN = Int.MIN_VALUE
        const val REF_1M = -59.0       // 송신 0dBm 기기를 1m에서 받았을 때의 통상값
        const val PATH_N = 2.2         // 실내 가시거리 경로손실 지수
        const val Q_MOVING = 6.0       // dB²/s
        const val Q_STILL = 0.5
        const val R_BASE = 16.0        // 측정 잡음 분산 (σ≈4dB)
        const val R_DIP = 120.0
        const val R_SPIKE = 80.0
        const val RISE_WINDOW_MS = 4000L
        const val RISE_DB = 8          // 4초에 8dB(거리로는 절반 이하) 이상 가까워지면 급접근
        const val MIN_M = 0.3
        const val MAX_M = 12.0

        fun fmt(d: Double): String = if (d >= 10) "10m+" else "%.1fm".format(d)

        /** 그래프 세로 위치: 0(위, 0.3m) ~ 1(아래, 12m), 로그 눈금. */
        fun yFrac(d: Double): Float {
            val c = d.coerceIn(MIN_M, MAX_M)
            return ((log10(c) - log10(MIN_M)) / (log10(MAX_M) - log10(MIN_M))).toFloat()
        }
    }
}

/**
 * Bluetooth SIG 회사 식별자. 스마트안경 관련 ID와 UUID는 공개 자료 기준 추정값이므로
 * 실물 기기로 반드시 검증하고 갱신해야 한다.
 */
object Vendors {
    val names = mapOf(
        0x004C to "Apple",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0006 to "Microsoft",
        0x01AB to "Meta",
        0x058E to "Meta(Oculus)",
        0x0D53 to "Luxottica",
        0x03C2 to "Snap",
        0x038F to "Xiaomi",
        0x027D to "Huawei",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x012D to "Sony",
        0x0059 to "Nordic",
    )
    val glassesIds = setOf(0x01AB, 0x058E, 0x0D53, 0x03C2)
    val glassesUuidPrefixes = listOf("0000fd5f", "0000feb7", "0000feb8")
    val glassesWords = listOf(
        "glass", "ray-ban", "rayban", "rokid", "xreal", "rayneo",
        "inmo", "spectacles", "oakley", "looktech", "even g",
    )
}
