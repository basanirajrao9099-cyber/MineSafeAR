package com.minesafear.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Inspection audit entry created when a safety supervisor scans a worker QR card. */
data class InspectionAuditRecord(
    val id: String,
    val certId: String,
    val verdict: String,
    val timestamp: Long,
)

/** In-memory audit log store for recent supervisor certificate inspections. */
object InspectionAuditLogStore {
    private val _logs = MutableStateFlow<List<InspectionAuditRecord>>(emptyList())
    val logs: StateFlow<List<InspectionAuditRecord>> = _logs.asStateFlow()

    fun logInspection(certId: String, verdict: String, timestamp: Long = System.currentTimeMillis()) {
        val record = InspectionAuditRecord(
            id = java.util.UUID.randomUUID().toString(),
            certId = certId,
            verdict = verdict,
            timestamp = timestamp,
        )
        _logs.value = listOf(record) + _logs.value.take(19)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
