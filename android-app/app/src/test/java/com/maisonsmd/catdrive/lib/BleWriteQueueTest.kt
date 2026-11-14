package com.maisonsmd.catdrive.lib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BleWriteQueueTest {
    @Test
    fun addingOverwriteItemReplacesPendingItemsWithSameUuid() {
        val queue = BleWriteQueue()
        queue.add(BleWriteQueue.QueueItem("nav", byteArrayOf(1)))
        queue.add(BleWriteQueue.QueueItem("settings", byteArrayOf(2)))
        queue.add(BleWriteQueue.QueueItem("nav", byteArrayOf(3)))

        assertEquals(2, queue.size)
        assertEquals("settings", queue.pop().uuid)
        val latestNavigation = queue.pop()
        assertEquals("nav", latestNavigation.uuid)
        assertArrayEquals(byteArrayOf(3), latestNavigation.data)
    }

    @Test
    fun addingNonOverwriteItemKeepsEarlierItems() {
        val queue = BleWriteQueue()
        queue.add(BleWriteQueue.QueueItem("icon", byteArrayOf(1), overwrite = false))
        queue.add(BleWriteQueue.QueueItem("icon", byteArrayOf(2), overwrite = false))

        assertEquals(2, queue.size)
        assertArrayEquals(byteArrayOf(1), queue.pop().data)
        assertArrayEquals(byteArrayOf(2), queue.pop().data)
    }

    @Test
    fun clearRemovesAllPendingItems() {
        val queue = BleWriteQueue()
        queue.add(BleWriteQueue.QueueItem("nav", byteArrayOf(1)))

        queue.clear()

        assertEquals(0, queue.size)
    }
}
