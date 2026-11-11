# Planetarium pack format

A wallpaper pack is a directory (or a `.zip` of one) holding `manifest.json` and the files it
refers to. Packs contain data only: JSON, GLSL text, images and video. Nothing in a pack is ever
run as code other than its fragment shaders, which run on the GPU.

```
my-pack/
  manifest.json     required
  thumb.jpg         optional preview for the library grid
  params.json       optional current values of the pack's parameters
  ...assets         shaders, images, videos; sub-folders are fine
```

## manifest.json

```json
{
  "format": 1,
  "name": "My wallpaper",
  "layers": [
    { "type": "image",  "src": "sky.jpg",     "depth": 1.0 },
    { "type": "shader", "src": "clouds.frag", "depth": 0.5, "blend": "add" }
  ],
  "params": [
    { "id": "speed", "type": "float", "min": 0, "max": 2, "default": 1 }
  ]
}
```

| Key      | Type   | Meaning |
|----------|--------|---------|
| `format` | int    | Format version, currently `1`. A pack with a higher number is refused. |
| `name`   | string | Shown in the library. Defaults to the directory name. |
| `layers` | array  | The layers of the scene, see below. |
| `params` | array  | Optional user parameters for shader layers, see [Parameters](#parameters). |
| `parallax` | object | Optional tilt parallax settings, see [Parallax](#parallax). |
| `canvas` | object | Optional `{ "width": 1080, "height": 2400 }`: the shape the pack was laid out on. Needed to place video layers (see [`video`](#video)); image layers made for that canvas should simply have its aspect ratio. |

Every layer has:

| Key     | Type   | Meaning |
|---------|--------|---------|
| `type`  | string | `"image"`, `"video"` or `"shader"`. Required. |
| `src`   | string | Path of the layer's file, relative to the pack root. Must stay inside the pack. Required. |
| `depth` | float  | `1.0` = far background (default), `0.0` = nearest. Layers are drawn from the highest depth to the lowest. |

Layers are composited with premultiplied alpha. A layer that fails to load is skipped; the rest
of the pack still shows.

## Parallax

Tilting the phone slides image and video layers according to their `depth`, which is what makes
a layered pack look spatial.

```json
"parallax": { "strength": 0.04, "focus": 1.0 }
```

| Key        | Type  | Meaning |
|------------|-------|---------|
| `strength` | float | Slide at full tilt (about 17 degrees) per unit of depth difference, as a fraction of the screen. `0`..`0.2`, default `0.04`. `0` turns parallax off. |
| `focus`    | float | The depth that stays still. Default `1.0`: the far background is fixed and nearer layers move. With `0.5`, far and near layers move in opposite directions. |

A layer at depth `d` slides by `tilt * strength * (focus - d)`. Each layer is zoomed in just
enough that its largest slide never shows an edge, so foreground layers should be transparent
PNG/WebP with some empty space around the subject. Whatever pose the phone is held in becomes
the neutral one after a few seconds. The motion sensor runs only while a visible pack uses it.

## Layer types

### `image`

A still image (anything Android decodes: JPEG, PNG, WebP, ...), scaled to cover the screen. The
part that does not fit horizontally is used to pan with the launcher's page scroll.

| Option     | Type   | Meaning |
|------------|--------|---------|
| `depthMap` | string | Path of a greyscale image of the same framing: white = near, black = far. The image is then displaced per pixel by its depth instead of sliding as a whole, giving a single photo continuous parallax. The layer's own `depth` is ignored. |
| `sprites`  | array  | Up to 4 small animated patches drawn over the image, see below. A layer with sprites redraws continuously (at most 30 times a second). |

Each sprite is a strip image holding its frames side by side, all the same size. Inside its
rectangle the current frame **replaces** the image, transparency included, so a frame must
contain everything that should be visible there, not only the moving part.

| Sprite key | Type   | Meaning |
|------------|--------|---------|
| `src`      | string | Path of the strip image. |
| `frames`   | number | Frames in the strip (default 1). |
| `x`, `y`   | number | Top-left corner of the patch, in pixels of the layer's image file. One frame is drawn at its own pixel size, so strip and image must share a scale. |
| `play`     | string | `loop` (default): frame after frame at `fps`. `swing`: back and forth through the frames once per `period` seconds, easing at both ends. `sometimes`: rests on frame 0 and plays the other frames once at `fps`, about once per `every` seconds at an irregular moment. |
| `fps`      | number | Frames per second for `loop` and `sometimes` (default 8). |
| `period`   | number | Seconds for one full swing (default 2). |
| `every`    | number | Seconds between `sometimes` plays, on average (default 6). |

```json
{ "type": "image", "src": "balcony.png", "depth": 0.05, "sprites": [
  { "src": "cat-tail.png", "frames": 7, "x": 0, "y": 1812, "play": "swing", "period": 3.2 },
  { "src": "cat-ears.png", "frames": 5, "x": 108, "y": 1620, "play": "sometimes", "fps": 9, "every": 7 }
] }
```

### `video`

A video file, played muted. It redraws at most 30 times a second and pauses while the
wallpaper is hidden or battery saver is on. Video has no transparency; use `blend` to overlay
footage shot on black.

| Option     | Type   | Meaning |
|------------|--------|---------|
| `loop`     | bool   | Whether playback loops. Default `true`. |
| `x`, `y`   | float  | Offset of the video's centre from the canvas centre, as fractions of the canvas width and height (y downwards). Default `0`. |
| `scale`    | float  | Size relative to the smallest size that covers the canvas. Default `1`. |
| `rotation` | float  | Clockwise rotation in degrees. Default `0`. |
| `opacity`  | float  | `0`..`1`. Default `1`. |
| `blend`    | string | `"normal"` (default), `"add"` or `"screen"`. The last two make black transparent, for sparks, glows, light leaks and similar overlay footage. |

Without a `canvas` in the manifest the video itself is the canvas: it covers the screen and pans
with the launcher's page scroll, and the placement options are of little use. With a `canvas`,
the video is placed on it by the options above, and the canvas is what covers the screen.
The file is played as it is: an imported pack is not transcoded, so keep videos near screen
resolution.

### `shader`

A GLSL ES 3.00 fragment shader drawn over the whole screen.

| Option     | Type   | Default    | Meaning |
|------------|--------|------------|---------|
| `channels` | array  | none       | Up to 4 entries, bound to `iChannel0..3`. Each is an image path inside the pack, or `"self"`. See [Channels](#channels-and-feedback). |
| `scale`    | float  | `1`        | `0.25`..`1`. Renders at this fraction of the screen resolution and upscales. Use it for heavy shaders. |
| `blend`    | string | `"normal"` | `"normal"`: the output is premultiplied-alpha colour laid over the layers behind (alpha 1 = opaque). `"add"`: the output is added to them. |

## Writing shaders

Two styles are accepted. Which one a file is gets decided from its text (comments ignored):

**Shadertoy style** - the file defines `mainImage` and no `main`:

```glsl
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime), 1.0);
}
```

The app puts `#version 300 es`, `precision highp float/int`, all uniforms listed below and a
`main()` around it. Do not write a `#version` line. Compiler errors are reported with the line
numbers of your file. `fragColor` starts as opaque black.

**Complete shader** - the file's first line is `#version 300 es` and it defines `main()`. It is
compiled as it is, so it must declare its precision, the uniforms it uses (same names and types
as below) and an `out vec4` for the colour. The pixel position is `gl_FragCoord.xy`; an
`in vec2 vUv;` (0..1, origin bottom-left) is available as well.

Either way this is GLSL ES 3.00: `texture()` rather than `texture2D()`, no implicit int/float
conversion (`1.0`, not `1`).

Unlike on Shadertoy, alpha matters: with `"blend": "normal"` write alpha `1.0` for an opaque
pixel, and multiply the colour by alpha yourself for translucent ones.

### Uniforms

| Uniform | Type | Value |
|---------|------|-------|
| `iResolution` | `vec3` | Size in pixels of what is being rendered (smaller than the screen when `scale` < 1); z is 1. |
| `iTime` | `float` | Seconds since the wallpaper started. |
| `iTimeDelta` | `float` | Seconds since the previous frame, at most 0.1. |
| `iFrame` | `int` | Frames drawn so far. |
| `iMouse` | `vec4` | Touch, Shadertoy conventions: pixels, origin bottom-left. xy = last position touched; zw = where the current/last touch began, z positive while touching, w positive only on the frame the touch began. All zero until the first touch. |
| `iChannel0..3` | `sampler2D` | The layer's channels. |
| `iChannelResolution[4]` | `vec3` | Pixel size of each channel (z is 1); zero for an unused channel. |
| `uOffset` | `float` | Launcher page scroll, 0..1 (0.5 = centred). |
| `uTouch` | `vec3` | xy = last touch position normalised to 0..1, origin bottom-left; z = 1 while touching, else 0. |
| `uTilt` | `vec2` | Device tilt away from its resting pose, each axis -1..1. x > 0: right edge turned away from the viewer; y > 0: top edge turned towards them. Shader layers get no automatic parallax; use this to do your own. |
| `uPass` | `int` | Feedback layers only, see below. 0 otherwise. |
| *param id* | `float` | One per entry of the manifest's `params`. |

A shader that uses none of `iTime`, `iTimeDelta`, `iFrame` and has no `"self"` channel is not
animated: it is redrawn only when the page scroll or touch changes, which costs no battery
otherwise. Animated shaders are drawn at up to 30 fps.

Touches reach the wallpaper only where the launcher lets them through (empty home-screen areas).

### Channels and feedback

```json
{ "type": "shader", "src": "fx.frag", "channels": ["noise.png", "self"] }
```

- **Image path**: the image is loaded as a texture with repeat wrapping and mipmaps. As on
  Shadertoy, `texture(iChannel0, vec2(0.0))` is the bottom-left corner of the image. Colours are
  premultiplied by alpha.
- **`"self"`**: the layer's own previous frame. The shader renders into an RGBA8 buffer
  (`scale` x screen size, linear filtering, clamped at the edges, transparent black at first)
  and reads the previous contents of that buffer through the channel. The buffer stores exactly
  what the shader writes, with no blending, so its four channels can hold state. It is resized,
  keeping its contents (nearest sampling), when the screen size or render scale changes.

What reaches the screen from a feedback layer:

- If the shader never reads `uPass`, the buffer itself is shown (trails, smearing, blur-over-time
  effects).
- If the shader reads `uPass`, it runs twice per frame: once with `uPass == 0` to write the
  buffer, then with `uPass == 1` at full screen resolution to produce the picture, with the
  `"self"` channel now holding the buffer just written. `iResolution` is the buffer size in pass
  0 and the screen size in pass 1; `iChannelResolution` gives the buffer size in both. This is
  how a simulation (pass 0) is kept apart from how it looks (pass 1).

`assets/samples/ripple` in the app's source is a complete example: a wave simulation packed into
the buffer, presented as a rippling pool.

### Parameters

```json
"params": [ { "id": "speed", "type": "float", "min": 0, "max": 2, "default": 1 } ]
```

Each entry becomes a `float` uniform named exactly by its `id`, available to every shader layer
of the pack (declared for you in Shadertoy style; declare `uniform float speed;` yourself in a
complete shader). `id` must be a GLSL identifier and must not clash with the uniforms above.
`type` is `"float"`, the only type so far. The value is taken from `params.json` in the pack
root if present, otherwise `default`, and kept within `min`..`max`:

```json
{ "speed": 1.3 }
```

Values are read when the wallpaper loads the pack.

### Performance

Shaders run for every pixel of a phone screen, every frame. If an animated shader layer keeps
missing its frame time, the app lowers that layer's render scale on its own, in steps, down to
0.25. Setting a `scale` yourself gives a predictable result. With a feedback layer that uses
`uPass`, only pass 0 is scaled.

## Importing

- **Single shader file** (`.frag`, `.glsl`, `.fs`, `.txt`): becomes a pack with one shader layer,
  no channels or parameters.
- **Plugin pack** (`.zip`): `manifest.json` at the root of the archive, or inside a single
  top-level folder. Limits: 300 MB unpacked, 2000 entries. Only data files are kept: `.json`,
  `.txt`, `.md`, shader sources (`.frag`, `.glsl`, `.fs`, `.vert`), images, videos, and files
  without an extension; anything else, and anything that looks like a program, is left out.
  Every layer's `src` must exist.

Shader layers are compiled during import. If one fails, the pack is not added and the compiler
log is shown. If such a pack has no `thumb.jpg`, one is rendered from its image and shader
layers.

## Minimal complete example

`manifest.json`:

```json
{
  "format": 1,
  "name": "Pulse",
  "layers": [
    { "type": "shader", "src": "pulse.frag" }
  ],
  "params": [
    { "id": "speed", "type": "float", "min": 0, "max": 4, "default": 1 }
  ]
}
```

`pulse.frag`:

```glsl
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = (fragCoord - 0.5 * iResolution.xy) / iResolution.y;
    uv.x += (uOffset - 0.5) * 0.3;
    float ring = 0.5 + 0.5 * sin(length(uv) * 20.0 - iTime * 2.0 * speed);
    vec3 col = mix(vec3(0.05, 0.08, 0.20), vec3(0.20, 0.70, 0.90), ring);
    fragColor = vec4(col, 1.0);
}
```

Zip the two files and import the archive as a plugin pack.
