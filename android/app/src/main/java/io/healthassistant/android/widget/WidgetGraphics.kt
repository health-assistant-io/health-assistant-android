package io.healthassistant.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.healthassistant.shared.data.RangeStatus

/**
 * M4 (widgets) — the canvas renderers for the visuals RemoteViews cannot
 * express: Glance's `CircularProgressIndicator` has no determinate progress,
 * so the Single metric widget's value-vs-range ring is drawn here into a
 * bitmap and handed to Glance as an [androidx.glance.appwidget.Image].
 * Colors come from [WidgetTheme] (the app's design tokens, resolved for the
 * current night mode); geometry is sized to the widget's actual box.
 */
object WidgetGraphics {
    /** A progress ring: full track + a rounded sweep arc for [progress]
     * (null renders the track-only indeterminate look — no range to place
     * the value in). [status] colors the arc with the health-semantic token. */
    fun ringBitmap(
        context: Context,
        sizePx: Int,
        progress: Float?,
        status: RangeStatus?,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = (size / 9f).coerceAtLeast(4f)
        val inset = stroke / 2f + 1f
        val bounds = RectF(inset, inset, size - inset, size - inset)
        canvas.drawArc(
            bounds,
            START_ANGLE,
            FULL_SWEEP,
            false,
            Paint(PAINT_FLAGS).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = WidgetTheme.trackArgb(context)
            },
        )
        if (progress != null) {
            canvas.drawArc(
                bounds,
                START_ANGLE,
                FULL_SWEEP * progress.coerceIn(0f, 1f),
                false,
                Paint(PAINT_FLAGS).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                    color = WidgetTheme.statusArgb(context, status)
                },
            )
        }
        return bitmap
    }

    private const val PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG
    private const val START_ANGLE = -90f
    private const val FULL_SWEEP = 360f

    /** A minimal last-hour sparkline: shaded reference band, a value polyline
     * and a dot on the newest point ([status] colors it). Inline by design —
     * InsightsChart is a Vico app-internal component and must not leak into
     * Glance. Handles the 0/1-point degenerate cases (blank / flat line). */
    fun sparklineBitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        values: List<Double>,
        bandLow: Double?,
        bandHigh: Double?,
        status: RangeStatus?,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (values.isEmpty()) return bitmap
        val pad = height / 8f
        val drawing = RectF(0f, pad, width.toFloat(), height - pad)
        val min = minOf(values.min(), bandLow ?: Double.MAX_VALUE)
        val max = maxOf(values.max(), bandHigh ?: -Double.MAX_VALUE)
        val span = (max - min).takeIf { it > 0 } ?: 1.0

        fun x(index: Int): Float = if (values.size == 1) drawing.centerX() else drawing.left + index * drawing.width() / (values.size - 1)

        fun y(value: Double): Float = drawing.bottom - ((value - min) / span * drawing.height()).toFloat()

        if (bandLow != null && bandHigh != null && bandHigh > bandLow) {
            val bandPaint =
                Paint(PAINT_FLAGS).apply {
                    style = Paint.Style.FILL
                    color = WidgetTheme.primaryArgb(context)
                    alpha = BAND_ALPHA
                }
            canvas.drawRect(RectF(drawing.left, y(bandHigh), drawing.right, y(bandLow)), bandPaint)
        }
        val linePaint =
            Paint(PAINT_FLAGS).apply {
                style = Paint.Style.STROKE
                strokeWidth = (height / 14f).coerceAtLeast(3f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = WidgetTheme.primaryArgb(context)
            }
        val path =
            android.graphics.Path().apply {
                moveTo(x(0), y(values.first()))
                for (index in 1 until values.size) lineTo(x(index), y(values[index]))
            }
        canvas.drawPath(path, linePaint)
        val dotRadius = (height / 12f).coerceAtLeast(3f)
        canvas.drawCircle(
            x(values.lastIndex),
            y(values.last()),
            dotRadius,
            Paint(PAINT_FLAGS).apply {
                style = Paint.Style.FILL
                color = WidgetTheme.statusArgb(context, status)
            },
        )
        return bitmap
    }

    private const val BAND_ALPHA = 38
}
