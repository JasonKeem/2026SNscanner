package kr.whycombinator.examscan

class Sample(val t: Long, val rssi: Int)

/** 광고 패킷으로 관측된 BLE 발신원 하나. 키는 MAC 주소(랜덤 주소 포함). */
class Dev(val address: String) {
    var name: String? = null
    var vendorId: Int = -1
    val uuids = LinkedHashSet<String>()
    var lastRssi = -127
    var lastSeen = 0L
    var peak = -127
    var packets = 0

    private val recent = ArrayDeque<Sample>()   // 평활용 최근 구간
    val history = ArrayDeque<Sample>()          // 그래프용 30초

    fun add(t: Long, rssi: Int) {
        lastRssi = rssi
        lastSeen = t
        packets++
        recent.addLast(Sample(t, rssi))
        trim(t)
        history.addLast(Sample(t, quantile()))
        while (history.isNotEmpty() && t - history.first().t > 30_000) history.removeFirst()
    }

    /** 최근 WINDOW 구간의 상위 75% 값. 순간적인 페이딩(값이 푹 꺼지는 현상)에 덜 흔들린다. */
    fun smoothed(now: Long): Int {
        trim(now)
        return quantile()
    }

    private fun trim(now: Long) {
        while (recent.size > 1 && now - recent.first().t > WINDOW_MS) recent.removeFirst()
    }

    private fun quantile(): Int {
        if (recent.isEmpty()) return lastRssi
        val a = recent.map { it.rssi }.sorted()
        return a[minOf(a.size - 1, a.size * 3 / 4)]
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
        const val WINDOW_MS = 1200L

        /** RSSI → 대략적인 거리대. 기기·자세·장애물에 따라 크게 달라지므로 참고용. */
        fun band(rssi: Int): String = when {
            rssi >= -50 -> "1m 이내"
            rssi >= -65 -> "1~3m"
            rssi >= -78 -> "3~8m"
            else -> "멀리 / 옆 교실"
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
