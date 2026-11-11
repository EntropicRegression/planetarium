#version 300 es
// Planetarium sample "dunes": layered dunes at dusk that slide past each other as you swipe
// between launcher pages (uOffset).
//
// Complete-shader style: the file has its own #version line, declares the uniforms it needs
// and defines main(). It does not use iTime, so it is only redrawn when the page offset changes.
precision highp float;

uniform vec3 iResolution;
uniform float uOffset;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n * 91.345) * 4745.853);
}

// Smooth 1D value noise.
float noise(float x) {
    float i = floor(x);
    float f = fract(x);
    float u = f * f * (3.0 - 2.0 * f);
    return mix(hash(i), hash(i + 1.0), u);
}

float ridge(float x) {
    return 0.6 * noise(x) + 0.3 * noise(x * 2.1 + 7.0) + 0.1 * noise(x * 4.3 + 19.0);
}

void main() {
    vec2 uv = gl_FragCoord.xy / iResolution.xy;
    float aspect = iResolution.x / iResolution.y;
    float scroll = uOffset - 0.5;

    // Sky: warm at the horizon, deep violet above.
    vec3 col = mix(vec3(0.98, 0.60, 0.40), vec3(0.15, 0.13, 0.36), smoothstep(0.25, 1.0, uv.y));

    // Sun with a soft glow; it barely moves, being far away.
    vec2 sun = vec2(0.5 - scroll * 0.08, 0.50);
    float d = length((uv - sun) * vec2(aspect, 1.0));
    col += vec3(1.0, 0.75, 0.45) * (1.0 - smoothstep(0.0, 0.45, d)) * 0.45;
    col = mix(col, vec3(1.0, 0.94, 0.78), 1.0 - smoothstep(0.070, 0.076, d));

    // Five ridges, far to near. Nearer ones are darker, taller and move faster.
    for (int i = 0; i < 5; i++) {
        float k = float(i) / 4.0;
        float x = uv.x * aspect * mix(3.0, 1.2, k) + scroll * mix(0.4, 3.5, k) + float(i) * 13.7;
        float top = mix(0.42, 0.06, k) + ridge(x) * mix(0.10, 0.22, k);
        vec3 tint = mix(vec3(0.66, 0.38, 0.40), vec3(0.09, 0.05, 0.15), k);
        // Lighter rim just below each crest.
        tint += vec3(0.10, 0.05, 0.02) * (1.0 - smoothstep(0.0, 0.05, top - uv.y));
        float mask = 1.0 - smoothstep(top - 0.003, top, uv.y);
        col = mix(col, tint, mask);
    }

    fragColor = vec4(col, 1.0);
}
