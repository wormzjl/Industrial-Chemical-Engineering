#version 150

#moj_import <fog.glsl>

// Packed layout: FxPackedSink. Position = new centre (emitter-local m), UV0 = old - new x and z (m), UV1.x = old - new y (mm),
// UV1.y = flags (corner u, corner v, ribbon, 12-bit seed), UV2 = old and new half-size (mm), Normal = ribbon axis, Color = parameters.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
// The emitter origin minus the camera, m; game seconds since the batch epoch; the glide start and length, s.
uniform vec3 Origin;
uniform float FxTime;
uniform float GlideStart;
uniform float GlideSeconds;

out float vertexDistance;
out vec4 params;
out vec2 corner;
out float seed;

// The glide ease, as FxGlide.ease: smoothstep of the elapsed share.
float ease(float k) {
    k = clamp(k, 0.0, 1.0);
    return k * k * (3.0 - 2.0 * k);
}

void main() {
    float e = ease((FxTime - GlideStart) / max(GlideSeconds, 0.001));
    vec3 oldCentre = Position + vec3(UV0.x, float(UV1.x) / 1000.0, UV0.y);
    vec3 centre = Origin + mix(oldCentre, Position, e);
    float halfSize = mix(float(UV2.x), float(UV2.y), e) / 1000.0;
    int flags = UV1.y;
    vec2 c = vec2(float(flags & 1), float((flags >> 1) & 1));
    bool ribbon = ((flags >> 2) & 1) == 1;
    seed = float(flags >> 3) / 4096.0;
    float along = Color.r;

    // Flicker: the width breathes by up to 15 %, in two beats that do not repeat together.
    float flick = 0.5 * sin(FxTime * 9.0 + seed * 40.0 + along * 12.0) + 0.5 * sin(FxTime * 23.0 + seed * 17.0 - along * 7.0);
    halfSize *= 1.0 + 0.15 * flick;

    vec4 view = ModelViewMat * vec4(centre, 1.0);
    if (ribbon) {
        // Turned about its own axis to face the camera: the side is across the axis and the line of sight.
        vec3 axis = mat3(ModelViewMat) * Normal;
        vec3 side = cross(axis, -view.xyz);
        float s = length(side);
        side = s > 1e-5 ? side / s : vec3(1.0, 0.0, 0.0);
        view.xyz += side * (c.x * 2.0 - 1.0) * halfSize;
        // A sway that grows toward the tip.
        view.xyz += side * along * along * 0.25 * halfSize * sin(FxTime * 5.0 + seed * 31.0 + along * 6.0);
    } else {
        view.xy += (c * 2.0 - 1.0) * halfSize;
    }
    gl_Position = ProjMat * view;
    vertexDistance = fog_distance(centre, FogShape);
    params = Color;
    corner = c;
}
