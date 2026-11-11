package app.wallengine.engine

import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/** Placeholder: a looping video drawn as a layer. Implemented in milestone M1. */
class VideoLayer(pack: WallpaperPack, spec: LayerSpec, host: LayerHost) : Layer {
    override val animated = true

    init {
        throw UnsupportedOperationException("video layers are not implemented yet")
    }

    override fun draw(frame: FrameContext) {}
    override fun release() {}
}
