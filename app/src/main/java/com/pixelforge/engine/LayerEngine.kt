package com.pixelforge.engine

import com.pixelforge.core.LayerModel
import java.util.UUID

/** LAYER ENGINE (Blueprint #16). */
object LayerEngine {

    fun newLayer(name: String, bitmapPath: String): LayerModel = LayerModel(name = name, bitmapPath = bitmapPath)

    fun duplicate(layer: LayerModel, newBitmapPath: String): LayerModel =
        layer.copy(id = UUID.randomUUID().toString(), name = layer.name + " copy", bitmapPath = newBitmapPath)

    fun rename(layer: LayerModel, newName: String): LayerModel = layer.copy(name = newName)

    fun moveUp(layers: MutableList<LayerModel>, index: Int) {
        if (index <= 0 || index >= layers.size) return
        val tmp = layers[index - 1]; layers[index - 1] = layers[index]; layers[index] = tmp
    }

    fun moveDown(layers: MutableList<LayerModel>, index: Int) {
        if (index < 0 || index >= layers.size - 1) return
        val tmp = layers[index + 1]; layers[index + 1] = layers[index]; layers[index] = tmp
    }

    fun delete(layers: MutableList<LayerModel>, id: String) {
        layers.removeAll { it.id == id }
    }
}
