package com.patjackson.latertext.data.impl.repository

import androidx.room.withTransaction
import com.patjackson.latertext.data.api.AttachmentAssetRecord
import com.patjackson.latertext.data.api.AttachmentRepository
import com.patjackson.latertext.data.api.AttachmentStore
import com.patjackson.latertext.data.api.AttachmentWriteRequest
import com.patjackson.latertext.data.api.InvalidAttachmentException
import com.patjackson.latertext.data.impl.db.LaterTextDatabase
import com.patjackson.latertext.data.impl.db.toEntity
import com.patjackson.latertext.data.impl.db.toRecord
import java.io.InputStream

class RoomAttachmentRepository(
    private val database: LaterTextDatabase,
    private val store: AttachmentStore,
) : AttachmentRepository {
    override suspend fun stage(request: AttachmentWriteRequest): AttachmentAssetRecord {
        val staged = store.stage(request)
        try {
            database.attachmentAssetDao().upsert(staged.toEntity())
        } catch (failure: Throwable) {
            store.delete(staged)
            throw failure
        }
        return staged
    }

    override suspend fun promoteToDurable(
        assetId: String,
        updatedAtEpochMillis: Long,
    ): AttachmentAssetRecord {
        val current = database.attachmentAssetDao().get(assetId)?.toRecord()
            ?: throw InvalidAttachmentException("Attachment metadata is missing")
        val promoted = store.promoteToDurable(current, updatedAtEpochMillis)
        database.attachmentAssetDao().upsert(promoted.toEntity())
        return promoted
    }

    override suspend fun get(assetId: String): AttachmentAssetRecord? =
        database.attachmentAssetDao().get(assetId)?.toRecord()

    override suspend fun open(assetId: String): InputStream {
        val asset = get(assetId) ?: throw InvalidAttachmentException("Attachment metadata is missing")
        return store.open(asset)
    }

    override suspend fun removeIfUnreferenced(assetId: String): Boolean {
        val deleted = database.withTransaction {
            val asset = database.attachmentAssetDao().get(assetId) ?: return@withTransaction null
            if (database.attachmentAssetDao().deleteIfUnreferenced(assetId) == 1) asset else null
        } ?: return false
        return store.delete(deleted.toRecord())
    }

    override suspend fun cleanExpiredStaging(nowEpochMillis: Long): Int {
        store.deleteExpiredStaging(nowEpochMillis)
        val candidates = database.attachmentAssetDao().findExpiredUnreferencedStaging(nowEpochMillis)
        var deletedCount = 0
        for (candidate in candidates) {
            val removed = database.withTransaction {
                database.attachmentAssetDao().deleteIfUnreferenced(candidate.id) == 1
            }
            if (removed) {
                store.delete(candidate.toRecord())
                deletedCount++
            }
        }
        return deletedCount
    }
}
