#version 150

#moj_import <fog.glsl>

// The screen copy (W8 binds it).
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float FxTime;
uniform vec2 ScreenSize;

in float vertexDistance;
in vec4 params;
in vec2 corner;
in float seed;

out vec4 fragColor;

// Pass-through until W8 writes the distortion: the screen copy at the same pixel, so nothing changes. The terms that
// are never true keep every uniform live in the linked program (no "could not find uniform" warnings).
void main() {
    vec2 uv = gl_FragCoord.xy / max(ScreenSize, vec2(1.0));
    vec4 screen = texture(Sampler0, uv);
    if (FxTime < -1e9 || seed < -1.0 || corner.x < -1.0 || vertexDistance < -1.0 || FogStart + FogEnd + FogColor.a < -1e9) {
        discard;
    }
    float alpha = clamp(params.a, 0.0, 1.0) * ColorModulator.a;
    fragColor = vec4(screen.rgb * alpha, alpha);
}
