package com.forensicppg.monitor.pipeline

import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.forensic.AuditTrail
import com.forensicppg.monitor.forensic.RoiAuditEvent
import kotlin.math.abs

/**
 * Detecta transiciones TRIGGER/CLEAR sobre métricas reales del frame (ROI, contacto,
 * clipping, movimiento fusionado, FPS). Sin datos sintéticos: sólo compara umbrales
 * con valores entrantes del fotograma.
 */
class RoiAuditDetector(
    private val auditTrail: AuditTrail?
) {
    private var armedContact = false
    private var armedMask = false
    private var armedClipH = false
    private var armedClipL = false
    private var armedMotion = false
    private var armedFps = false
    private var armedLowLight = false

    private var lastRoiL = -1
    private var lastRoiT = -1
    private var lastRoiW = -1
    private var lastRoiH = -1
    private var roiStableFrames = 0
    private var roiGeometryTriggerPending = false

    fun reset() {
        armedContact = false
        armedMask = false
        armedClipH = false
        armedClipL = false
        armedMotion = false
        armedFps = false
        armedLowLight = false
        lastRoiL = -1
        lastRoiT = -1
        lastRoiW = -1
        lastRoiH = -1
        roiStableFrames = 0
        roiGeometryTriggerPending = false
    }

    fun onFrame(
        ppg: PpgSample,
        fusedMotion01: Double,
        validity: PpgValidityState,
        measuredFpsHz: Double,
        targetFps: Int
    ) {
        val trail = auditTrail ?: return
        val ts = ppg.monotonicRealtimeNs

        val c = ppg.contactScore
        if (!armedContact && c < CONTACT_TRIG) {
            armedContact = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.CONTACT_SCORE,
                    "contacto_bajo",
                    "score=%.3f mask=%.3f val=%s".format(c, ppg.maskCoverage, validity.name)
                )
            )
        } else if (armedContact && c > CONTACT_CLEAR) {
            armedContact = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.CONTACT_SCORE,
                    "contacto_recuperado",
                    "score=%.3f mask=%.3f".format(c, ppg.maskCoverage)
                )
            )
        }

        val m = ppg.maskCoverage
        if (!armedMask && m < MASK_TRIG) {
            armedMask = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.FINGER_MASK,
                    "mascara_dedo_baja",
                    "coverage=%.3f contact=%.3f".format(m, c)
                )
            )
        } else if (armedMask && m > MASK_CLEAR) {
            armedMask = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.FINGER_MASK,
                    "mascara_dedo_ok",
                    "coverage=%.3f".format(m)
                )
            )
        }

        val ch = ppg.clippingHighRatio
        if (!armedClipH && ch >= CLIP_H_TRIG) {
            armedClipH = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.CLIPPING_HIGH,
                    "clipping_alto",
                    "ratio=%.4f".format(ch)
                )
            )
        } else if (armedClipH && ch < CLIP_H_CLEAR) {
            armedClipH = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.CLIPPING_HIGH,
                    "clipping_alto_resuelto",
                    "ratio=%.4f".format(ch)
                )
            )
        }

        val cl = ppg.clippingLowRatio
        if (!armedClipL && cl >= CLIP_L_TRIG) {
            armedClipL = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.CLIPPING_LOW,
                    "clipping_bajo",
                    "ratio=%.4f".format(cl)
                )
            )
        } else if (armedClipL && cl < CLIP_L_CLEAR) {
            armedClipL = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.CLIPPING_LOW,
                    "clipping_bajo_resuelto",
                    "ratio=%.4f".format(cl)
                )
            )
        }

        val mo = fusedMotion01.coerceIn(0.0, 1.0)
        if (!armedMotion && mo >= MOTION_TRIG) {
            armedMotion = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.MOTION_FUSED,
                    "movimiento_alto",
                    "motion=%.3f opt=%.3f".format(mo, ppg.motionScoreOptical)
                )
            )
        } else if (armedMotion && mo < MOTION_CLEAR) {
            armedMotion = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.MOTION_FUSED,
                    "movimiento_bajo",
                    "motion=%.3f".format(mo)
                )
            )
        }

        val tf = targetFps.coerceAtLeast(1)
        val fpsOk = measuredFpsHz >= tf * FPS_OK_FRAC
        val fpsBad = measuredFpsHz > 1.0 && measuredFpsHz < tf * FPS_BAD_FRAC
        if (!armedFps && fpsBad) {
            armedFps = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.FPS_STABILITY,
                    "fps_degradado",
                    "fps=%.2f target=%d jitter_coherente_con_gap".format(measuredFpsHz, tf)
                )
            )
        } else if (armedFps && fpsOk) {
            armedFps = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.FPS_STABILITY,
                    "fps_recuperado",
                    "fps=%.2f target=%d".format(measuredFpsHz, tf)
                )
            )
        }

        if (!armedLowLight && ppg.lowLightSuspected) {
            armedLowLight = true
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.LOW_LIGHT,
                    "luz_baja_sospechada",
                    "G_mean=%.1f clipL=%.3f".format(ppg.roiStats.greenMean, cl)
                )
            )
        } else if (armedLowLight && !ppg.lowLightSuspected) {
            armedLowLight = false
            trail.logRoi(
                RoiAuditEvent(
                    ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.LOW_LIGHT,
                    "luz_ok",
                    "G_mean=%.1f".format(ppg.roiStats.greenMean)
                )
            )
        }

        val l = ppg.roiBoundingLeft
        val t = ppg.roiBoundingTop
        val w = ppg.roiBoundingWidth
        val h = ppg.roiBoundingHeight
        if (lastRoiL >= 0 && w > 2 && h > 2) {
            val moved =
                abs(l - lastRoiL) > ROI_MOVE_PX ||
                    abs(t - lastRoiT) > ROI_MOVE_PX ||
                    abs(w - lastRoiW) > ROI_MOVE_PX.coerceAtMost((lastRoiW * 0.08).toInt().coerceAtLeast(3)) ||
                    abs(h - lastRoiH) > ROI_MOVE_PX.coerceAtMost((lastRoiH * 0.08).toInt().coerceAtLeast(3))
            if (moved) {
                trail.logRoi(
                    RoiAuditEvent(
                        ts, RoiAuditEvent.Edge.TRIGGER, RoiAuditEvent.Channel.ROI_GEOMETRY,
                        "roi_geometria_cambio",
                        "L,T,W,H=%d,%d,%d,%d -> %d,%d,%d,%d".format(
                            lastRoiL, lastRoiT, lastRoiW, lastRoiH, l, t, w, h
                        )
                    )
                )
                roiStableFrames = 0
                roiGeometryTriggerPending = true
            } else {
                roiStableFrames++
                if (roiGeometryTriggerPending && roiStableFrames == ROI_STABLE_NEED) {
                    roiGeometryTriggerPending = false
                    trail.logRoi(
                        RoiAuditEvent(
                            ts, RoiAuditEvent.Edge.CLEAR, RoiAuditEvent.Channel.ROI_GEOMETRY,
                            "roi_estable",
                            "L,T,W,H=%d,%d,%d,%d n=%d".format(l, t, w, h, ROI_STABLE_NEED)
                        )
                    )
                }
            }
        }
        lastRoiL = l
        lastRoiT = t
        lastRoiW = w
        lastRoiH = h
    }

    companion object {
        private const val CONTACT_TRIG = 0.26
        private const val CONTACT_CLEAR = 0.44
        private const val MASK_TRIG = 0.56
        private const val MASK_CLEAR = 0.69
        private const val CLIP_H_TRIG = 0.079
        private const val CLIP_H_CLEAR = 0.048
        private const val CLIP_L_TRIG = 0.048
        private const val CLIP_L_CLEAR = 0.028
        private const val MOTION_TRIG = 0.19
        private const val MOTION_CLEAR = 0.13
        private const val FPS_BAD_FRAC = 0.74
        private const val FPS_OK_FRAC = 0.87
        private const val ROI_MOVE_PX = 5
        private const val ROI_STABLE_NEED = 14
    }
}
