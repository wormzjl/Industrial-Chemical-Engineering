#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float FxTime;
// The sky brightness, 0.2 at night to 1 at noon (ClientLevel.getSkyDarken).
uniform float Daylight;

in float vertexDistance;
// RGB: albedo; A: density (FxSink).
in vec4 params;
in vec2 corner;
in float seed;
in vec3 sunView;

out vec4 fragColor;

// Value noise and a four-octave fractal sum (about 0 to 0.94), as fx_flame.fsh.
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
    for (int i = 0; i < 4; i++) {
        sum += amplitude * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        amplitude *= 0.5;
    }
    return sum;
}

void main() {
    vec2 q = corner * 2.0 - 1.0;
    float r = length(q);

    // A puff with a torn, billowing edge (finer, harder erosion: no round balls), drifting slowly.
    vec2 p = q * 2.4 + vec2(seed * 31.0, seed * 17.0) + vec2(FxTime * 0.03, -FxTime * 0.05);
    float n = fbm(p + 1.3 * vec2(fbm(p + 3.1), fbm(p - 1.7)));
    float shape = 1.0 - smoothstep(0.35, 1.0, r + 0.9 * (n - 0.47));
    float alpha = clamp(shape * params.a * (0.7 + 0.6 * n), 0.0, 1.0) * ColorModulator.a;
    if (alpha < 0.004) {
        discard;
    }

    // Lit as a sphere by the sun and by the sky: 15 % at night, so a plume is not white in the dark (FxColour.puff).
    // A flattened sphere: a puff is a soft cloud, not a lit ball.
    vec3 normal = normalize(vec3(q, sqrt(max(0.0, 1.0 - r * r)) + 1.0));
    float sun = max(0.0, dot(normal, normalize(sunView + vec3(0.0, 0.0, 1e-4))));
    float day = clamp((Daylight - 0.2) / 0.8, 0.0, 1.0);
    float light = 0.15 + 0.85 * day * (0.7 + 0.3 * sun);
    vec4 lit = linear_fog(vec4(params.rgb * ColorModulator.rgb * light, alpha), vertexDistance, FogStart, FogEnd, FogColor);
    fragColor = vec4(lit.rgb * alpha, alpha);
}
