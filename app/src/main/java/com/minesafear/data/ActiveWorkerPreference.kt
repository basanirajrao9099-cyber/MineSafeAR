package com.minesafear.data

import android.content.Context
import com.minesafear.data.repository.TrainingRepository

/** Stores the active worker profile ID selected on this device. */
object ActiveWorkerPreference {
    private const val FILE = "minesafear_active_worker"
    private const val KEY_WORKER_ID = "active_worker_id"

    fun getActiveWorkerId(context: Context): String {
        return prefs(context).getString(KEY_WORKER_ID, TrainingRepository.UNPROVISIONED_USER_ID)
            ?: TrainingRepository.UNPROVISIONED_USER_ID
    }

    fun setActiveWorkerId(context: Context, workerId: String) {
        prefs(context).edit().putString(KEY_WORKER_ID, workerId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
