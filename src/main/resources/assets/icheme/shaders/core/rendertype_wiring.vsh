#version 150

#moj_import <fog.glsl>

// icheme:rendertype_wiring (batch 2026-11-10-custom-shader-system, plan section 4.4): the instrument wiring's cached
// buffers on the SHADER path. NEW_ENTITY vertices in the structure's own frame (axes as the world's), unshaded white,
// v in texture periods. One structure per draw: WiringOrigin is its origin relative to the camera, WiringLight its packed
// light (block, sky), so the buffer is built again only when the wiring changes.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 WiringOrigin;
uniform ivec2 WiringLight;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;
// Camera-relative world position and the world normal: the fragment builds its tangent frame from their derivatives.
out vec3 worldPosition;
out vec3 worldNormal;

void main() {
    vec3 position = Position + WiringOrigin;
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    vertexDistance = fog_distance(position, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, WiringLight / 16, 0);
    texCoord0 = UV0;
    worldPosition = position;
    worldNormal = Normal;
}
