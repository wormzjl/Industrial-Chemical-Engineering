#version 150

// The gloss law of ReflectionLaw.added (batch 2026-11-18-equipment-reflections), with its constants as uniforms: a grey
// (surround F + highlight) x sky light x strength x range fade, faded with the terrain's fog, added over the chunk mesh,
// which keeps 1 - F x the same factors (ReflectionLaw.kept: alpha is the sheen's share, blend ONE, ONE_MINUS_SRC_ALPHA).

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// The fog colour (the horizon): the sheen's grey is its brightness.
uniform vec3 Horizon;
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
    float surround = dot(Horizon, vec3(0.2126, 0.7152, 0.0722));
    float m = 1.0 - c;
    float m2 = m * m;
    float fresnel = surface.x + (1.0 - surface.x) * m2 * m2 * m;
    float skyLight = tint.a;
    float highlight = pow(max(dot(r, Light.xyz), 0.0), surface.z) * surface.w * Light.w * smoothstep(HighlightSky.x, HighlightSky.y, skyLight);
    float fade = 1.0 - smoothstep(Fade.x, Fade.y, length(position));
    // As linear_fog mixes the terrain towards the fog colour, the reflection fades by the same share.
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    float scale = skyLight * Strength * fade * (1.0 - fog * FogColor.a);
    float added = (surround * fresnel + highlight) * scale;
    fragColor = vec4(vec3(added), min(fresnel * scale, 1.0));
}
