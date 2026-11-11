package app.wallengine.engine

import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/** Placeholder: a user-supplied fragment shader drawn as a layer. Implemented in milestone M2. */
class ShaderLayer(pack: WallpaperPack, spec: LayerSpec, host: LayerHost, maxTextureSize: Int) : Layer {
    override val animated = true

    init {
        throw UnsupportedOperationException("shader layers are not implemented yet")
    }

    override fun draw(frame: FrameContext) {}
    override fun release() {}
}
