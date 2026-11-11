// Falling snow in three depth layers. Output is premultiplied white, for "blend": "normal".
vec2 hash2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

float flakes(vec2 uv, float scale, float speed, float size) {
    uv *= scale;
    // fragCoord's y points up, so adding time makes the pattern fall.
    uv.y += iTime * speed;
    uv.x += 0.3 * sin(uv.y * 0.7 + scale);
    vec2 cell = floor(uv);
    vec2 f = fract(uv);
    vec2 rnd = hash2(cell);
    float present = step(0.4, fract(rnd.x * 5.13 + rnd.y * 9.71));
    vec2 pos = 0.25 + 0.5 * rnd;
    pos.x += 0.15 * sin(iTime * (0.6 + rnd.y) + rnd.x * 6.2831);
    float d = length(f - pos);
    return present * (1.0 - smoothstep(size * 0.3, size, d));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.y;
    float a = 0.0;
    a += 0.45 * flakes(uv + uTilt * 0.010, 14.0, 1.2, 0.10);
    a += 0.65 * flakes(uv + uTilt * 0.025 + 5.3, 9.0, 1.5, 0.12);
    a += 0.90 * flakes(uv + uTilt * 0.050 + 2.9, 5.0, 1.9, 0.14);
    a = clamp(a, 0.0, 1.0);
    fragColor = vec4(vec3(a), a);
}
