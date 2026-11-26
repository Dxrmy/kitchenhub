package com.kyle.kitchenhub.core.ui

import android.content.Context
import android.opengl.GLES20
import android.util.AttributeSet

/**
 * Port of AOSP weathereffects GLSL/AGSL shaders (rain streaks + mist, sun rays + shimmer,
 * snow bokeh + haze, cloudy FBM fog) running at 15fps inside the Weather modal.
 */
class WeatherEffectsShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GlShaderTextureView(context, attrs) {

    override val bufferScale: Float get() = 0.35f

    @Volatile
    private var mode = 0 // 0=cloudy, 1=clear/sun, 2=rain/storm, 3=snow
    private var cachedProgram = -1
    private var uModeLoc = -1

    init {
        setFragmentShader(FRAG_SHADER)
    }

    fun setCondition(cond: String) {
        mode = when {
            cond.contains("Rain", true) || cond.contains("Storm", true) -> 2
            cond.contains("Snow", true) -> 3
            cond.contains("Clear", true) -> 1
            else -> 0
        }
        requestRenderFrame()
    }

    override fun onBindUniforms(program: Int, width: Int, height: Int, timeSec: Float) {
        if (program != cachedProgram) {
            cachedProgram = program
            uModeLoc = GLES20.glGetUniformLocation(program, "uMode")
        }
        if (uModeLoc >= 0) GLES20.glUniform1f(uModeLoc, mode.toFloat())
    }

    companion object {
        private const val FRAG_SHADER = """
            precision mediump float;
            uniform vec2 uResolution;
            uniform float uTime;
            uniform float uMode;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
                    u.y
                );
            }

            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += a * noise(p);
                    p = p * 2.03 + vec2(11.3, 5.7);
                    a *= 0.5;
                }
                return v;
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
                float aspect = uResolution.x / max(uResolution.y, 1.0);
                vec2 p = vec2(uv.x * aspect, uv.y);

                // Smooth vertical fade-out at the bottom of the shader band (no hard horizontal cutoff)
                float edgeFade = smoothstep(0.0, 0.48, uv.y);

                if (uMode > 0.5 && uMode < 1.5) {
                    // Sun / Clear: warm volumetric sun glow + rotating rays
                    vec2 sunPos = vec2(aspect * 0.82, 0.78);
                    vec2 d = p - sunPos;
                    float r = length(d);
                    float ang = atan(d.y, d.x) + uTime * 0.15;
                    float rays = 0.5 + 0.5 * sin(ang * 8.0);
                    float glow = exp(-r * 2.2) * (0.55 + 0.45 * rays);
                    vec3 col = mix(vec3(1.0, 0.82, 0.32), vec3(1.0, 0.65, 0.2), r);
                    gl_FragColor = vec4(col, clamp(glow * 0.62, 0.0, 0.68) * edgeFade);
                } else if (uMode >= 1.5 && uMode < 2.5) {
                    // Rain / Storm: multi-layer tilted rain streaks + atmospheric mist
                    vec2 ruv = vec2(p.x + p.y * 0.18, p.y);
                    float colIdx = floor(ruv.x * 28.0);
                    float speed = 1.6 + 0.6 * hash(vec2(colIdx, 3.0));
                    float yShift = fract(ruv.y * 2.5 + uTime * speed + hash(vec2(colIdx, 9.0)));
                    float xCell = abs(fract(ruv.x * 28.0) - 0.5);
                    float streak = smoothstep(0.14, 0.0, xCell) * smoothstep(0.0, 0.25, yShift) * smoothstep(0.55, 0.25, yShift);
                    float mist = fbm(p * 2.5 + vec2(uTime * 0.08, 0.0)) * 0.32;
                    vec3 col = vec3(0.52, 0.80, 1.0) * (streak * 0.85 + 0.45);
                    gl_FragColor = vec4(col, clamp(streak * 0.55 + mist, 0.0, 0.65) * edgeFade);
                } else if (uMode >= 2.5) {
                    // Snow: drifting soft snow particles + winter fog
                    float snow = 0.0;
                    for (int l = 0; l < 2; l++) {
                        float scale = (l == 0) ? 14.0 : 22.0;
                        vec2 suv = p * scale + vec2(sin(uTime * 0.6 + p.y * 3.0) * 0.3, uTime * (0.45 + 0.2 * float(l)));
                        vec2 id = floor(suv);
                        vec2 f = fract(suv) - 0.5;
                        float rnd = hash(id + float(l) * 19.0);
                        vec2 off = vec2(rnd - 0.5, fract(rnd * 17.3) - 0.5) * 0.5;
                        float d = length(f - off);
                        snow += smoothstep(0.18, 0.04, d) * step(0.65, rnd);
                    }
                    float haze = fbm(p * 2.0 + vec2(uTime * 0.05, 0.0)) * 0.28;
                    gl_FragColor = vec4(vec3(0.92, 0.96, 1.0), clamp(snow * 0.52 + haze, 0.0, 0.62) * edgeFade);
                } else {
                    // Cloudy / Partly Cloudy: full-width layered FBM drifting clouds + soft fade-out
                    float c1 = fbm(p * 1.45 + vec2(uTime * 0.08, -uTime * 0.015));
                    float c2 = fbm(p * 2.60 - vec2(uTime * 0.05, uTime * 0.01) + vec2(4.2, 1.7));
                    float c = c1 * 0.65 + c2 * 0.35;
                    float cloud = smoothstep(0.28, 0.66, c);
                    vec3 col = mix(vec3(0.55, 0.74, 0.98), vec3(0.88, 0.94, 1.0), cloud);
                    gl_FragColor = vec4(col, clamp(cloud * 0.42, 0.0, 0.46) * edgeFade);
                }
            }
        """
    }
}
