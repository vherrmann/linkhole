package com.vherrmann.linkhole

import com.vherrmann.linkhole.data.Item
import com.vherrmann.linkhole.data.OpType
import com.vherrmann.linkhole.data.PendingOp
import com.vherrmann.linkhole.sync.ApiFactory
import com.vherrmann.linkhole.sync.LocalStore
import com.vherrmann.linkhole.sync.SyncEngine
import com.vherrmann.linkhole.sync.SyncResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeStore : LocalStore {
    val ops = mutableListOf<PendingOp>()
    val items = mutableMapOf<Long, Item>()

    override suspend fun pendingOps() = ops.sortedBy { it.seq }
    override suspend fun deleteOp(seq: Long) { ops.removeAll { it.seq == seq } }
    override suspend fun replaceLocalItem(localId: Long, server: Item) {
        items.remove(localId)
        items[server.id] = server
        ops.replaceAll { if (it.itemId == localId) it.copy(itemId = server.id) else it }
    }
    override suspend fun discardLocalItem(localId: Long) {
        items.remove(localId)
        ops.removeAll { it.itemId == localId }
    }
    override suspend fun applySnapshot(items: List<Item>) {
        this.items.keys.removeAll { it >= 0 && none(items, it) }
        items.forEach { this.items[it.id] = it }
    }
    private fun none(items: List<Item>, id: Long) = items.none { it.id == id }
}

class SyncEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var engine: SyncEngine

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeStore()
        engine = SyncEngine(ApiFactory.create(server.url("/").toString(), "tok")!!, store)
    }

    @After fun tearDown() = server.shutdown()

    private fun itemJson(id: Long, url: String, state: String = "unread") =
        """{"id":$id,"url":"$url","title":null,"note":null,"state":"$state","created_at":1,"updated_at":1,"title_fetched":false,"extra":1}"""

    private fun enqueue(code: Int, body: String = "") =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    @Test fun flushesInOrderAndSwapsTempId() = runBlocking {
        store.items[-1] = Item(-1, "https://a/", createdAt = 5, updatedAt = 5)
        store.ops += PendingOp(1, OpType.ADD, -1, url = "https://a/")
        store.ops += PendingOp(2, OpType.SET_STATE, -1, state = "read")
        enqueue(201, itemJson(42, "https://a/"))
        enqueue(200, itemJson(42, "https://a/", "read"))
        enqueue(200, "[" + itemJson(42, "https://a/", "read") + "]")

        assertEquals(SyncResult.OK, engine.sync())

        val add = server.takeRequest()
        assertEquals("POST", add.method)
        assertEquals("Bearer tok", add.getHeader("Authorization"))
        assertEquals("""{"url":"https://a/"}""", add.body.readUtf8())
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/items/42", patch.path)
        assertEquals("""{"state":"read"}""", patch.body.readUtf8())
        assertEquals("/api/items?state=all", server.takeRequest().path)

        assertTrue(store.ops.isEmpty())
        assertEquals(setOf(42L), store.items.keys)
        assertEquals("read", store.items[42]!!.state)
    }

    @Test fun networkFailureKeepsOpsForRetry() = runBlocking {
        store.ops += PendingOp(1, OpType.SET_STATE, 7, state = "read")
        server.shutdown()
        assertEquals(SyncResult.RETRY, engine.sync())
        assertEquals(1, store.ops.size)
    }

    @Test fun serverErrorRetriesAndStopsAtFirstFailure() = runBlocking {
        store.ops += PendingOp(1, OpType.SET_STATE, 7, state = "read")
        store.ops += PendingOp(2, OpType.SET_STATE, 8, state = "read")
        enqueue(503)
        assertEquals(SyncResult.RETRY, engine.sync())
        assertEquals(2, store.ops.size)
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthorizedStops() = runBlocking {
        store.ops += PendingOp(1, OpType.SET_STATE, 7, state = "read")
        enqueue(401, """{"error":"unauthorized"}""")
        assertEquals(SyncResult.UNAUTHORIZED, engine.sync())
        assertEquals(1, store.ops.size)
    }

    @Test fun rejectedAddIsDiscardedWithDependentOps() = runBlocking {
        store.items[-1] = Item(-1, "bad", createdAt = 1, updatedAt = 1)
        store.ops += PendingOp(1, OpType.ADD, -1, url = "bad")
        store.ops += PendingOp(2, OpType.SET_STATE, -1, state = "read")
        enqueue(400, """{"error":"bad url"}""")
        enqueue(200, "[]")
        assertEquals(SyncResult.OK, engine.sync())
        assertTrue(store.ops.isEmpty())
        assertTrue(store.items.isEmpty())
        assertEquals(2, server.requestCount) // ADD + list; no PATCH for a placeholder id
    }

    @Test fun patchOfDeletedItemIsDropped() = runBlocking {
        store.ops += PendingOp(1, OpType.SET_STATE, 9, state = "read")
        enqueue(404, """{"error":"not found"}""")
        enqueue(200, "[]")
        assertEquals(SyncResult.OK, engine.sync())
        assertTrue(store.ops.isEmpty())
    }

    @Test fun snapshotRemovesServerDeletedItemsButKeepsPlaceholders() = runBlocking {
        store.items[5] = Item(5, "https://gone/", createdAt = 1, updatedAt = 1)
        store.items[-1] = Item(-1, "https://local/", createdAt = 2, updatedAt = 2)
        enqueue(200, "[" + itemJson(6, "https://new/") + "]")
        assertEquals(SyncResult.OK, engine.sync())
        assertEquals(setOf(-1L, 6L), store.items.keys)
    }
}
