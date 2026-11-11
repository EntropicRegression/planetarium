// Floating light dust: soft glowing motes drifting upwards. Meant for "blend": "add".
vec2 hash2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

float motes(vec2 uv, float scale, float speed, float size) {
    uv *= scale;
    uv.y -= iTime * speed;
    vec2 cell = floor(uv);
    vec2 f = fract(uv);
    vec2 rnd = hash2(cell);
    // Only some cells hold a mote, each wandering a little around its own spot.
    float present = step(0.55, fract(rnd.x * 7.31 + rnd.y * 3.17));
    vec2 pos = 0.25 + 0.5 * rnd;
    pos.x += 0.12 * sin(iTime * (0.3 + rnd.y) + rnd.x * 6.2831);
    float twinkle = 0.6 + 0.4 * sin(iTime * (0.8 + rnd.x * 1.5) + rnd.y * 6.2831);
    float d = length(f - pos);
    return present * twinkle * (1.0 - smoothstep(0.0, size, d));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.y;
    // Nearer motes are larger, faster and slide more with tilt.
    float glow = 0.0;
    glow += 0.45 * motes(uv + uTilt * 0.010, 9.0, 0.020, 0.10);
    glow += 0.65 * motes(uv + uTilt * 0.025 + 3.7, 6.0, 0.035, 0.13);
    glow += 0.90 * motes(uv + uTilt * 0.050 + 8.1, 3.5, 0.055, 0.16);
    vec3 color = vec3(1.0, 0.86, 0.62) * glow * 0.55;
    fragColor = vec4(color, 0.0);
}
