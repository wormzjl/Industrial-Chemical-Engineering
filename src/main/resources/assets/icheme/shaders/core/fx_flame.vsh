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
// A billboard's noise domain, in eddies.
out vec2 eddy;
// A ribbon's place in metres: across from its centre line and from the flame's root; fx_flame.fsh turns them into eddies
// per fragment (a log interpolated between vertices packed the first segment's noise into ripples).
out vec2 metres;
// 1 on a ribbon, 0 on a billboard.
out float onRibbon;

// A ribbon is drawn this many times the flame's half-width, so the tongues of fx_flame.fsh can reach past it.
const float ROOM = 1.5;

// The glide ease, as FxGlide.ease: smoothstep of the elapsed share.
float ease(float k) {
    k = clamp(k, 0.0, 1.0);
    return k * k * (3.0 - 2.0 * k);
}

void main() {
    float e = ease((FxTime - GlideStart) / max(GlideSeconds, 0.001));
    vec3 oldCentre = Position + vec3(UV0.x, float(UV1.x) / 1000.0, UV0.y);
    // Emitter-local, from the flame's root (the flare tip, the still's door).
    vec3 local = mix(oldCentre, Position, e);
    float halfSize = mix(float(UV2.x), float(UV2.y), e) / 1000.0;
    int flags = UV1.y;
    vec2 c = vec2(float(flags & 1), float((flags >> 1) & 1));
    bool ribbon = ((flags >> 2) & 1) == 1;
    seed = float(flags >> 3) / 4096.0;
    float along = Color.r;

    // The whole ribbon breathes in length from its root, in two beats that do not repeat together (one seed per ribbon);
    // the shader's wandering end does the rest.
    if (ribbon) local *= 1.0 + 0.05 * sin(FxTime * 2.3 + seed * 40.0) + 0.04 * sin(FxTime * 5.9 + seed * 23.0);
    vec3 centre = Origin + local;
    float m = length(local);

    vec4 view = ModelViewMat * vec4(centre, 1.0);
    if (ribbon) {
        // Turned about its own axis to face the camera: the side is across the axis and the line of sight.
        vec3 axis = mat3(ModelViewMat) * Normal;
        vec3 side = cross(axis, -view.xyz);
        float s = length(side);
        side = s > 1e-5 ? side / s : vec3(1.0, 0.0, 0.0);
        view.xyz += side * (c.x * 2.0 - 1.0) * halfSize * ROOM;
        // Two waves travel up the flame and grow toward its end, a slow lean and a quicker lick, in metres of the larger
        // of the local half-width and 0.15 of the distance from the root, so a short still flame moves visibly too.
        float sway = max(halfSize, 0.15 * m) * along;
        view.xyz += side * sway * (0.45 * sin(along * 4.0 - FxTime * 3.1 + seed * 31.0) + 0.3 * sin(along * 8.0 - FxTime * 7.3 + seed * 13.0));
        metres = vec2((c.x * 2.0 - 1.0) * halfSize * ROOM, m);
        eddy = vec2(0.0);
        onRibbon = 1.0;
    } else {
        eddy = (c * 2.0 - 1.0) * 1.6;
        metres = vec2(0.0);
        onRibbon = 0.0;
        view.xy += (c * 2.0 - 1.0) * halfSize;
    }
    gl_Position = ProjMat * view;
    vertexDistance = fog_distance(centre, FogShape);
    params = Color;
    corner = c;
}
