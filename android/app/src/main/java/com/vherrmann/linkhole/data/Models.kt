package com.vherrmann.linkhole.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object State {
    const val UNREAD = "unread"
    const val READ = "read"
    const val UNINTERESTING = "uninteresting"
    const val ALL = "all"
}

/** A queued link. Negative ids are local placeholders awaiting an ADD sync. */
@Serializable
@Entity(tableName = "items", indices = [Index(value = ["url"], unique = true)])
data class Item(
    @PrimaryKey val id: Long,
    val url: String,
    val title: String? = null,
    val note: String? = null,
    val state: String = State.UNREAD,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("title_fetched") val titleFetched: Boolean = false,
) {
    val isLocalOnly get() = id < 0
}

enum class OpType { ADD, SET_STATE }

/** A queued write, flushed to the server in `seq` order. */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val type: OpType,
    /** For ADD this is the local placeholder id. */
    val itemId: Long,
    val url: String? = null,
    val note: String? = null,
    val state: String? = null,
)

@Serializable
data class AddRequest(val url: String, val note: String? = null)

@Serializable
data class StateRequest(val state: String)
