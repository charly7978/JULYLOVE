package com.forensicppg.monitor.forensic

import com.forensicppg.monitor.domain.MeasurementState
import java.util.concurrent.atomic.AtomicReference

/**
 * Pequeño motor de auditoría sincronizado. Centraliza los cambios de estado
 * y eventos para registrarlos en la sesión actual. Es thread-safe porque el
 * pipeline produce desde Dispatchers.Default.
 */
class AuditTrail(private val session: MeasurementSession) {

    private val lastState = AtomicReference<MeasurementState>(MeasurementState.NO_CONTACT)

    fun log(timestampNs: Long, kind: MeasurementEvent.Kind, details: String) {
        synchronized(session.events) {
            session.events += MeasurementEvent(timestampNs, kind, details)
        }
    }

    fun observeState(newState: MeasurementState, timestampNs: Long) {
        val prev = lastState.getAndSet(newState)
        if (prev != newState) {
            log(timestampNs, MeasurementEvent.Kind.STATE_CHANGE, "$prev → $newState")
            if (prev.allowsMetrics && !newState.allowsMetrics) {
                log(timestampNs, MeasurementEvent.Kind.CONTACT_LOST, "medición interrumpida")
            } else if (!prev.allowsMetrics && newState.allowsMetrics) {
                log(timestampNs, MeasurementEvent.Kind.CONTACT_RESTORED, "medición reanudada")
            }
        }
    }
}
