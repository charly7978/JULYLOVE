# -*- coding: utf-8 -*-
p = r"app/src/main/java/com/forensicppg/monitor/ppg/PpgFrameAnalyzer.kt"
with open(p, encoding="utf-8") as f:
    c = f.read()
old = """        val (zr, zg, zb, zBrief) = synchronized(zloLock) {
            Quadruple(zloR, zloG, zloB, zloDesc)
        }

        updateAdaptiveRoiFraction()"""
new = """        val zr: Double
        val zg: Double
        val zb: Double
        val zBrief: String
        synchronized(zloLock) {
            zr = zloR
            zg = zloG
            zb = zloB
            zBrief = zloDesc
        }

        updateAdaptiveRoiFraction()"""
if old not in c:
    raise SystemExit("patch block not found")
c = c.replace(old, new)
c = c.replace(
    "    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)\n\n",
    "",
)
with open(p, "w", encoding="utf-8") as f:
    f.write(c)
print("patched")
