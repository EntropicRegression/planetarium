// Chunky pixel stars that twinkle in the upper sky. Meant for "blend": "add" and a low
// render scale: every star is one cell of a coarse grid, so there is nothing fine to resolve.
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    // 90 cells across the screen: two of the artwork's pixels per star.
    float cells = 90.0;
    vec2 grid = floor(fragCoord / iResolution.x * cells);
    float rows = cells * iResolution.y / iResolution.x;

    // About one cell in 140 holds a star: a few dozen on screen, on top of the painted ones.
    float star = step(0.993, hash(grid));
    float phase = hash(grid + 17.0) * 6.2831;
    float speed = 0.5 + hash(grid + 5.0) * 1.6;
    // Brightness moves in four steps rather than smoothly, like a sprite animation.
    float level = floor((0.5 + 0.5 * sin(iTime * speed + phase)) * 4.0) / 3.0;

    // Only the sky: fragCoord's y points up, so the top of the screen is the large values.
    float sky = smoothstep(0.58, 0.78, grid.y / rows);

    vec3 color = vec3(0.85, 0.92, 1.0) * star * clamp(level, 0.0, 1.0) * sky * 0.85;
    fragColor = vec4(color, 0.0);
}
