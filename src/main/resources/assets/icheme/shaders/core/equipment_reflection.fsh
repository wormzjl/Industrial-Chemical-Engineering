#version 150

// The reflection law of ReflectionLaw.added (batch 2026-11-18-equipment-reflections), with its constants as uniforms:
// (sky(R) F tint + highlight) x sky light x strength x range fade, faded with the terrain's fog, added over the chunk mesh.

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// The level's sky colour, the fog colour (the horizon), the ground just below the horizon and far below it (x daylight).
uniform vec3 Zenith;
uniform vec3 Horizon;
uniform vec3 NearGround;
uniform vec3 Ground;
// The sky's and the ground's gradient powers.
uniform vec2 Powers;
// The sun or the moon: direction (world, unit) and strength (ReflectionLaw.celestial).
uniform vec4 Light;
// The sky light over which the highlight comes in.
uniform vec2 HighlightSky;
uniform float Strength;
// The range fade: full to x, nothing from y, m.
uniform vec2 Fade;

in float vertexDistance;
in vec3 position;
in vec3 normal;
in vec4 tint;
in vec4 surface;

out vec4 fragColor;

void main() {
    vec3 n = normalize(normal);
    vec3 v = normalize(-position);
    float c = clamp(dot(n, v), 0.0, 1.0);
    vec3 r = reflect(-v, n);
    float blur = surface.y;
    vec3 up = mix(Horizon, Zenith, pow(max(r.y, 0.0), Powers.x));
    vec3 down = mix(NearGround, Ground, pow(max(-r.y, 0.0), Powers.y));
    vec3 sky = mix(down, up, smoothstep(-blur, blur, r.y));
    float m = 1.0 - c;
    float m2 = m * m;
    float fresnel = surface.x + (1.0 - surface.x) * m2 * m2 * m;
    float skyLight = tint.a;
    float highlight = pow(max(dot(r, Light.xyz), 0.0), surface.z) * surface.w * Light.w * smoothstep(HighlightSky.x, HighlightSky.y, skyLight);
    float fade = 1.0 - smoothstep(Fade.x, Fade.y, length(position));
    // As linear_fog mixes the terrain towards the fog colour, the reflection fades by the same share.
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    vec3 added = (sky * fresnel * tint.rgb + highlight) * skyLight * Strength * fade * (1.0 - fog * FogColor.a);
    fragColor = vec4(added, 0.0);
}
