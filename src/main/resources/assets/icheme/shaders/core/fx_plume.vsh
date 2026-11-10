#version 150

#moj_import <fog.glsl>

// Packed layout: FxPackedSink (see fx_flame.vsh). Sprites only: Normal is unused.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 Origin;
uniform float FxTime;
uniform float GlideStart;
uniform float GlideSeconds;
// Toward the sun, world axes (unit).
uniform vec3 SunDirection;

out float vertexDistance;
out vec4 params;
out vec2 corner;
out float seed;
out vec3 sunView;

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
    seed = float(flags >> 3) / 4096.0;

    // A slow billow: each puff swells and shrinks by up to 6 %.
    halfSize *= 1.0 + 0.06 * sin(FxTime * 0.8 + seed * 50.0);

    vec4 view = ModelViewMat * vec4(centre, 1.0);
    view.xy += (c * 2.0 - 1.0) * halfSize;
    gl_Position = ProjMat * view;
    vertexDistance = fog_distance(centre, FogShape);
    params = Color;
    corner = c;
    sunView = mat3(ModelViewMat) * SunDirection;
}
