#version 150

#moj_import <fog.glsl>

// icheme:sight_glass (batch 2026-11-18-equipment-reflections, part 3): one quad per bubble over a sight glass's window,
// NEW_ENTITY vertices packed by SightGlassRenderer. The bubbles' positions come from SightGlassGeometry.bubbles on the CPU.
//   Color   the bubble's rim colour and alpha (SightGlassGeometry.bubbleColour)
//   UV0     the fragment's offset from the bubble's centre in the window's axes, in units of the bubble's radius
//   UV1     the fragment's position in the window, in window radii x 10000 (signed shorts)
//   UV2     packed light
//   Normal  (ax, ay) the flow's direction in the window's axes, z = (stretch - 1) / 1.5 (raw, not through the pose)
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

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 bubbleCoord;
out vec2 windowCoord;
out vec3 flow;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    bubbleCoord = UV0;
    windowCoord = vec2(UV1) / 10000.0;
    flow = vec3(Normal.xy, 1.0 + 1.5 * Normal.z);
}
