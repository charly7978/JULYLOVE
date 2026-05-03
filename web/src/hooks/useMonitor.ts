import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CameraController, type CameraCapabilitiesSnapshot } from '../camera/CameraController'
import { PROCESSING } from '../constants/processing'
import { PpgPipeline } from '../ppg/pipeline'
import { DeviceCalibrationManager, type CalibrationPoint, type CalibrationProfile } from '../ppg/spo2'
import type { BeatEvent, PpgSample, VitalReading } from '../ppg/types'
import { MotionEstimator } from '../sensors/MotionEstimator'

const DEFAULT_READING: VitalReading = {
  bpm: 0,
  bpmConfidence: 0,
  spo2: 0,
  spo2Confidence: 0,
  sqi: 0,
  perfusionIndex: 0,
  motionScore: 0,
  rrMs: 0,
  rrSdnnMs: 0,
  pnn50: 0,
  beatsDetected: 0,
  abnormalBeats: 0,
  state: 'NO_CONTACT',
  validityFlags: 0,
  message: 'Coloque el dedo sobre la cámara trasera',
  hypertensionRisk: 'NO_VALID_PPG',
  bloodPressureSystolic: 0,
  bloodPressureDiastolic: 0,
  glucoseMgDl: 0,
  lipidsMgDl: 0,
  arrhythmiaStatus: 'NO_VALID_PPG',
  reasonCodes: ['NO_VALID_PPG']
}

export interface MonitorApi {
  reading: VitalReading
  /** Ref viva al ring buffer de muestras. No re-renderiza React. */
  samplesRef: React.MutableRefObject<PpgSample[]>
  /** Ref viva al ring buffer de latidos. */
  beatsRef: React.MutableRefObject<BeatEvent[]>
  /** Contador que sube cada vez que los buffers cambian (para invalidar memos si hace falta). */
  tick: number
  fps: number
  running: boolean
  error: string | null
  calibration: CalibrationProfile | null
  caps: CameraCapabilitiesSnapshot | null
  pendingCalibrationPoints: number
  lastRatioOfRatios: number | null
  start(): Promise<void>
  stop(): Promise<void>
  captureCalibrationPoint(reference: number): void
  applyCalibration(): void
  clearCalibration(): void
}

const SAMPLE_BUFFER_LIMIT = PROCESSING.SAMPLE_BUFFER_LIMIT
const BEAT_BUFFER_LIMIT = PROCESSING.BEAT_BUFFER_LIMIT
const PUBLISH_THROTTLE_MS = PROCESSING.PUBLISH_THROTTLE_MS

