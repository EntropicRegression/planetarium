// Slanted rain streaks in two depth layers. Output is premultiplied, for "blend": "normal".
float hash1(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float streaks(vec2 uv, float columns, float speed, float len) {
    // Lean the rain a little.
    uv.x += uv.y * 0.12;
    float col = floor(uv.x * columns);
    float fx = fract(uv.x * columns);
    float rnd = hash1(vec2(col, columns));
    float present = step(0.45, rnd);
    // Each column has its own phase and speed; fragCoord's y points up, so adding time falls.
    float y = fract(uv.y * 0.9 + iTime * speed * (0.8 + 0.4 * rnd) + rnd * 17.0);
    float body = smoothstep(0.0, len, y) * (1.0 - smoothstep(len, len + 0.02, y));
    float thin = 1.0 - smoothstep(0.0, 0.5, abs(fx - 0.5) * 6.0);
    return present * body * thin;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.y;
    float a = 0.0;
    a += 0.25 * streaks(uv + uTilt * 0.015, 70.0, 1.6, 0.10);
    a += 0.40 * streaks(uv + uTilt * 0.040 + 4.2, 40.0, 2.1, 0.16);
    a = clamp(a, 0.0, 1.0);
    fragColor = vec4(vec3(0.82, 0.88, 1.0) * a, a);
}
