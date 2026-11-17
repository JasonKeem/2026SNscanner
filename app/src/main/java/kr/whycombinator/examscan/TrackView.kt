package kr.whycombinator.examscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/** 추적 중인 기기의 최근 20초 RSSI 그래프. 빨간 선은 최고점. */
class TrackView(ctx: Context) : View(ctx) {
    var dev: Dev? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val peakPaint = Paint().apply {
        color = 0xFFD32F2F.toInt()
        strokeWidth = 3f
    }
    private val grid = Paint().apply {
        color = 0x22000000
        strokeWidth = 1f
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val d = dev ?: return
        val w = width.toFloat()
        val hgt = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val span = 20_000f

        fun y(r: Int) = hgt * (1f - (r.coerceIn(-100, -30) + 100) / 70f)

        for (g in intArrayOf(-90, -70, -50)) c.drawLine(0f, y(g), w, y(g), grid)

        path.reset()
        var first = true
        for (s in d.history) {
            val age = (now - s.t).toFloat()
            if (age > span) continue
            val x = w * (1f - age / span)
            if (first) {
                path.moveTo(x, y(s.rssi))
                first = false
            } else {
                path.lineTo(x, y(s.rssi))
            }
        }
        c.drawPath(path, line)
        if (d.peak > -127) c.drawLine(0f, y(d.peak), w, y(d.peak), peakPaint)
    }
}
