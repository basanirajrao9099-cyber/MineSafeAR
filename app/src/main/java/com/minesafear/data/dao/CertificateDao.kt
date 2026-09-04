package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.minesafear.data.entity.CertificateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CertificateDao {

    @Upsert
    suspend fun upsert(certificate: CertificateEntity)

    @Query("SELECT * FROM certificates WHERE user_id = :userId ORDER BY issued_date DESC")
    fun observeForUser(userId: String): Flow<List<CertificateEntity>>

    @Query("SELECT * FROM certificates WHERE cert_id = :certId")
    fun observeById(certId: String): Flow<CertificateEntity?>

    /** One-shot lookup for the verify screen, which resolves a scanned id once. */
    @Query("SELECT * FROM certificates WHERE cert_id = :certId")
    suspend fun getById(certId: String): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE pending_sync = 1")
    suspend fun getPendingSync(): List<CertificateEntity>

    /** Counted, not loaded — see the note on `ModuleResultDao.observePendingSyncCount`. */
    @Query("SELECT COUNT(*) FROM certificates WHERE pending_sync = 1")
    fun observePendingSyncCount(): Flow<Int>

    /** Named ids only, from [com.minesafear.sync.SyncAck.acceptedIds]. */
    @Query("UPDATE certificates SET pending_sync = 0 WHERE cert_id IN (:certIds)")
    suspend fun clearPendingSync(certIds: List<String>)
}
