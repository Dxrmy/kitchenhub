package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.os.SystemClock
import android.util.AttributeSet

/**
 * Port of AOSP Material 3 RippleShader (radial ring + pixel sparkle noise burst on touch).
 * Lives directly inside each bento box / card so it automatically clips to the card's
 * rounded outline and scrolls 1:1 with the card. Sleeps at 0fps when idle.
 */
class M3RippleShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GlShaderTextureView(context, attrs) {

    @Volatile
    private var originX = 0.5f
    @Volatile
    private var originY = 0.5f
    @Volatile
    private var rippleStartMs = 0L
    @Volatile
    private var tintR = 0.72f
    @Volatile
    private var tintG = 0.83f
    @Volatile
    private var tintB = 1.0f
    @Volatile
    private var fadeDurationMs = 650f

    private var cachedProgram = -1
    private var uOrigLoc = -1
    private var uProgLoc = -1
    private var uColLoc = -1

    init {
        continuousRender = false
        setFragmentShader(FRAG_SHADER)
    }

    fun isRippleActive(): Boolean = continuousRender

    fun triggerRipple(px: Float, py: Float, color: Int = Color.parseColor("#B8D4FF")) {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        originX = (px / w).coerceIn(0f, 1f)
        originY = (1f - (py / h)).coerceIn(0f, 1f)
        fadeDurationMs = 650f
        tintR = Color.red(color) / 255f
        tintG = Color.green(color) / 255f
        tintB = Color.blue(color) / 255f
        rippleStartMs = SystemClock.uptimeMillis()
        continuousRender = true
        requestRenderFrame()
    }

    fun fadeOutFast() {
        if (!continuousRender) return
        if (fadeDurationMs > 180f) {
            val elapsed = (SystemClock.uptimeMillis() - rippleStartMs).coerceAtLeast(0L)
            fadeDurationMs = (elapsed + 120f).coerceAtLeast(140f)
        }
    }

    override fun onBindUniforms(program: Int, width: Int, height: Int, timeSec: Float) {
        if (program != cachedProgram) {
            cachedProgram = program
            uOrigLoc = GLES20.glGetUniformLocation(program, "uOrigin")
            uProgLoc = GLES20.glGetUniformLocation(program, "uProgress")
            uColLoc = GLES20.glGetUniformLocation(program, "uColor")
        }
        val elapsed = (SystemClock.uptimeMillis() - rippleStartMs) / fadeDurationMs
        val progress = elapsed.coerceIn(0f, 1f)
        if (elapsed >= 1f) {
            continuousRender = false
        }
        if (uOrigLoc >= 0) GLES20.glUniform2f(uOrigLoc, originX, originY)
        if (uProgLoc >= 0) GLES20.glUniform1f(uProgLoc, progress)
        if (uColLoc >= 0) GLES20.glUniform3f(uColLoc, tintR, tintG, tintB)
    }

    companion object {
        private const val FRAG_SHADER = """
            precision mediump float;
            uniform vec2 uResolution;
            uniform vec2 uOrigin;
            uniform float uProgress;
            uniform vec3 uColor;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                if (uProgress < 0.0 || uProgress >= 1.0) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
                float aspect = uResolution.x / max(uResolution.y, 1.0);
                float dist = length((uv - uOrigin) * vec2(aspect, 1.0));
                float maxR = 1.15 * uProgress;
                float ringWidth = 0.22 + 0.14 * uProgress;
                float ring = exp(-pow((dist - maxR) / ringWidth, 2.0) * 2.8);
                float fill = (1.0 - smoothstep(0.0, maxR + 0.10, dist)) * 0.28;
                float sparkle = smoothstep(0.58, 0.94, hash(floor(gl_FragCoord.xy * 0.30) + floor(uProgress * 12.0)));
                float fade = (1.0 - uProgress) * smoothstep(0.0, 0.04, uProgress + 0.02);
                float alpha = (ring * 0.42 + fill + (ring * 0.55 + fill * 0.7) * sparkle * 0.46) * fade;
                gl_FragColor = vec4(uColor + vec3(0.20) * sparkle, clamp(alpha, 0.0, 0.68));
            }
        """
    }
}