export function useMonitor(): MonitorApi {
  const cameraRef = useRef<CameraController | null>(null)
  const motionRef = useRef<MotionEstimator | null>(null)
  const pipelineRef = useRef<PpgPipeline | null>(null)
  const samplesRef = useRef<PpgSample[]>([])
  const beatsRef = useRef<BeatEvent[]>([])
  const pendingRef = useRef<CalibrationPoint[]>([])
  const calibrationMgr = useMemo(() => new DeviceCalibrationManager(), [])
  const lastPublishRef = useRef(0)
  const calibrationRef = useRef<CalibrationProfile | null>(null)

  const [reading, setReading] = useState<VitalReading>(DEFAULT_READING)
  const [tick, setTick] = useState(0)
  const [fps, setFps] = useState(0)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [calibration, setCalibration] = useState<CalibrationProfile | null>(null)
  const [caps, setCaps] = useState<CameraCapabilitiesSnapshot | null>(null)
  const [pendingCount, setPendingCount] = useState(0)
  const lastRatioRef = useRef<number | null>(null)
  const [lastRatio, setLastRatio] = useState<number | null>(null)

  useEffect(
    () => () => {
      void cameraRef.current?.stop()
      motionRef.current?.stop()
    },
    []
  )

  const start = useCallback(async () => {
    if (running) return
    setError(null)
    samplesRef.current = []
    beatsRef.current = []
    setReading(DEFAULT_READING)
    try {
      const motion = new MotionEstimator()
      await motion.requestPermissionIfNeeded()
      motion.start()
      motionRef.current = motion

      const camera = new CameraController()
      cameraRef.current = camera
      const targetFps = PROCESSING.TARGET_FPS
      const pipeline = new PpgPipeline(targetFps)
      pipeline.setTargetFps(targetFps)
      pipelineRef.current = pipeline

      const capsSnap = await camera.start({
        targetFps,
        onFrame: (frame) => {
          const motionScore = motionRef.current?.score() ?? 0
          const step = pipeline.process(frame, motionScore, calibrationRef.current)
          if (step.spo2Debug?.ratioOfRatios != null) {
            lastRatioRef.current = step.spo2Debug.ratioOfRatios
          }

          if (step.sample === null && step.reading.state === 'NO_CONTACT') {
            // Sin contacto real: limpiamos onda y latidos (no queremos residuos).
            if (samplesRef.current.length !== 0) samplesRef.current = []
            if (beatsRef.current.length !== 0) beatsRef.current = []
          } else if (step.sample !== null) {
            samplesRef.current.push(step.sample)
            if (samplesRef.current.length > SAMPLE_BUFFER_LIMIT) {
              samplesRef.current.splice(0, samplesRef.current.length - SAMPLE_BUFFER_LIMIT)
            }
            if (step.reading.state === 'VALID_LIVE_PPG' && step.beat) {
              beatsRef.current.push(step.beat)
              if (beatsRef.current.length > BEAT_BUFFER_LIMIT) {
                beatsRef.current.splice(0, beatsRef.current.length - BEAT_BUFFER_LIMIT)
              }
            } else if (beatsRef.current.length !== 0) {
              // Fail-closed: fuera de VALID_LIVE_PPG no hay marcadores de latido.
              beatsRef.current = []
            }
          }

          const now = performance.now()
          if (now - lastPublishRef.current >= PUBLISH_THROTTLE_MS) {
            lastPublishRef.current = now
            // Los números (React state): throttled a 20 Hz. No incluimos
            // los arrays de onda porque el canvas los lee de ref en su
            // propio rAF — evita parpadeo por re-render masivo.
            setReading(step.reading)
            setFps(pipeline.fpsActual())
            setLastRatio(lastRatioRef.current)
            setTick((t) => (t + 1) & 0xffff)
          }
        }
      })
      setCaps(capsSnap)
      const foundCal = calibrationMgr.find(capsSnap.deviceId, navigator.userAgent)
      calibrationRef.current = foundCal
      setCalibration(foundCal)
      setRunning(true)
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setError(message)
      await cameraRef.current?.stop()
      motionRef.current?.stop()
      setRunning(false)
    }
  }, [calibrationMgr, running])

  const stop = useCallback(async () => {
    await cameraRef.current?.stop()
    motionRef.current?.stop()
    pipelineRef.current?.reset()
    samplesRef.current = []
    beatsRef.current = []
    setReading(DEFAULT_READING)
    setFps(0)
    setRunning(false)
    setTick((t) => (t + 1) & 0xffff)
  }, [])

  const captureCalibrationPoint = useCallback(
    (reference: number) => {
      // Sólo permitimos captura con evidencia viva validada.
      const r = lastRatioRef.current
      if (r === null || reading.state !== 'VALID_LIVE_PPG') return
      pendingRef.current.push({
        capturedAtMs: Date.now(),
        referenceSpo2: reference,
        ratioOfRatios: r,
        sqi: reading.sqi,
        perfusionIndex: reading.perfusionIndex,
        motionScore: reading.motionScore
      })
      setPendingCount(pendingRef.current.length)
    },
    [reading]
  )

  const applyCalibration = useCallback(() => {
    if (!caps) return
    const profile = calibrationMgr.fit(
      caps.deviceId,
      navigator.userAgent,
      null,
      null,
      caps.torchOn ? 1 : null,
      pendingRef.current,
      `web-calibration@${new Date().toISOString()}`
    )
    if (profile) {
      calibrationRef.current = profile
      setCalibration(profile)
      pendingRef.current = []
      setPendingCount(0)
    }
  }, [caps, calibrationMgr])

  const clearCalibration = useCallback(() => {
    pendingRef.current = []
    setPendingCount(0)
  }, [])

  return {
    reading,
    samplesRef,
    beatsRef,
    tick,
    fps,
    running,
    error,
    calibration,
    caps,
    pendingCalibrationPoints: pendingCount,
    lastRatioOfRatios: lastRatio,
    start,
    stop,
    captureCalibrationPoint,
    applyCalibration,
    clearCalibration
  }
}
