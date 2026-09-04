package com.minesafear.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

/**
 * Local bookkeeping about sync: when it last succeeded, and who this handset is.
 *
 * ## Why SharedPreferences and not a Room table
 *
 * A `sync_state` table would be tidier, and it would also be a schema bump. The
 * database is at version 3 with `fallbackToDestructiveMigration` still enabled, so
 * bumping to 4 wipes every certificate and drill result on every installed device —
 * an unreasonable price for storing one timestamp. This mirrors
 * [com.minesafear.localization.LanguagePreference], which made the same call for the
 * same reason.
 *
 * When destructive migration is turned off and real migrations begin, moving this
 * into the database is a reasonable cleanup. It is not urgent: nothing here is worth
 * preserving across a reinstall except [deviceId], and a reinstalled app is arguably
 * a new device anyway.
 *
 * ## Threading
 *
 * Every writer is [SyncWorker], on a WorkManager background thread, so all writes
 * use `commit()`. The reader is the Home screen via [observeLastSyncedAt], which is
 * a listener-backed [Flow] rather than a one-shot read so that a sync completing
 * while the user is looking at the screen updates it.
 */
object SyncStatusStore {

    /**
     * A stable, app-private identifier for this handset, created on first use.
     *
     * Deliberately a random [UUID] and **not** `Settings.Secure.ANDROID_ID`: this
     * value exists so a backend can tell a retry from a duplicate upload (see
     * [SyncBatch.deviceId]), which needs stability and nothing else. A real device
     * identifier would additionally let anyone holding the payload correlate a
     * worker's records with any other app's data from the same phone, which is a
     * privacy cost with no matching benefit.
     *
     * Scoped to the app's data directory, so it survives updates and is lost on
     * uninstall or a "clear data" — both of which are indistinguishable from a new
     * handset as far as the sync protocol is concerned.
     */
    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        // Two workers could in principle race here; the lock is cheap and makes the
        // first-write-wins behaviour explicit rather than accidental.
        synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
            return generated
        }
    }

    /** Epoch millis of the last run that reached the server, or null if never. */
    fun lastSyncedAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_SYNCED_AT, NEVER).takeIf { it != NEVER }

    /**
     * Records that data reached the server.
     *
     * Called by [SyncWorker] when at least one record came back acknowledged — not
     * for a run that had nothing to upload, and not for a run that reached the server
     * only to be refused. "Last synced" is read by a worker as *when did my records
     * last get out of this phone*, so a run that moved nothing must not advance it.
     *
     * It is deliberately advanced even when the run's overall verdict was a retry: if
     * the drill results uploaded and the certificates hit a 500, records did leave
     * the phone at that moment.
     */
    fun recordSyncSuccess(context: Context, atMillis: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNCED_AT, atMillis).commit()
    }

    /**
     * Emits the current [lastSyncedAt] and then again whenever it changes.
     *
     * Nothing is emitted for a failed sync: a failure leaves records queued, and the
     * pending count the Home screen already observes is the honest signal for that.
     */
    fun observeLastSyncedAt(context: Context): Flow<Long?> = callbackFlow {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key is nullable on API 30+ when preferences are cleared wholesale.
            if (key == null || key == KEY_LAST_SYNCED_AT) {
                trySend(prefs.getLong(KEY_LAST_SYNCED_AT, NEVER).takeIf { it != NEVER })
            }
        }
        // Registered before the initial read would be racy the other way round: a
        // write landing between read and register would be missed entirely.
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getLong(KEY_LAST_SYNCED_AT, NEVER).takeIf { it != NEVER })
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Sentinel for "no successful sync recorded". 0L is not a plausible epoch. */
    private const val NEVER = 0L

    private const val FILE = "minesafear_sync"
    private const val KEY_LAST_SYNCED_AT = "last_synced_at_millis"
    private const val KEY_DEVICE_ID = "device_id"
}
