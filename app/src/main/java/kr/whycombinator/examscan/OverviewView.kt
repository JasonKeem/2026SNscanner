package kr.whycombinator.examscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * 감지 반경 안에 들어온 모든 기기의 최근 20초 추정 거리를 한 화면에 겹쳐 그린다(위쪽이 가까움).
 * 빠르게 가까워지는 기기는 굵은 빨간 선으로 맨 위에 그려진다.
 */
class OverviewView(ctx: Context) : View(ctx) {
    var devs: List<Dev> = emptyList()
    var radius = 1.0

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply {
        color = 0x1F000000
        strokeWidth = 1f
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textSize = 10 * d
    }
    private val thr = Paint().apply {
        color = 0xFF757575.toInt()
        strokeWidth = 1.5f * d
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d }
    private val path = Path()
    private val palette = intArrayOf(
        0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(), 0xFF00838F.toInt(),
        0xFF4E342E.toInt(), 0xFF37474F.toInt(), 0xFF827717.toInt(), 0xFFAD1457.toInt(),
    )
    private val hotColor = 0xFFD32F2F.toInt()

    private class Lbl(var y: Float, val s: String, val color: Int, val hot: Boolean)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val hgt = height.toFloat()
        val pw = w - 118 * d                 // 오른쪽은 이름표 영역
        val now = SystemClock.elapsedRealtime()
        val span = 20_000f

        for (m in doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0)) {
            val gy = hgt * Dev.yFrac(m)
            c.drawLine(0f, gy, pw, gy, grid)
            c.drawText(Dev.fmt(m), 2 * d, gy - 2 * d, axis)
        }
        val ry = hgt * Dev.yFrac(radius)
        path.reset()
        path.moveTo(0f, ry)
        path.lineTo(pw, ry)
        c.drawPath(path, thr)

        if (devs.isEmpty()) {
            text.color = 0xFF9E9E9E.toInt()
            text.typeface = Typeface.DEFAULT
            c.drawText("반경 안에 잡히는 기기가 없습니다", 12 * d, hgt / 2, text)
            return
        }

        val labels = ArrayList<Lbl>()
        // 급상승이 아닌 기기를 먼저, 급상승 기기를 나중에 그려 위에 오게 한다
        for (dev in devs.sortedBy { it.rise(now) }) {
            val rise = dev.rise(now)
            val hot = rise >= Dev.RISE_DB
            val color = if (hot) hotColor else palette[(dev.address.hashCode() and 0x7fffffff) % palette.size]
            line.color = color
            line.alpha = if (hot) 255 else 150
            line.strokeWidth = if (hot) 4.5f * d else 1.8f * d

            path.reset()
            var first = true
            var lx = 0f
            var ly = 0f
            for (s in dev.history) {
                val age = (now - s.t).toFloat()
                if (age > span) continue
                lx = pw * (1f - age / span)
                ly = hgt * Dev.yFrac(dev.distOf(s.rssi.toDouble()))
                if (first) {
                    path.moveTo(lx, ly)
                    first = false
                } else {
                    path.lineTo(lx, ly)
                }
            }
            if (first) continue
            c.drawPath(path, line)
            dot.color = color
            c.drawCircle(lx, ly, if (hot) 5 * d else 3 * d, dot)

            labels.add(Lbl(ly, (if (hot) "▲ " else "") + dev.shortLabel() + " " + Dev.fmt(dev.dist()), color, hot))
        }

        // 이름표: 급접근 기기 전부 + 나머지는 위쪽(가까운 기기)부터, 최대 7개. 서로 겹치지 않게 밀어낸다.
        val picked = labels.sortedWith(compareBy<Lbl> { !it.hot }.thenBy { it.y }).take(7).sortedBy { it.y }
        val gap = 15 * d
        var prev = -gap
        for (l in picked) {
            if (l.y < prev + gap) l.y = prev + gap
            if (l.y < 12 * d) l.y = 12 * d
            prev = l.y
        }
        for (l in picked) {
            text.color = l.color
            text.typeface = if (l.hot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(l.s, pw + 8 * d, minOf(l.y + 4 * d, hgt - 2 * d), text)
        }
    }
}
