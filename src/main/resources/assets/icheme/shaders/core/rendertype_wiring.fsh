#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
// LabPBR 1.3 beside the albedo (W2 binds them): _n RG normal XY, B AO, A height; _s R smoothness, G F0 or metal id, A emission.
uniform sampler2D NormalMap;
uniform sampler2D SpecularMap;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
in vec3 worldPosition;
in vec3 worldNormal;

out vec4 fragColor;

// Pass-through until W2 writes the normal-mapped lighting: the vanilla entity cutout look.
void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    // Never true for real texels; keeps both maps and the tangent inputs live in the linked program.
    vec4 relief = texture(NormalMap, texCoord0);
    vec4 specular = texture(SpecularMap, texCoord0);
    if (relief.a + specular.a < -1.0 || length(worldPosition) + length(worldNormal) < -1.0) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
