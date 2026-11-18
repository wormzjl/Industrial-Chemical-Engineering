#version 150

#moj_import <fog.glsl>

// icheme:sight_glass (batch 2026-11-18-equipment-reflections, part 3): a gas bubble in the liquid behind a sight glass.
// Clipped to the round glass; a soft pale rim, a small highlight toward the light (up and to the left in the window's
// axes), the liquid's colour through the centre. tools/sight-glass-frames/ copies bubbleShade line for line.
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 bubbleCoord;
in vec2 windowCoord;
in vec3 flow;

out vec4 fragColor;

const float APERTURE = 0.97;
const vec2 HIGHLIGHT = vec2(-0.32, 0.42);

// x: the bubble's opacity at this point (times the rim colour's alpha), y: how much of it is the white highlight.
// d: the offset from the bubble's centre in the window's axes, in its radius; a: the flow's direction; stretch along it.
vec2 bubbleShade(vec2 d, vec2 a, float stretch) {
    float along = dot(d, a) / stretch, across = dot(d, vec2(-a.y, a.x));
    float r = length(vec2(along, across));
    if (r > 1.0) return vec2(0.0);
    float rim = smoothstep(0.5, 0.88, r) * (1.0 - smoothstep(0.9, 1.0, r));
    // The highlight sits toward the light in the window's axes, whichever way the flow runs.
    float spot = 1.0 - smoothstep(0.0, 0.3, length(d - HIGHLIGHT));
    return vec2(clamp(0.1 + 0.9 * rim + 0.8 * spot, 0.0, 1.0), spot);
}

void main() {
    // The glass's rim: soft over the outer 2 % so the clip does not alias.
    float edge = 1.0 - smoothstep(APERTURE - 0.02, APERTURE, length(windowCoord));
    vec2 shade = bubbleShade(bubbleCoord, normalize(flow.xy), flow.z);
    float alpha = vertexColor.a * shade.x * edge;
    if (alpha < 0.004) discard;
    vec3 colour = mix(vertexColor.rgb, vec3(1.0), 0.8 * shade.y);
    vec4 lit = vec4(colour, alpha) * ColorModulator * lightMapColor;
    fragColor = linear_fog(vec4(lit.rgb, alpha * ColorModulator.a), vertexDistance, FogStart, FogEnd, FogColor);
}
