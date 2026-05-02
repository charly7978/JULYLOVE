package com.forensicppg.monitor.forensic

import java.security.MessageDigest

/**
 * Hasher SHA-256 streaming. Se usa para el hash de integridad del archivo
 * exportado: el archivo JSON incluye la firma del resto del contenido para
 * detectar alteraciones posteriores.
 */
class IntegrityHasher {
    private val md = MessageDigest.getInstance("SHA-256")

    fun update(data: ByteArray): IntegrityHasher { md.update(data); return this }
    fun update(s: String): IntegrityHasher { md.update(s.toByteArray(Charsets.UTF_8)); return this }

    fun digestHex(): String {
        val d = md.digest()
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        fun sha256Hex(s: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
