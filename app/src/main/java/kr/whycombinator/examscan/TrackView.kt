package kr.whycombinator.examscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/** 추적 중인 기기의 최근 20초 추정 거리 그래프(위쪽이 가까움). 빨간 선은 최근접 지점. */
class TrackView(ctx: Context) : View(ctx) {
    var dev: Dev? = null

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        strokeWidth = 3 * d
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val peakPaint = Paint().apply {
        color = 0xFFD32F2F.toInt()
        strokeWidth = 1.5f * d
    }
    private val grid = Paint().apply {
        color = 0x1F000000
        strokeWidth = 1f
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textSize = 10 * d
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val dv = dev ?: return
        val w = width.toFloat()
        val hgt = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val span = 20_000f

        for (m in doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0)) {
            val gy = hgt * Dev.yFrac(m)
            c.drawLine(0f, gy, w, gy, grid)
            c.drawText(Dev.fmt(m), 2 * d, gy - 2 * d, axis)
        }

        path.reset()
        var first = true
        for (s in dv.history) {
            val age = (now - s.t).toFloat()
            if (age > span) continue
            val x = w * (1f - age / span)
            val y = hgt * Dev.yFrac(dv.distOf(s.rssi.toDouble()))
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        c.drawPath(path, line)
        if (dv.peak > -127) {
            val py = hgt * Dev.yFrac(dv.distOf(dv.peak.toDouble()))
            c.drawLine(0f, py, w, py, peakPaint)
        }
    }
}
