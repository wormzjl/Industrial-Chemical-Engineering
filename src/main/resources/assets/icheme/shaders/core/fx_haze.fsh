#version 150

#moj_import <fog.glsl>

// The screen copy taken just before the haze draws (FxBatch).
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float FxTime;
uniform vec2 ScreenSize;

in float vertexDistance;
// A: the shimmer's strength (FxSink, HAZE).
in vec4 params;
in vec2 corner;
in float seed;

out vec4 fragColor;

// Value noise and a three-octave fractal sum, as fx_flame.fsh.
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 3; i++) {
        sum += amplitude * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        amplitude *= 0.5;
    }
    return sum;
}

// Heat shimmer (W8): hot gas bends the light behind it. The screen copy is read a few pixels off, by noise that rises with
// the gas; the offset fades to the sprite's edge and into the fog, so far stacks do not shimmer.
void main() {
    vec2 q = corner * 2.0 - 1.0;
    float fade = 1.0 - smoothstep(FogStart, max(FogEnd, FogStart + 1.0), vertexDistance);
    float mask = (1.0 - smoothstep(0.35, 1.0, length(q))) * clamp(params.a, 0.0, 1.0) * ColorModulator.a * fade;
    if (mask < 0.004 || FogColor.a < -1.0) {
        discard;
    }
    vec2 p = vec2(q.x * 3.0 + seed * 13.0, q.y * 2.0 - FxTime * 2.2);
    vec2 offset = vec2(fbm(p) - 0.44, fbm(p + vec2(4.1, 2.3)) - 0.44) * 2.0;
    vec2 uv = (gl_FragCoord.xy + offset * 4.0 * mask) / max(ScreenSize, vec2(1.0));
    vec4 screen = texture(Sampler0, clamp(uv, vec2(0.0), vec2(1.0)));
    fragColor = vec4(screen.rgb * mask, mask);
}
