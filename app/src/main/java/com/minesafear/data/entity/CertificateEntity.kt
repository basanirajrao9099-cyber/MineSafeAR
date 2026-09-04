package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A certification awarded to a worker for their training as a whole, not for a
 * single module: [score] is the average across [modulesCompleted], and one worker
 * holds one current certificate rather than a stack of per-module ones.
 *
 * ## No foreign keys, deliberately
 *
 * Same reasoning as [ModuleResultEntity]. A worker on an offline device has no
 * synced `workers` row and the `training_modules` table is unseeded, so foreign
 * keys to either would turn "issue a certificate" into an insert that throws —
 * Room enables SQLite foreign key enforcement by default, so this is not
 * theoretical. Add them once sign-in provisions a worker and the module catalogue
 * is seeded.
 *
 * ## No stored QR payload
 *
 * An earlier version of this table kept the encoded QR string in a column so a
 * re-render could not change what a scanner reads. It is gone because every field
 * the payload carries is now a column here, so
 * [com.minesafear.certificate.CertificatePayload] is derived rather than
 * remembered — which removes the possibility of the two drifting apart instead of
 * merely making it unlikely.
 *
 * ## Signature
 *
 * [signatureHash] covers [certId], [userId], [score] and [issuedDate]. Read the
 * production note on [com.minesafear.certificate.CertificateSigner] before
 * treating it as proof of anything.
 */
@Entity(
    tableName = "certificates",
    indices = [Index("user_id")],
)
data class CertificateEntity(
    /** Locally generated UUID; also the QR code's primary field. */
    @PrimaryKey @ColumnInfo(name = "cert_id") val certId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    /** Snapshotted at issue: the printed card must not change if the profile does. */
    @ColumnInfo(name = "user_name") val userName: String,
    /** Average of the worker's best score per module, 0-100. */
    val score: Int,
    /**
     * Module ids counted towards [score]. Stored through
     * [com.minesafear.data.converter.Converters] as a newline-delimited string,
     * which is how the schema's other list column is kept; module ids never
     * contain a newline.
     */
    @ColumnInfo(name = "modules_completed") val modulesCompleted: List<String>,
    /** Epoch millis. */
    @ColumnInfo(name = "issued_date") val issuedDate: Long,
    /**
     * Epoch millis, always [com.minesafear.certificate.CertificatePolicy.expiryFor]
     * of [issuedDate]. Stored rather than computed on read so the row is
     * self-describing to the sync backend and to anyone reading the database.
     */
    @ColumnInfo(name = "expiry_date") val expiryDate: Long,
    /** Lowercase SHA-256 hex. */
    @ColumnInfo(name = "signature_hash") val signatureHash: String,
    /** Sync plumbing, not part of the certificate. */
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)
