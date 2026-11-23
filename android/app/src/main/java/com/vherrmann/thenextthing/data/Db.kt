package com.vherrmann.thenextthing.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.vherrmann.thenextthing.sync.LocalStore
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE state = 'unread' ORDER BY createdAt DESC, id DESC")
    fun observeUnread(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE :state = 'all' OR state = :state ORDER BY createdAt DESC, id DESC")
    fun observe(state: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): Item?

    @Query("SELECT * FROM items WHERE url = :url")
    suspend fun getByUrl(url: String): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Item)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Item>)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("SELECT id FROM items")
    suspend fun allIds(): List<Long>

    @Query("SELECT MIN(id) FROM items")
    suspend fun minId(): Long?

    @Query("UPDATE items SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: String, now: Long)

    @Insert
    suspend fun insertOp(op: PendingOp)

    @Query("SELECT * FROM pending_ops ORDER BY seq")
    suspend fun ops(): List<PendingOp>

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeOpCount(): Flow<Int>

    @Query("DELETE FROM pending_ops WHERE seq = :seq")
    suspend fun deleteOp(seq: Long)

    @Query("DELETE FROM pending_ops WHERE itemId = :itemId")
    suspend fun deleteOpsFor(itemId: Long)

    @Query("UPDATE pending_ops SET itemId = :newId WHERE itemId = :oldId")
    suspend fun retargetOps(oldId: Long, newId: Long)
}

@Database(entities = [Item::class, PendingOp::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "the-next-thing.db").build()
    }
}

class RoomLocalStore(private val db: AppDatabase) : LocalStore {
    private val dao = db.dao()

    override suspend fun pendingOps() = dao.ops()

    override suspend fun deleteOp(seq: Long) = dao.deleteOp(seq)

    override suspend fun replaceLocalItem(localId: Long, server: Item) = db.withTransaction {
        dao.deleteItem(localId)
        dao.upsert(server)
        dao.retargetOps(localId, server.id)
    }

    override suspend fun discardLocalItem(localId: Long) = db.withTransaction {
        dao.deleteItem(localId)
        dao.deleteOpsFor(localId)
    }

    override suspend fun applySnapshot(items: List<Item>) = db.withTransaction {
        val serverIds = items.mapTo(HashSet()) { it.id }
        dao.allIds().filter { it >= 0 && it !in serverIds }.forEach { dao.deleteItem(it) }
        dao.upsertAll(items)
    }
}
