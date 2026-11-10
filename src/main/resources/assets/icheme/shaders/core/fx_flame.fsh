#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float FxTime;

in float vertexDistance;
// R: position along the flame (0 root, 1 tip); G: heat; B: soot; A: opacity (FxSink).
in vec4 params;
in vec2 corner;
in float seed;

out vec4 fragColor;

// Value noise and a four-octave fractal sum (about 0 to 0.94).
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

// A blackbody's display colour (the Tanner Helland fit), as FxColour.blackbody.
vec3 blackbody(float kelvin) {
    float t = clamp(kelvin, 1000.0, 40000.0) / 100.0;
    float g = 0.3900815788 * log(t) - 0.6318414438;
    float b = t <= 19.0 ? 0.0 : 0.5432067891 * log(t - 10.0) - 1.1962540891;
    return clamp(vec3(1.0, g, b), 0.0, 1.0);
}

void main() {
    float along = clamp(params.r, 0.0, 1.0);
    float heat = params.g;
    float soot = params.b;
    float across = corner.x * 2.0 - 1.0;

    // Domain-warped noise scrolling up the ribbon.
    vec2 p = vec2(across * 1.6 + seed * 17.0, along * 3.0 - FxTime * 1.8);
    vec2 warp = vec2(fbm(p + vec2(0.0, FxTime * 0.6)), fbm(p + vec2(5.2, 1.3 - FxTime * 0.4)));
    float n = fbm(p + 1.7 * warp);

    // A ragged edge that closes toward the tip.
    float edge = abs(across) + 0.55 * (n - 0.47) + 0.25 * along;
    float body = 1.0 - smoothstep(0.55, 1.0, edge);
    // The core: hot at the root and the middle, 1100 K at the tip to 1900 K in the core.
    float core = clamp(heat * (1.0 - along) * (1.0 - abs(across)) * (0.6 + 0.9 * n), 0.0, 1.0);
    vec3 rgb = blackbody(1100.0 + 800.0 * core);
    rgb = mix(rgb, vec3(1.0), 0.35 * core * core);
    // Soot darkens the upper flame.
    rgb *= 1.0 - 0.85 * soot * smoothstep(0.45, 1.0, along);

    float alpha = body * params.a * (1.0 - 0.7 * smoothstep(0.75, 1.0, along)) * (0.75 + 0.5 * n);
    alpha = clamp(alpha, 0.0, 1.0) * ColorModulator.a;
    if (alpha < 0.004) {
        discard;
    }
    // Full bright, no lightmap; vanilla fog on the straight colour, then premultiplied.
    vec4 lit = linear_fog(vec4(rgb * ColorModulator.rgb * (0.85 + 0.6 * core), alpha), vertexDistance, FogStart, FogEnd, FogColor);
    fragColor = vec4(lit.rgb * alpha, alpha);
}
