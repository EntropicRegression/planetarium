// WallEngine sample "plasma": slowly drifting colour bands.
// Shadertoy style: only mainImage is defined; the app supplies the uniforms and main().
// "speed" is a pack parameter declared in manifest.json.

vec3 palette(float t) {
    vec3 phase = vec3(0.02, 0.36, 0.62);
    return 0.5 + 0.5 * cos(6.2831853 * (t + phase));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = (fragCoord - 0.5 * iResolution.xy) / iResolution.y;
    float t = iTime * 0.2 * speed;

    // Swiping between launcher pages slides the pattern sideways.
    vec2 p = uv * 2.2;
    p.x += (uOffset - 0.5) * 1.5;

    float v = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        p += 0.55 * vec2(sin(p.y * 1.3 + t + fi * 1.7), cos(p.x * 1.1 - t * 0.8 + fi * 2.3));
        v += sin(p.x + p.y + t) / (fi + 1.0);
    }

    vec3 col = palette(0.13 * v + 0.04 * t);
    col = mix(vec3(0.03, 0.04, 0.12), col, 0.8);
    // Darker towards the corners.
    col *= 1.0 - 0.45 * smoothstep(0.2, 1.3, length(uv));
    fragColor = vec4(col, 1.0);
}
