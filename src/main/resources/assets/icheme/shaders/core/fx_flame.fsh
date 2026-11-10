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
// A billboard's noise domain in eddies; a ribbon's place in metres (across, from the root); 1 on a ribbon (fx_flame.vsh).
in vec2 eddy;
in vec2 metres;
in float onRibbon;

out vec4 fragColor;

// A ribbon is ROOM flame half-widths across (fx_flame.vsh).
const float ROOM = 1.5;
// An eddy's size at m metres from the root, m: it grows with the distance as a turbulent jet's width does (about 0.22 of
// it), from ROOT m at the root, so a 30 m flare and a 0.3 m still flame look alike in kind.
const float EDDY = 0.22;
const float ROOT = 0.2;

// Value noise, 0 to 1.
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

// A four-octave fractal sum (mean about 0.47) whose finer octaves climb faster than the coarse ones and drift sideways by
// turns, so the pattern boils as it rises instead of sliding up the flame as one picture.
float flow(vec2 p, float t) {
    float sum = 0.0;
    float amplitude = 0.5;
    float drift = 0.3;
    for (int i = 0; i < 4; i++) {
        sum += amplitude * noise(p - vec2(drift * t, t));
        p = p * 2.03 + vec2(1.7, 9.2);
        t *= 2.33;
        drift = -drift;
        amplitude *= 0.5;
    }
    return sum;
}

// The colour of the flame's local intensity, 0 at its edge to 1 in its core: red-orange, orange, yellow, then pale yellow
// where a sooty flame's glow saturates the eye. Never darker than a dim flame: a flame does not shade the sky behind it.
vec3 ramp(float h) {
    vec3 c = mix(vec3(0.8, 0.2, 0.02), vec3(1.0, 0.4, 0.05), smoothstep(0.0, 0.25, h));
    c = mix(c, vec3(1.0, 0.6, 0.12), smoothstep(0.2, 0.5, h));
    c = mix(c, vec3(1.0, 0.85, 0.45), smoothstep(0.45, 0.8, h));
    return mix(c, vec3(1.0, 0.97, 0.85), smoothstep(0.8, 1.0, h));
}

/*
 * A turbulent flame as a threshold of a rising noise field (owner 2026-11-10: "still looks like plumes, it lacks the
 * variability of actual flames"). The intensity is full on the axis and falls to nothing at one half-width; the noise
 * moves its edge little at the root, where the flame is attached, and by half a width toward the end, where it tears
 * into tongues. The end wanders over about a quarter of the length in gusts, and past it only pockets torn off the flame
 * still burn. Bulges roll up the flame and pinch it (the puffing of a buoyant flame). The colour follows the intensity,
 * so every tongue is yellow inside and dark orange at its edge, and the edge is crisp, not a soft blur.
 */
void main() {
    float along = clamp(params.r, 0.0, 1.0);
    float heat = params.g;
    float soot = params.b;
    float x = (corner.x * 2.0 - 1.0) * ROOM;

    // The noise domain in eddies: across, and along on a log scale, so each unit is about one local eddy.
    vec2 e = onRibbon > 0.5 ? vec2(metres.x / (EDDY * (metres.y + ROOT)), log(1.0 + metres.y / ROOT) / EDDY) : eddy;
    // Tongues about half a local eddy across and twice as long, climbing about 20 m/s at a 30 m flare's end.
    vec2 p = vec2(e.x * 2.2, e.y * 1.1) + seed * vec2(17.0, 31.0);
    vec2 warp = vec2(noise(p * 0.5 + vec2(0.0, -FxTime * 1.3)), noise(p * 0.5 + vec2(4.3, -FxTime * 1.1))) - 0.5;
    float n = (flow(p + 1.2 * warp, FxTime * 3.3) - 0.47) / 0.11;

    float reach = 0.84 + 0.2 * (noise(vec2(FxTime * 1.3, seed * 50.0)) - 0.5) + 0.1 * (noise(vec2(FxTime * 3.7, seed * 20.0)) - 0.5);
    float puff = 1.0 + 0.2 * smoothstep(0.35, 0.75, along) * sin(e.y * 1.4 - FxTime * 4.0 + seed * 9.0);
    float intensity = 1.0 - abs(x) / puff + (0.12 + 0.4 * along) * n
            - 1.1 * smoothstep(0.5, reach + 0.08, along) - smoothstep(1.2, ROOM, abs(x));
    // Hotter is more opaque: the dim edges and the torn-off pockets let the sky through.
    float h = clamp(intensity * (0.45 + 0.55 * heat) - 0.15 * along, 0.0, 1.0);
    float alpha = smoothstep(0.0, 0.07, intensity) * (0.6 + 0.4 * smoothstep(0.0, 0.3, h)) * params.a;
    alpha = clamp(alpha, 0.0, 1.0) * ColorModulator.a;
    if (alpha < 0.004) {
        discard;
    }

    vec3 rgb = ramp(h);
    // Soot dims the upper flame's cooler edges toward a smoky red.
    rgb *= 1.0 - 0.5 * soot * smoothstep(0.6, 1.0, along) * (1.0 - h);
    // The blue root of a clean flame, before soot forms (FxColour.blueRoot).
    rgb = mix(rgb, vec3(0.35, 0.55, 1.0), 0.7 * heat * (1.0 - soot) * (1.0 - smoothstep(0.0, 0.18, along)));
    // Hotter is brighter, and the whole flame flickers a little.
    rgb *= (0.9 + 0.5 * h) * (0.9 + 0.2 * noise(vec2(FxTime * 7.0, seed * 61.0)));

    // Full bright, no lightmap; vanilla fog on the straight colour, then premultiplied.
    vec4 lit = linear_fog(vec4(rgb * ColorModulator.rgb, alpha), vertexDistance, FogStart, FogEnd, FogColor);
    fragColor = vec4(lit.rgb * alpha, alpha);
}
