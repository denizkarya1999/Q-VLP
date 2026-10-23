package com.developer27.qvlp.capture

/** One action per physical press. Ignore held-key repeats and releases after losing focus. */
internal class CaptureTrigger {
    private val down = mutableSetOf<Pair<Int, Int>>()
    fun reset() = down.clear()
    fun press(device: Int, key: Int, repeat: Int): Boolean {
        val first = down.add(device to key)
        return first && repeat == 0
    }
    fun release(device: Int, key: Int) { down.remove(device to key) }
}
