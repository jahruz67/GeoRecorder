package org.campusrecorder.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit forensic export: never presented as a validated v1 recording archive. */
object RecoveryExporter {
    fun export(directory: File, schema: InputStream, output: OutputStream) {
        val m=SessionStore.readMetadata(directory)
        require(m.state !in listOf("recording","paused","initializing")) { "Stop before exporting recovery files" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(0)
            val sums=StringBuilder()
            fun add(path: String,input: InputStream) {
                zip.putNextEntry(ZipEntry(path)); val hash=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
                input.use { while(true) { val n=it.read(buffer); if(n<0) break; hash.update(buffer,0,n); zip.write(buffer,0,n) } }
                zip.closeEntry(); sums.append(hash.digest().joinToString("") { "%02x".format(it) }).append("  ").append(path).append('\n')
            }
            add("RECOVERY-NOTICE.txt","Forensic recovery bundle. May contain incomplete/corrupt frames and redundant journals. Preserve originals. This is not a validated campus-recordings-v1 archive.\n".byteInputStream())
            add("schema/recording.proto",schema)
            directory.walkTopDown().filter { it.isFile }.forEach {
                add("sessions/${m.id}/"+it.relativeTo(directory).invariantSeparatorsPath,it.inputStream())
            }
            zip.putNextEntry(ZipEntry("checksums.sha256")); zip.write(sums.toString().toByteArray(Charsets.US_ASCII)); zip.closeEntry()
        }
    }
}
