package com.maisonsmd.catdrive.lib

import com.maisonsmd.catdrive.lib.BleWriteQueue.QueueItem

class BleWriteDispatcher(private val send: (QueueItem) -> Boolean) {
    private val queue = BleWriteQueue()
    var isSending: Boolean = false
        private set

    fun enqueue(item: QueueItem) {
        queue.add(item)
        dispatchNext()
    }

    fun onWriteCompleted() {
        isSending = false
        dispatchNext()
    }

    fun reset() {
        isSending = false
        queue.clear()
    }

    private fun dispatchNext() {
        while (!isSending && queue.size > 0) {
            val item = queue.pop()
            if (send(item)) {
                isSending = true
            }
        }
    }
}
