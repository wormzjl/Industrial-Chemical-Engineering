#version 150

#moj_import <fog.glsl>

// The equipment reflection pass (batch 2026-11-18-equipment-reflections; EquipmentReflectionRenderer packs the vertices).
// BLOCK layout, repacked: Position = m from the region corner; Color = white (rgb, unused) and the vertex's sky light
// (a, 0 to 1); UV0 = Fresnel F0 (x; y unused); UV2 = the highlight's power and its strength x 100; Normal = the smooth
// normal (world axes: the mesh is only translated).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
// The region corner minus the camera, m.
uniform vec3 Origin;
// ReflectionDepth: x position rounding (m), y subpixel snap (per m of distance per unit slope), z depth rounding (per m^2), w the slope cap.
uniform vec4 Pull;

out float vertexDistance;
out vec3 position;
out vec3 normal;
out vec4 tint;
out vec4 surface;

void main() {
    vec3 pos = Position + Origin;
    vec4 eye = ModelViewMat * vec4(pos, 1.0);
    // Pulled towards the eye along its view ray, so the depth test passes on the chunk mesh's own copy of this surface and
    // fails behind anything in front (ReflectionDepth.pull, measured in ReflectionDepthTest).
    float z = max(-eye.z, 1e-3);
    float c = clamp(dot(normalize(Normal), normalize(-pos)), 1e-3, 1.0);
    float t = min(sqrt(1.0 - c * c) / c, Pull.w);
    float pull = (Pull.x + Pull.y * z) * (1.0 + t) + Pull.z * z * z;
    eye.xyz *= 1.0 - pull / z;
    gl_Position = ProjMat * eye;

    vertexDistance = fog_distance(pos, FogShape);
    position = pos;
    normal = Normal;
    tint = Color;
    surface = vec4(UV0, float(UV2.x), float(UV2.y) / 100.0);
}
