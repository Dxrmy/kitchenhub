package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.util.AttributeSet

/**
 * Port of AOSP Material 3 TurbulenceNoiseShader (FBM simplex-like turbulence + soft cloud/sparkle blend)
 * running at 15fps over album cover art.
 */
class TurbulenceNoiseShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GlShaderTextureView(context, attrs) {

    override val bufferScale: Float get() = 0.35f

    @Volatile
    private var tintR = 0.66f
    @Volatile
    private var tintG = 0.78f
    @Volatile
    private var tintB = 0.98f
    private var cachedProgram = -1
    private var uColLoc = -1

    init {
        setFragmentShader(FRAG_SHADER)
    }

    fun setTintColor(color: Int) {
        tintR = Color.red(color) / 255f
        tintG = Color.green(color) / 255f
        tintB = Color.blue(color) / 255f
    }

    fun setCloudsActive(active: Boolean) {
        if (continuousRender != active) {
            continuousRender = active
            if (active) requestRenderFrame()
        }
    }

    override fun onBindUniforms(program: Int, width: Int, height: Int, timeSec: Float) {
        if (program != cachedProgram) {
            cachedProgram = program
            uColLoc = GLES20.glGetUniformLocation(program, "uColor")
        }
        if (uColLoc >= 0) GLES20.glUniform3f(uColLoc, tintR, tintG, tintB)
    }

    companion object {
        private const val FRAG_SHADER = """
            precision mediump float;
            uniform vec2 uResolution;
            uniform float uTime;
            uniform vec3 uColor;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float fbm3(vec2 p) {
                float v = 0.5 * noise(p);
                p = p * 2.02 + vec2(13.1, 7.7);
                v += 0.25 * noise(p);
                p = p * 2.02 + vec2(13.1, 7.7);
                v += 0.125 * noise(p);
                return v;
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
                float t = uTime * 0.22;
                float n1 = fbm3(uv * 2.6 + vec2(t, -t * 0.6));
                float n2 = 0.55 * noise(uv * 4.2 - vec2(t * 0.5, t * 0.8) + n1 * 0.65)
                         + 0.30 * noise(uv * 8.4 - vec2(t, t * 1.4) + n1);
                float cloud = smoothstep(0.26, 0.78, n2);
                vec3 col = uColor * (0.65 + 0.35 * n1);
                float alpha = cloud * 0.30;
                gl_FragColor = vec4(col, alpha);
            }
        """
    }
}
