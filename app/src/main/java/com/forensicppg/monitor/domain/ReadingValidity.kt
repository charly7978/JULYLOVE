package com.forensicppg.monitor.domain

/**
 * Motivos por los que una lectura puede ser inválida o estar degradada.
 * Se combinan en un bitmask para que la UI muestre mensajes precisos.
 */
object ReadingValidity {
    const val OK: Int = 0
    const val NO_FINGER: Int = 1 shl 0
    const val PARTIAL_CONTACT: Int = 1 shl 1
    const val CLIPPING_HIGH: Int = 1 shl 2
    const val CLIPPING_LOW: Int = 1 shl 3
    const val MOTION_HIGH: Int = 1 shl 4
    const val LOW_PERFUSION: Int = 1 shl 5
    const val LOW_FPS: Int = 1 shl 6
    const val UNSTABLE_RR: Int = 1 shl 7
    const val NOT_ENOUGH_BEATS: Int = 1 shl 8
    const val CALIBRATION_MISSING: Int = 1 shl 9
    const val SIGNAL_INCOHERENT: Int = 1 shl 10

    fun describe(flags: Int): List<String> {
        if (flags == 0) return emptyList()
        val out = mutableListOf<String>()
        if (flags and NO_FINGER != 0) out += "Dedo ausente"
        if (flags and PARTIAL_CONTACT != 0) out += "Contacto parcial"
        if (flags and CLIPPING_HIGH != 0) out += "Saturación alta"
        if (flags and CLIPPING_LOW != 0) out += "Oscuridad excesiva"
        if (flags and MOTION_HIGH != 0) out += "Movimiento alto"
        if (flags and LOW_PERFUSION != 0) out += "Baja perfusión"
        if (flags and LOW_FPS != 0) out += "FPS inestable"
        if (flags and UNSTABLE_RR != 0) out += "RR inestable"
        if (flags and NOT_ENOUGH_BEATS != 0) out += "Latidos insuficientes"
        if (flags and CALIBRATION_MISSING != 0) out += "Calibración faltante"
        if (flags and SIGNAL_INCOHERENT != 0) out += "Señal incoherente"
        return out
    }
}
