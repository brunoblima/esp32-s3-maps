package com.maisonsmd.catdrive.lib

import com.maisonsmd.catdrive.lib.BleWriteQueue.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleWriteDispatcherTest {
    @Test
    fun serializesWritesUntilThePreviousWriteCompletes() {
        val sent = mutableListOf<String>()
        val dispatcher = BleWriteDispatcher { item ->
            sent.add(item.uuid)
            true
        }

        dispatcher.enqueue(QueueItem("first", byteArrayOf(1)))
        dispatcher.enqueue(QueueItem("second", byteArrayOf(2)))

        assertEquals(listOf("first"), sent)
        assertTrue(dispatcher.isSending)

        dispatcher.onWriteCompleted()

        assertEquals(listOf("first", "second"), sent)
        assertTrue(dispatcher.isSending)
    }

    @Test
    fun continuesAfterAWriteCannotBeStarted() {
        val sent = mutableListOf<String>()
        val dispatcher = BleWriteDispatcher { item ->
            sent.add(item.uuid)
            item.uuid != "failed"
        }

        dispatcher.enqueue(QueueItem("active", byteArrayOf(1)))
        dispatcher.enqueue(QueueItem("failed", byteArrayOf(2)))
        dispatcher.enqueue(QueueItem("next", byteArrayOf(3)))

        dispatcher.onWriteCompleted()

        assertEquals(listOf("active", "failed", "next"), sent)
        assertTrue(dispatcher.isSending)
    }

    @Test
    fun resetClearsPendingWritesAndSendingState() {
        val sent = mutableListOf<String>()
        val dispatcher = BleWriteDispatcher { item ->
            sent.add(item.uuid)
            true
        }

        dispatcher.enqueue(QueueItem("active", byteArrayOf(1)))
        dispatcher.enqueue(QueueItem("pending", byteArrayOf(2)))
        dispatcher.reset()
        dispatcher.onWriteCompleted()

        assertEquals(listOf("active"), sent)
        assertFalse(dispatcher.isSending)
    }
}
