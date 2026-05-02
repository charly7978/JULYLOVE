package com.forensicppg.monitor.forensic

/**
 * Pequeño motor de auditoría sincronizado. Centraliza los eventos para registrar
 * en la sesión actual. Es thread-safe porque el pipeline produce desde
 * Dispatchers.Default.
 */
class AuditTrail(private val session: MeasurementSession) {

    fun log(timestampNs: Long, kind: MeasurementEvent.Kind, details: String) {
        synchronized(session.events) {
            session.events += MeasurementEvent(timestampNs, kind, details)
        }
    }
}
