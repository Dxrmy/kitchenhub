package com.kyle.kitchenhub.ui.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.kyle.kitchenhub.core.ui.FontCache
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object XMorphFaceRenderer {

    private val cachedFaces = mutableListOf<Pair<String, JSONObject>>()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun listNames(context: Context): List<String> {
        ensureLoaded(context)
        return cachedFaces.map { it.first }
    }

    fun getFaceName(context: Context, index: Int): String {
        ensureLoaded(context)
        if (cachedFaces.isEmpty()) return "XMorph"
        return cachedFaces[((index % cachedFaces.size) + cachedFaces.size) % cachedFaces.size].first
    }

    fun getFace(context: Context, index: Int): JSONObject? {
        ensureLoaded(context)
        if (cachedFaces.isEmpty()) return null
        return cachedFaces[((index % cachedFaces.size) + cachedFaces.size) % cachedFaces.size].second
    }

    private fun ensureLoaded(context: Context) {
        if (cachedFaces.isNotEmpty()) return
        val files = context.assets.list("clock_faces")?.sorted() ?: return
        for (f in files) {
            if (!f.endsWith(".json")) continue
            runCatching {
                val raw = context.assets.open("clock_faces/$f").bufferedReader().use { it.readText() }
                if (raw.isNotBlank()) {
                    val json = JSONObject(raw)
                    cachedFaces.add(json.optString("name", f.removeSuffix(".json")) to json)
                }
            }
        }
    }

    fun drawFace(
        context: Context,
        canvas: Canvas,
        faceJson: JSONObject,
        w: Float,
        h: Float,
        compact: Boolean,
        nightMode: Boolean,
        c1: Int,
        c2: Int,
        faceIndex: Int = 0,
        animSec: Float = 0f
    ) {
        val now = Date()
        val cal = Calendar.getInstance().apply { time = now }
        val hh = SimpleDateFormat("HH", Locale.getDefault()).format(now)
        val mm = SimpleDateFormat("mm", Locale.getDefault()).format(now)

        val layerMap = HashMap<String, JSONObject>()
        val layers = faceJson.optJSONArray("layers")
        if (layers != null) {
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                layerMap[l.optString("id")] = l
            }
        }

        val rootSpec = faceJson.optJSONObject(if (compact && faceJson.has("small")) "small" else "large") ?: return
        val category = faceJson.optString("category", "")
        val safeTop = if (compact) h * 0.04f else h * 0.12f
        val safeBottomPad = if (compact) h * 0.04f else h * 0.07f
        val availH = (h - safeTop - safeBottomPad).coerceAtLeast(100f)

        if (rootSpec.has("stack")) {
            val stackObj = rootSpec.getJSONObject("stack")
            val stackH = availH * 0.94f
            val stackW = if (category == "analog") stackH else minOf(w * 0.80f, stackH * 1.38f)
            val originX = (w - stackW) * 0.5f
            val originY = safeTop + (availH - stackH) * 0.5f
            drawStack(
                context, canvas, stackObj, layerMap,
                originX, originY, stackW, stackH,
                hh, mm, now, cal, nightMode, c1, c2
            )
            return
        }

        val treeNode = when {
            rootSpec.has("column") -> "column" to rootSpec.getJSONObject("column")
            rootSpec.has("row") -> "row" to rootSpec.getJSONObject("row")
            else -> null
        } ?: return

        val rawSize = measureNode(context, treeNode.first, treeNode.second, layerMap, availH, hh, mm, now, nightMode)
        if (rawSize.first <= 0f || rawSize.second <= 0f) return

        val maxAllowedW = w * 0.88f
        val maxAllowedH = availH * 0.94f
        val fitScale = minOf(1f, maxAllowedW / rawSize.first, maxAllowedH / rawSize.second)
        val effH = availH * fitScale
        val fitSize = measureNode(context, treeNode.first, treeNode.second, layerMap, effH, hh, mm, now, nightMode)

        val anchorFracX = rootSpec.optDouble("x", 0.5).toFloat()
        val anchorX = w * anchorFracX
        val startX = when (rootSpec.optString("anchor", "CENTER")) {
            "START" -> anchorX
            "END" -> anchorX - fitSize.first
            else -> anchorX - fitSize.first * 0.5f
        }
        val startY = safeTop + (availH - fitSize.second) * 0.5f

        drawFlowNode(
            context, canvas, treeNode.first, treeNode.second, layerMap,
            startX, startY, fitSize.first, fitSize.second,
            effH, hh, mm, now, cal, nightMode, c1, c2
        )
    }

    private fun drawStack(
        context: Context,
        canvas: Canvas,
        stackObj: JSONObject,
        layers: Map<String, JSONObject>,
        originX: Float,
        originY: Float,
        stackW: Float,
        stackH: Float,
        hh: String,
        mm: String,
        now: Date,
        cal: Calendar,
        nightMode: Boolean,
        c1: Int,
        c2: Int
    ) {
        val children = stackObj.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            val ch = children.getJSONObject(i)
            val layer = layers[ch.optString("layer")] ?: continue
            val type = layer.optString("type")
            val sizeFrac = ch.optDouble("size", 0.2).toFloat()
            val fx = ch.optDouble("x", 0.5).toFloat()
            val fy = ch.optDouble("y", 0.5).toFloat()
            val anchor = ch.optString("anchor", "CENTER")
            val rot = ch.optDouble("rotation", 0.0).toFloat()

            if (type == "HAND_HOUR" || type == "HAND_MINUTE") {
                val boxSize = sizeFrac * stackH
                val cx = originX + fx * stackW
                val cy = originY + fy * stackH + boxSize * 0.5f
                drawAnalogHand(canvas, layer, type, cx, cy, stackH, cal, nightMode, c1, c2)
                continue
            }

            val txt = resolveText(layer, hh, mm, now)
            val tracking = ch.optDouble("tracking", layer.optDouble("tracking", 0.0)).toFloat()
            configurePaint(context, fillPaint, layer, sizeFrac, stackH, tracking, nightMode)

            val tw = fillPaint.measureText(txt)
            val capH = -fillPaint.ascent() * 0.74f
            val px = originX + fx * stackW
            val py = originY + fy * stackH

            val drawCenterX = when (anchor) {
                "START" -> px + tw * 0.5f
                "END" -> px - tw * 0.5f
                else -> px
            }
            val baselineY = py + capH

            renderStyledText(
                canvas, layer, txt, drawCenterX, py, baselineY, capH,
                rot, nightMode, c1, c2
            )
        }
    }

    private fun drawAnalogHand(
        canvas: Canvas,
        layer: JSONObject,
        type: String,
        cx: Float,
        cy: Float,
        refH: Float,
        cal: Calendar,
        nightMode: Boolean,
        c1: Int,
        c2: Int
    ) {
        val sec = cal.get(Calendar.SECOND) + if (nightMode) 0f else cal.get(Calendar.MILLISECOND) / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        val deg = if (type == "HAND_HOUR") hr * 30f - 90f else min * 6f - 90f
        val rad = deg * PI / 180.0
        val len = layer.optDouble("handLength", if (type == "HAND_HOUR") 0.26 else 0.40).toFloat() * refH
        val wght = if (nightMode) layer.optInt("sleepWeight", 250) else layer.optInt("weight", 500)
        handPaint.strokeWidth = (wght / 55f).coerceIn(4f, 16f)
        handPaint.color = resolveFillColor(layer.optString("color"), nightMode, c1, c2)

        canvas.drawLine(
            cx, cy,
            cx + (cos(rad) * len).toFloat(),
            cy + (sin(rad) * len).toFloat(),
            handPaint
        )
        fillPaint.shader = null
        fillPaint.color = c1
        canvas.drawCircle(cx, cy, 7f, fillPaint)
    }

    private fun resolveText(layer: JSONObject, hh: String, mm: String, now: Date): String {
        val raw = when (layer.optString("type")) {
            "HOURS" -> hh
            "MINUTES" -> mm
            "HOUR_TENS" -> hh.getOrNull(0)?.toString() ?: "0"
            "HOUR_ONES" -> hh.getOrNull(1)?.toString() ?: "0"
            "MINUTE_TENS" -> mm.getOrNull(0)?.toString() ?: "0"
            "MINUTE_ONES" -> mm.getOrNull(1)?.toString() ?: "0"
            "COLON" -> ":"
            "DATE" -> {
                val fmt = layer.optString("format", "EEE d MMM")
                runCatching { SimpleDateFormat(fmt, Locale.getDefault()).format(now) }
                    .getOrDefault(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now))
            }
            "TEXT" -> layer.optString("format", layer.optString("text", ""))
            else -> layer.optString("text", layer.optString("format", ""))
        }
        return if (layer.optBoolean("caps", false)) raw.uppercase(Locale.getDefault()) else raw
    }

    private fun resolveTypeface(context: Context, fontRef: String): Typeface {
        val lower = fontRef.lowercase(Locale.US)
        return when {
            lower.contains("dynapuff") -> FontCache.dynaPuff(context)
            lower.contains("bagel_fat") -> FontCache.bagelFat(context)
            lower.contains("oswald") -> FontCache.oswald(context)
            lower.contains("anton") -> FontCache.anton(context)
            lower.contains("space_grotesk") -> FontCache.spaceGrotesk(context)
            lower.contains("inter") -> FontCache.inter(context)
            else -> FontCache.robotoFlex(context)
        }
    }

    private fun configurePaint(
        context: Context,
        paint: Paint,
        layer: JSONObject,
        sizeFrac: Float,
        h: Float,
        tracking: Float,
        nightMode: Boolean
    ) {
        val fontRef = layer.optString("font", "")
        val lower = fontRef.lowercase(Locale.US)
        paint.typeface = resolveTypeface(context, fontRef)
        paint.textSize = (sizeFrac * h).coerceAtLeast(16f)
        paint.letterSpacing = tracking

        val wght = (if (nightMode) layer.optInt("sleepWeight", layer.optInt("weight", 500)) else layer.optInt("weight", 600))
            .coerceIn(100, 950)
        val wdth = layer.optInt("width", 100).coerceIn(50, 135)
        val opsz = parseQueryInt(fontRef, "opsz") ?: if (sizeFrac > 0.15f) 144 else 32

        paint.fontVariationSettings = when {
            lower.contains("dynapuff") -> "'wght' $wght, 'wdth' $wdth"
            lower.contains("oswald") || lower.contains("space_grotesk") -> "'wght' $wght"
            lower.contains("inter") -> "'wght' $wght, 'opsz' $opsz"
            lower.contains("bagel_fat") || lower.contains("anton") -> null
            else -> "'wght' $wght, 'wdth' $wdth, 'opsz' $opsz"
        }
    }

    private fun parseQueryInt(fontRef: String, key: String): Int? {
        val qIdx = fontRef.indexOf('?')
        if (qIdx < 0) return null
        val query = fontRef.substring(qIdx + 1)
        for (part in query.split('&')) {
            val kv = part.split('=')
            if (kv.size == 2 && kv[0].equals(key, ignoreCase = true)) {
                return kv[1].toIntOrNull()
            }
        }
        return null
    }

    private fun measureNode(
        context: Context,
        kind: String,
        node: JSONObject,
        layers: Map<String, JSONObject>,
        h: Float,
        hh: String,
        mm: String,
        now: Date,
        nightMode: Boolean
    ): Pair<Float, Float> {
        if (kind == "layer") {
            val layer = layers[node.optString("layer")] ?: return 0f to 0f
            val txt = resolveText(layer, hh, mm, now)
            val tracking = node.optDouble("tracking", layer.optDouble("tracking", 0.0)).toFloat()
            configurePaint(context, fillPaint, layer, node.optDouble("size", 0.2).toFloat(), h, tracking, nightMode)
            val w = fillPaint.measureText(txt)
            val isDate = layer.optString("type") == "DATE"
            val th = -fillPaint.ascent() * (if (isDate) 0.90f else 0.74f)
            return w to th
        }
        val children = node.optJSONArray("children") ?: return 0f to 0f
        var gap = node.optDouble("gap", 0.0).toFloat() * h
        var maxW = 0f; var maxH = 0f; var sumW = 0f; var sumH = 0f
        for (i in 0 until children.length()) {
            val ch = children.getJSONObject(i)
            if (kind == "column" && i > 0 && ch.has("layer")) {
                val l = layers[ch.optString("layer")]
                if (l?.optString("effect") == "OVERLAP") {
                    gap = minOf(gap, -0.075f * h)
                }
            }
            val (cw, chH) = when {
                ch.has("layer") -> measureNode(context, "layer", ch, layers, h, hh, mm, now, nightMode)
                ch.has("column") -> measureNode(context, "column", ch.getJSONObject("column"), layers, h, hh, mm, now, nightMode)
                ch.has("row") -> measureNode(context, "row", ch.getJSONObject("row"), layers, h, hh, mm, now, nightMode)
                else -> 0f to 0f
            }
            maxW = maxOf(maxW, cw); maxH = maxOf(maxH, chH)
            sumW += cw + if (i > 0) gap else 0f
            sumH += chH + if (i > 0) gap else 0f
        }
        return if (kind == "column") maxW to sumH else sumW to maxH
    }

    private fun drawFlowNode(
        context: Context,
        canvas: Canvas,
        kind: String,
        node: JSONObject,
        layers: Map<String, JSONObject>,
        x: Float,
        y: Float,
        boxW: Float,
        boxH: Float,
        h: Float,
        hh: String,
        mm: String,
        now: Date,
        cal: Calendar,
        nightMode: Boolean,
        c1: Int,
        c2: Int
    ) {
        if (kind == "layer") {
            val layer = layers[node.optString("layer")] ?: return
            val txt = resolveText(layer, hh, mm, now)
            val sizeFrac = node.optDouble("size", 0.2).toFloat()
            val tracking = node.optDouble("tracking", layer.optDouble("tracking", 0.0)).toFloat()
            configurePaint(context, fillPaint, layer, sizeFrac, h, tracking, nightMode)

            val offX = node.optDouble("x", 0.0).toFloat() * h
            val offY = node.optDouble("y", 0.0).toFloat() * h
            val rot = node.optDouble("rotation", 0.0).toFloat()
            val cx = x + boxW * 0.5f + offX
            val isDate = layer.optString("type") == "DATE"
            val capH = -fillPaint.ascent() * (if (isDate) 0.88f else 0.74f)
            val baseLine = y + capH + offY

            renderStyledText(canvas, layer, txt, cx, y + offY, baseLine, boxH, rot, nightMode, c1, c2)
            return
        }

        val children = node.optJSONArray("children") ?: return
        var gap = node.optDouble("gap", 0.0).toFloat() * h
        var curX = x
        var curY = y
        for (i in 0 until children.length()) {
            val ch = children.getJSONObject(i)
            if (kind == "column" && i > 0 && ch.has("layer")) {
                val l = layers[ch.optString("layer")]
                if (l?.optString("effect") == "OVERLAP") {
                    gap = minOf(gap, -0.075f * h)
                }
            }
            val (cKind, cObj) = when {
                ch.has("layer") -> "layer" to ch
                ch.has("column") -> "column" to ch.getJSONObject("column")
                ch.has("row") -> "row" to ch.getJSONObject("row")
                else -> continue
            }
            val (cw, chH) = measureNode(context, cKind, cObj, layers, h, hh, mm, now, nightMode)
            val drawX = if (kind == "column") x + (boxW - cw) * 0.5f else curX
            val drawY = if (kind == "row") y + (boxH - chH) * 0.5f else curY
            drawFlowNode(context, canvas, cKind, cObj, layers, drawX, drawY, cw, chH, h, hh, mm, now, cal, nightMode, c1, c2)
            if (kind == "column") curY += chH + gap else curX += cw + gap
        }
    }

    private fun renderStyledText(
        canvas: Canvas,
        layer: JSONObject,
        txt: String,
        cx: Float,
        topY: Float,
        baselineY: Float,
        boxH: Float,
        rot: Float,
        nightMode: Boolean,
        c1: Int,
        c2: Int
    ) {
        val effect = layer.optString("effect", "")
        val isGlass = effect == "GLASS"
        val isOverlap = effect == "OVERLAP"
        val isOutline = layer.optBoolean("outline", false) || (nightMode && layer.optString("sleepStyle") == "OUTLINE")
        val fillCol = resolveFillColor(layer.optString("color"), nightMode, c1, c2)

        canvas.save()
        if (rot != 0f) canvas.rotate(rot, cx, topY + boxH * 0.5f)
        fillPaint.textAlign = Paint.Align.CENTER

        val sw = layer.optDouble("strokeWidth", 0.0).toFloat()
        if (isOutline) {
            strokePaint.typeface = fillPaint.typeface
            strokePaint.textSize = fillPaint.textSize
            strokePaint.letterSpacing = fillPaint.letterSpacing
            strokePaint.fontVariationSettings = fillPaint.fontVariationSettings
            strokePaint.textAlign = Paint.Align.CENTER
            strokePaint.strokeWidth = 0.028f * fillPaint.textSize
            strokePaint.color = fillCol
            canvas.drawText(txt, cx, baselineY, strokePaint)
            canvas.restore()
            return
        }

        if (sw > 0f || isGlass) {
            strokePaint.typeface = fillPaint.typeface
            strokePaint.textSize = fillPaint.textSize
            strokePaint.letterSpacing = fillPaint.letterSpacing
            strokePaint.fontVariationSettings = fillPaint.fontVariationSettings
            strokePaint.textAlign = Paint.Align.CENTER
            strokePaint.strokeWidth = (if (sw > 0f) sw else 0.018f) * fillPaint.textSize
            strokePaint.color = when {
                nightMode -> Color.parseColor("#33FF2D20")
                isGlass -> Color.parseColor("#99FFFFFF")
                else -> Color.parseColor("#CC08090C")
            }
            canvas.drawText(txt, cx, baselineY, strokePaint)
        }

        when {
            isGlass && !nightMode -> {
                fillPaint.shader = LinearGradient(
                    cx, topY, cx, topY + boxH,
                    intArrayOf(
                        Color.argb(240, 255, 255, 255),
                        Color.argb(185, Color.red(c1), Color.green(c1), Color.blue(c1)),
                        Color.argb(215, Color.red(c2), Color.green(c2), Color.blue(c2))
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                fillPaint.alpha = 235
            }
            isOverlap && !nightMode -> {
                fillPaint.shader = LinearGradient(cx, topY, cx, topY + boxH, c1, c2, Shader.TileMode.CLAMP)
                fillPaint.alpha = 220
            }
            else -> {
                fillPaint.shader = null
                fillPaint.color = fillCol
            }
        }

        canvas.drawText(txt, cx, baselineY, fillPaint)
        fillPaint.shader = null
        fillPaint.alpha = 255
        canvas.restore()
    }

    private fun resolveFillColor(colorKey: String, nightMode: Boolean, c1: Int, c2: Int): Int {
        if (nightMode) {
            return if (colorKey.contains("accent3") || colorKey.contains("neutral1_300")) c2 else c1
        }
        return when {
            colorKey.contains("accent3") -> c2
            colorKey.contains("accent1") -> c1
            colorKey.contains("neutral1_300") -> Color.parseColor("#9AA3B2")
            colorKey.contains("neutral") -> Color.parseColor("#EEF2FA")
            else -> c1
        }
    }
}
