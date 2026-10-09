package com.aamus.powerpdf.engine

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Temporary and staging files created exclusively under random names (desktop
 * src/worker/staging.ts, decisions §82 there, §52 here). A file is only ever written after this
 * call created it, so a file or link already lying at a predictable name is never written through;
 * a name that exists is skipped for another one (16 tries), and when every name is taken nothing
 * is written at all.
 *
 * HonoKit writes its working copies in its own private storage, where no other app can plant
 * anything, so this is defence in depth; it matters where a staging file sits beside a target the
 * person chose.
 */
object Staging {
    const val TRIES = 16

    /** A new, empty file in [dir] named [prefix] + a random part + [suffix]. [names] supplies the random parts (tests). */
    fun create(dir: File, prefix: String = "", suffix: String = "", names: Iterator<String>? = null): File {
        dir.mkdirs()
        repeat(TRIES) {
            val part = if (names != null) (if (names.hasNext()) names.next() else return@repeat) else UUID.randomUUID().toString()
            val f = File(dir, prefix + part + suffix)
            // createNewFile is atomic: true only when this call made the file (a link there counts as taken).
            if (runCatching { f.createNewFile() }.getOrDefault(false)) return f
        }
        throw IOException("No free name for a temporary file in ${dir.name}.")
    }
}
