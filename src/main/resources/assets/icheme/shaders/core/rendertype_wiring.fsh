#version 150

#moj_import <fog.glsl>

// icheme:rendertype_wiring (batch 2026-11-10-custom-shader-system, plan section 4.4): the vanilla entity cutout look,
// lit per pixel through the wiring's LabPBR 1.3 maps. _n: RG normal X (+u) and Y (+v, DirectX "Y-"), B AO, A height.
// _s: R smoothness, G F0 (0..229) or metal id (230..255), B porosity, A emission (none on the wiring).
uniform sampler2D Sampler0;
uniform sampler2D NormalMap;
uniform sampler2D SpecularMap;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec3 worldPosition;
in vec3 worldNormal;

out vec4 fragColor;

// Vanilla's entity light: two fixed directions, 0.6 of their sum plus 0.4 ambient (light.glsl).
float entityLight(vec3 normal) {
    return min(1.0, (max(0.0, dot(Light0_Direction, normal)) + max(0.0, dot(Light1_Direction, normal))) * 0.6 + 0.4);
}

// A faint highlight from each entity light, sharper on smoother parts.
float sheen(vec3 normal, vec3 view, vec3 light, float smoothness) {
    vec3 halfway = normalize(light + view);
    return pow(max(dot(normal, halfway), 0.0), mix(6.0, 48.0, smoothness)) * max(0.0, dot(light, normal));
}

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < 0.1) {
        discard;
    }
    vec4 relief = texture(NormalMap, texCoord0);
    vec4 specular = texture(SpecularMap, texCoord0);

    // The tangent frame from screen-space derivatives of position and UV: the vertex format stays NEW_ENTITY.
    // The maps encode real slopes, so T and B are normalised separately, not as a cotangent frame.
    vec3 n = normalize(worldNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 dp1 = dFdx(worldPosition), dp2 = dFdy(worldPosition);
    vec2 dt1 = dFdx(texCoord0), dt2 = dFdy(texCoord0);
    vec3 p2 = cross(dp2, n), p1 = cross(n, dp1);
    vec3 t = p2 * dt1.x + p1 * dt2.x;
    vec3 b = p2 * dt1.y + p1 * dt2.y;
    t -= n * dot(n, t);
    b -= n * dot(n, b);
    vec3 mapped = n;
    if (dot(t, t) > 1e-24 && dot(b, b) > 1e-24) {
        vec2 xy = relief.rg * 2.0 - 1.0;
        vec3 local = vec3(xy, sqrt(max(0.0, 1.0 - dot(xy, xy))));
        mapped = normalize(mat3(normalize(t), normalize(b), n) * local);
    }

    float smoothness = specular.r;
    bool metal = specular.g * 255.0 > 229.5;
    vec3 view = normalize(-worldPosition);
    float shine = (sheen(mapped, view, Light0_Direction, smoothness) + sheen(mapped, view, Light1_Direction, smoothness))
            * smoothness * (metal ? 0.35 : 0.06);

    vec4 color = albedo * vertexColor * ColorModulator;
    color.rgb = color.rgb * entityLight(mapped) * relief.b + shine * (metal ? albedo.rgb : vec3(1.0));
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
