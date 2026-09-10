package com.pixelforge.engine

import com.pixelforge.core.AnimationModel
import com.pixelforge.core.ReferenceBoundary

/** ANIMATION ENGINE (Blueprint #28-32). */
object AnimationEngine {

    fun createAnimation(name: String, fps: Int = 10, loop: Boolean = true): AnimationModel =
        AnimationModel(name = name, fps = fps.coerceIn(1, 30), loop = loop)

    fun addFrame(anim: AnimationModel, frameId: String) {
        anim.frameIds.add(frameId)
    }

    /** CAPTURE FRAME (Blueprint #30): appends a newly created frame id to the active animation. */
    fun captureFrame(anim: AnimationModel, newFrameId: String) {
        anim.frameIds.add(newFrameId)
    }

    fun reorderFrames(anim: AnimationModel, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in anim.frameIds.indices || toIndex !in anim.frameIds.indices) return
        val item = anim.frameIds.removeAt(fromIndex)
        anim.frameIds.add(toIndex, item)
    }

    fun removeFrame(anim: AnimationModel, frameId: String) {
        anim.frameIds.remove(frameId)
    }

    fun setFps(anim: AnimationModel, fps: Int) {
        anim.fps = fps.coerceIn(1, 30)
    }

    fun setReference(anim: AnimationModel, reference: ReferenceBoundary?) {
        anim.reference = reference
    }

    /** Frame duration in milliseconds for playback timing. */
    fun frameDurationMs(anim: AnimationModel): Long = (1000f / anim.fps).toLong().coerceAtLeast(1L)
}
