package kr.whycombinator.examscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 원형 레이더. 중심이 내 폰, 위쪽이 폰이 향한 방향, 중심에서의 거리가 추정 거리.
 * 방향을 아직 모르는 기기는 그 거리의 원(링)으로, 방향이 추정된 기기는 점과 호(오차 범위)로 그린다.
 */
class RadarView(ctx: Context) : View(ctx) {
    var devs: List<Dev> = emptyList()
    var radius = 1.0
    var heading = Float.NaN          // 폰이 향한 방위(도). 센서가 없으면 NaN

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000
        strokeWidth = 1f * d
    }
    private val rangeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2E7D32.toInt()
        strokeWidth = 2f * d
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * d
        color = 0xFF757575.toInt()
    }
    private val oval = RectF()
    private val palette = intArrayOf(
        0xFF1565C0.toInt(), 0xFF6A1B9A.toInt(), 0xFF00838F.toInt(), 0xFF4E342E.toInt(),
        0xFF37474F.toInt(), 0xFF827717.toInt(), 0xFFAD1457.toInt(), 0xFF283593.toInt(),
    )
    private val hotColor = 0xFFD32F2F.toInt()

    override fun onDraw(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val cx = width / 2f
        val cy = height / 2f
        val rMax = min(width, height) / 2f - 14 * d
        if (rMax <= 0) return
        val range = radius * 2.5                 // 화면 끝 = 감지 반경의 2.5배
        val scale = rMax / range.toFloat()

        // 배경 눈금
        fill.color = 0x0A2E7D32
        c.drawCircle(cx, cy, rMax, fill)
        c.drawCircle(cx, cy, rMax, ring)
        c.drawCircle(cx, cy, rMax * 0.5f, ring)
        c.drawLine(cx - rMax, cy, cx + rMax, cy, ring)
        c.drawLine(cx, cy - rMax, cx, cy + rMax, ring)
        val rr = (radius * scale).toFloat()
        fill.color = 0x142E7D32
        c.drawCircle(cx, cy, rr, fill)
        c.drawCircle(cx, cy, rr, rangeRing)
        c.drawText(Dev.fmt(radius), cx + 3 * d, cy - rr - 3 * d, small)
        c.drawText(Dev.fmt(range), cx + 3 * d, cy - rMax + 11 * d, small)
        c.drawText("앞", cx - 5 * d, cy - rMax - 2 * d, small)

        // 회전하는 스윕
        val sweep = (now % 3000L) / 3000f * 360f
        oval.set(cx - rMax, cy - rMax, cx + rMax, cy + rMax)
        fill.color = 0x182E7D32
        c.drawArc(oval, sweep - 90f - 40f, 40f, true, fill)

        // 내 위치
        fill.color = 0xFF2E7D32.toInt()
        c.drawCircle(cx, cy, 4 * d, fill)

        var unknown = 0
        val near = devs.filter { now - it.lastSeen < 8000 && it.dist() <= range }.sortedBy { it.dist() }.take(14)
        for (dev in near.reversed()) {           // 먼 것부터 그려 가까운 것이 위에 오게
            val dist = dev.dist()
            val r = (dist * scale).toFloat().coerceAtLeast(6 * d)
            val inside = dev.inRange
            val hot = dev.rise(now) >= Dev.RISE_DB
            val h = dev.address.hashCode() and 0x7fffffff
            val color = if (hot || dev.isGlasses()) hotColor else palette[h % palette.size]
            val alpha = if (inside) 255 else 80
            val b = dev.bearing(now)
            val label = (if (hot) "▲ " else "") + dev.shortLabel() + " " + Dev.fmt(dist)
            text.color = color
            text.alpha = alpha
            text.typeface = if (inside) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            if (b.isNaN() || heading.isNaN()) {
                // 방향 미확인: 그 거리의 링
                unknown++
                stroke.color = color
                stroke.alpha = alpha
                stroke.strokeWidth = (if (inside) 3f else 1.5f) * d
                c.drawCircle(cx, cy, r, stroke)
                if (inside) {
                    val a = Math.toRadians((h % 360).toDouble())
                    c.drawText(label, cx + r * sin(a).toFloat() + 4 * d, cy - r * cos(a).toFloat(), text)
                }
            } else {
                // 방향 추정됨: 점 + 오차 호(±40°)
                val rel = b - heading
                val a = Math.toRadians(rel.toDouble())
                val px = cx + r * sin(a).toFloat()
                val py = cy - r * cos(a).toFloat()
                stroke.color = color
                stroke.alpha = alpha / 2
                stroke.strokeWidth = 3f * d
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, rel - 90f - 40f, 80f, false, stroke)
                fill.color = color
                fill.alpha = alpha
                c.drawCircle(px, py, (if (inside) 8f else 5f) * d, fill)
                if (inside) c.drawText(label, px + 10 * d, py + 4 * d, text)
            }
        }

        if (unknown > 0 && !heading.isNaN()) {
            c.drawText("원으로 보이는 기기는 방향 미확인 · 제자리에서 천천히 한 바퀴 돌면 점으로 바뀝니다", 4 * d, height - 4 * d, small)
        }
        postInvalidateOnAnimation()
    }
}
