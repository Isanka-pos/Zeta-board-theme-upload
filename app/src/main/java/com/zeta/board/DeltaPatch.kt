package com.zeta.board

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Builds the NEW apk from the apk that is already installed plus a small patch file (the "delta").
 *
 * The patch is made on GitHub Actions (.github/scripts/make_patches.py). It is the classic bsdiff
 * format (control / diff / extra blocks) repacked with zlib, so no extra library is needed here.
 * Memory use is small: the old apk is read from disk in pieces and the new apk is written as it is built.
 */
object DeltaPatch {

    private const val CHUNK = 64 * 1024

    private fun offtin(b: ByteArray, o: Int): Long {
        var y = (b[o + 7].toLong() and 0x7FL)
        for (i in 6 downTo 0) y = (y shl 8) or (b[o + i].toLong() and 0xFFL)
        return if ((b[o + 7].toLong() and 0x80L) != 0L) -y else y
    }

    private fun readFully(s: InputStream, buf: ByteArray, n: Int) {
        var got = 0
        while (got < n) {
            val r = s.read(buf, got, n - got)
            if (r < 0) throw IllegalStateException("patch is truncated")
            got += r
        }
    }

    /** Applies [patch] on top of [base] and writes the result to [out]. Throws when the patch is broken or [cancelled] turns true. */
    fun apply(base: File, patch: ByteArray, out: File, cancelled: () -> Boolean) {
        val bb = ByteBuffer.wrap(patch)
        val magic = ByteArray(4)
        bb.get(magic)
        if (String(magic, Charsets.US_ASCII) != "ZDP1") throw IllegalStateException("bad patch")
        val newSize = bb.getLong()
        if (newSize <= 0L || newSize > 500L * 1024 * 1024) throw IllegalStateException("bad patch size")

        fun section(): InputStream {
            val len = bb.getLong()
            val pos = bb.position()
            if (len < 0L || pos + len > patch.size) throw IllegalStateException("bad patch block")
            bb.position(pos + len.toInt())
            return BufferedInputStream(InflaterInputStream(ByteArrayInputStream(patch, pos, len.toInt())), CHUNK)
        }

        val ctrl = section()
        val diff = section()
        val extra = section()

        val cb = ByteArray(24)
        val db = ByteArray(CHUNK)
        val ob = ByteArray(CHUNK)

        RandomAccessFile(base, "r").use { old ->
            BufferedOutputStream(FileOutputStream(out), CHUNK).use { os ->
                val oldSize = old.length()
                var oldPos = 0L
                var newPos = 0L
                while (newPos < newSize) {
                    if (cancelled()) throw InterruptedException()
                    readFully(ctrl, cb, 24)
                    val x = offtin(cb, 0) // bytes to copy from diff (+ old)
                    val y = offtin(cb, 8) // bytes to copy from extra
                    val z = offtin(cb, 16) // how far to jump in the old file afterwards
                    if (x < 0L || y < 0L || newPos + x + y > newSize) throw IllegalStateException("corrupt patch")

                    var left = x
                    while (left > 0L) {
                        val n = if (left > CHUNK) CHUNK else left.toInt()
                        readFully(diff, db, n)
                        java.util.Arrays.fill(ob, 0, n, 0.toByte())
                        if (oldPos < oldSize && oldPos + n > 0L) {
                            val from = if (oldPos < 0L) 0L else oldPos
                            val to = if (oldPos + n > oldSize) oldSize else oldPos + n
                            old.seek(from)
                            old.readFully(ob, (from - oldPos).toInt(), (to - from).toInt())
                        }
                        for (i in 0 until n) db[i] = (db[i] + ob[i]).toByte()
                        os.write(db, 0, n)
                        oldPos += n
                        newPos += n
                        left -= n
                    }

                    left = y
                    while (left > 0L) {
                        val n = if (left > CHUNK) CHUNK else left.toInt()
                        readFully(extra, db, n)
                        os.write(db, 0, n)
                        newPos += n
                        left -= n
                    }
                    oldPos += z
                }
            }
        }
        if (out.length() != newSize) throw IllegalStateException("patched file has the wrong size")
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
