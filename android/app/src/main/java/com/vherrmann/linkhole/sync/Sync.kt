package com.vherrmann.linkhole.sync

import com.vherrmann.linkhole.data.AddRequest
import com.vherrmann.linkhole.data.Item
import com.vherrmann.linkhole.data.OpType
import com.vherrmann.linkhole.data.PendingOp
import com.vherrmann.linkhole.data.StateRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

interface ThingApi {
    @POST("api/items")
    suspend fun add(@Body body: AddRequest): Response<Item>

    @GET("api/items")
    suspend fun list(@Query("state") state: String = "all"): Response<List<Item>>

    @PATCH("api/items/{id}")
    suspend fun setState(@Path("id") id: Long, @Body body: StateRequest): Response<Item>

    @GET("api/items/latest")
    suspend fun latest(): Response<ResponseBody>
}

object ApiFactory {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null if [serverUrl] is not a valid http(s) URL. */
    fun create(serverUrl: String, token: String): ThingApi? {
        val base = serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                )
            }
            .build()
        return try {
            Retrofit.Builder()
                .baseUrl(base)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ThingApi::class.java)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Storage operations the sync engine needs; implemented over Room in the app. */
interface LocalStore {
    suspend fun pendingOps(): List<PendingOp>
    suspend fun deleteOp(seq: Long)

    /** Swap a local placeholder item for the server's, retargeting queued ops. */
    suspend fun replaceLocalItem(localId: Long, server: Item)

    /** Drop a placeholder item and every queued op that refers to it. */
    suspend fun discardLocalItem(localId: Long)

    /** Make the cache mirror [items]: upsert them, delete other server-backed rows. */
    suspend fun applySnapshot(items: List<Item>)
}

enum class SyncResult { OK, RETRY, UNAUTHORIZED }

/**
 * Flushes queued writes in order, then pulls a full snapshot. Last write wins
 * (single user). A full snapshot is used instead of `updated_since` so that
 * deletions on the server are noticed too.
 */
class SyncEngine(private val api: ThingApi, private val store: LocalStore) {
    private enum class Outcome { DONE, RETRY, UNAUTHORIZED }

    suspend fun sync(): SyncResult {
        try {
            // Re-read after every op: a successful ADD retargets later ops to the server id.
            while (true) {
                val op = store.pendingOps().firstOrNull() ?: break
                when (flush(op)) {
                    Outcome.DONE -> {}
                    Outcome.RETRY -> return SyncResult.RETRY
                    Outcome.UNAUTHORIZED -> return SyncResult.UNAUTHORIZED
                }
            }
            val resp = api.list("all")
            val body = resp.body()
            return when {
                resp.isSuccessful && body != null -> {
                    store.applySnapshot(body)
                    SyncResult.OK
                }
                else -> classify(resp.code())
            }
        } catch (e: IOException) {
            return SyncResult.RETRY
        } catch (e: SerializationException) {
            return SyncResult.RETRY
        }
    }

    private fun classify(code: Int) = when {
        code == 401 || code == 403 -> SyncResult.UNAUTHORIZED
        else -> SyncResult.RETRY
    }

    private suspend fun flush(op: PendingOp): Outcome {
        val resp = when (op.type) {
            OpType.ADD -> api.add(AddRequest(op.url!!, op.note))
            OpType.SET_STATE -> {
                if (op.itemId < 0) { // its ADD was rejected; nothing to update remotely
                    store.deleteOp(op.seq)
                    return Outcome.DONE
                }
                api.setState(op.itemId, StateRequest(op.state!!))
            }
        }
        val body = resp.body()
        when {
            resp.isSuccessful && body != null -> {
                if (op.type == OpType.ADD) store.replaceLocalItem(op.itemId, body)
                store.deleteOp(op.seq)
            }
            resp.code() == 401 || resp.code() == 403 -> return Outcome.UNAUTHORIZED
            resp.code() >= 500 -> return Outcome.RETRY
            else -> {
                // 4xx: the server will never accept this op (bad URL, item deleted, ...).
                if (op.type == OpType.ADD) store.discardLocalItem(op.itemId) else store.deleteOp(op.seq)
            }
        }
        return Outcome.DONE
    }
}
