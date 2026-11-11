// Planetarium sample "ripple": a pool whose surface reacts to touch.
//
// iChannel0 is "self": the previous frame of this layer. The shader runs twice per frame:
//   uPass == 0  one step of a wave simulation, written into the feedback buffer
//               (a quarter of the screen size: "scale": 0.25 in manifest.json);
//   uPass == 1  draws the pool to the screen, bending it by the slope of the waves.
//
// The buffer is RGBA8, so each texel packs two 16-bit numbers:
//   rg = wave height now, ba = wave height one step ago.
// Packed values must not be filtered, so they are read with texelFetch only.

float unpack16(vec2 bytes) {
    vec2 b = floor(bytes * 255.0 + 0.5);
    return (b.x * 256.0 + b.y - 32768.0) / 32767.0;
}

vec2 pack16(float value) {
    float v = floor(clamp(value, -1.0, 1.0) * 32767.0 + 32768.5);
    float hi = floor(v / 256.0);
    return vec2(hi, v - hi * 256.0) / 255.0;
}

vec4 texel(ivec2 p) {
    ivec2 size = textureSize(iChannel0, 0);
    return texelFetch(iChannel0, clamp(p, ivec2(0), size - ivec2(1)), 0);
}

// A freshly created buffer is all zero, which pack16 never writes: treat it as still water.
float heightAt(ivec2 p) {
    vec4 s = texel(p);
    if (s.rg == vec2(0.0)) {
        return 0.0;
    }
    return unpack16(s.rg);
}

float previousHeightAt(ivec2 p) {
    vec4 s = texel(p);
    if (s.rg == vec2(0.0)) {
        return 0.0;
    }
    return unpack16(s.ba);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(41.3, 289.1))) * 15731.743);
}

// How strongly a disturbance centred on "centre" pushes this texel: 1 at the centre, 0 outside.
float dent(vec2 fragCoord, vec2 centre) {
    float radius = 0.012 * iResolution.y + 2.0;
    return 1.0 - smoothstep(0.0, radius, length(fragCoord - centre));
}

void simulate(out vec4 fragColor, in vec2 fragCoord) {
    ivec2 p = ivec2(fragCoord);
    float now = heightAt(p);
    float before = previousHeightAt(p);
    float around = heightAt(p + ivec2(1, 0)) + heightAt(p - ivec2(1, 0))
                 + heightAt(p + ivec2(0, 1)) + heightAt(p - ivec2(0, 1));

    // Discrete wave equation, with a little damping so ripples die out.
    float next = (around * 0.5 - before) * 0.992;
    next -= sign(next) * min(abs(next), 0.6 / 32767.0);

    // The finger pushes the surface down.
    if (uTouch.z > 0.5) {
        next = mix(next, -0.5, 0.6 * dent(fragCoord, uTouch.xy * iResolution.xy));
    }

    // A raindrop every two seconds, somewhere random, so the pool is never quite still.
    float beat = iTime * 0.5 + 0.52;
    if (fract(beat) < iTimeDelta * 0.5) {
        float n = floor(beat);
        vec2 centre = vec2(hash(vec2(n, 1.0)), hash(vec2(n, 2.0))) * iResolution.xy;
        next -= 0.45 * dent(fragCoord, centre);
    }

    fragColor = vec4(pack16(next), pack16(now));
}

vec2 slopeAt(ivec2 p) {
    return vec2(heightAt(p + ivec2(1, 0)) - heightAt(p - ivec2(1, 0)),
                heightAt(p + ivec2(0, 1)) - heightAt(p - ivec2(0, 1)));
}

vec3 poolFloor(vec2 uv) {
    vec2 p = uv * vec2(iResolution.x / iResolution.y, 1.0) * 12.0;
    vec2 cell = abs(fract(p) - 0.5);
    float grout = smoothstep(0.43, 0.48, max(cell.x, cell.y));
    vec3 tile = mix(vec3(0.02, 0.24, 0.42), vec3(0.10, 0.58, 0.72), clamp(uv.y, 0.0, 1.0));
    tile *= 0.92 + 0.16 * hash(floor(p));
    return mix(tile, tile * 0.55, grout);
}

void present(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;

    // Bilinear interpolation of the slope, done by hand because the texels are packed.
    vec2 st = uv * iChannelResolution[0].xy - 0.5;
    ivec2 i = ivec2(floor(st));
    vec2 f = fract(st);
    vec2 slope = mix(mix(slopeAt(i), slopeAt(i + ivec2(1, 0)), f.x),
                     mix(slopeAt(i + ivec2(0, 1)), slopeAt(i + ivec2(1, 1)), f.x), f.y);
    slope *= 5.0;

    // The floor seen through the water is displaced by the slope; pages scroll it sideways.
    vec2 q = uv + slope * 0.035;
    q.x += (uOffset - 0.5) * 0.25;
    vec3 col = poolFloor(q);

    // Light from the upper left: lit and shaded flanks, plus a sharp highlight.
    vec3 normal = normalize(vec3(-slope, 1.0));
    vec3 light = normalize(vec3(-0.45, 0.55, 0.7));
    col *= 1.0 + 0.9 * dot(slope, vec2(0.45, -0.55));
    col += vec3(0.9, 0.95, 1.0) * pow(max(dot(normal, light), 0.0), 48.0) * 0.6;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    if (uPass == 0) {
        simulate(fragColor, fragCoord);
    } else {
        present(fragColor, fragCoord);
    }
}
