package org.campusrecorder.core

import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArchiveExporter {
    const val FILE_NAME="campus-recordings-v1.zip"
    /** Caller supplies stopped/recovered sessions only; active sessions cannot be snapshotted unsafely. */
    fun export(sessions: List<File>, schema: InputStream, destination: OutputStream) {
        require(sessions.isNotEmpty()) { "No sessions selected" }
        val metadata=sessions.map { SessionStore.readMetadata(it) }
        require(metadata.none { it.state in listOf("recording","paused","initializing") }) { "Stop recording before exporting" }
        // Verify before creating a plausible but broken archive. Scan is bounded to one frame.
        sessions.zip(metadata).forEach { (dir,m) ->
            require(m.state!="damaged") { "Session ${m.id} has damaged chunks; preserve it for recovery" }
            var count=0L; var last=0L
            val files=File(dir,"chunks").listFiles()?.filter { it.name.endsWith(".pb.gz") }?.sortedBy { it.name } ?: emptyList()
            files.forEachIndexed { index,file ->
                val s=ChunkIO.scan(file)
                if(s.header.sessionId!=m.id || s.header.chunkIndex!=index || (s.count>0 && s.first<=last)) throw IntegrityException("Chunk ordering mismatch")
                count+=s.count; if(s.count>0) last=s.last
            }
            if(count!=m.observationCount || files.size!=m.chunkCount) throw IntegrityException("Metadata count mismatch")
        }
        ZipOutputStream(BufferedOutputStream(destination,64*1024)).use { zip ->
            zip.setLevel(0) // Chunks already gzip-compressed; stream without recompressing.
            val sums=StringBuilder()
            fun add(name: String, input: InputStream) {
                zip.putNextEntry(ZipEntry(name)); val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(64*1024)
                input.use { while(true) { val n=it.read(buffer); if(n<0) break; zip.write(buffer,0,n); digest.update(buffer,0,n) } }
                zip.closeEntry(); sums.append(digest.digest().joinToString("") { "%02x".format(it) }).append("  ").append(name).append('\n')
            }
            add("manifest.json",ByteArrayInputStream(SessionStore.gson.toJson(mapOf(
                "format" to "campus-recordings", "schema_version" to 1,"created_wall_ms" to System.currentTimeMillis(),
                "session_ids" to metadata.map { it.id },"session_count" to metadata.size,
                "incomplete_session_ids" to metadata.filter { it.state!="stopped" }.map { it.id },
                "contains_synthetic_data" to metadata.any { it.synthetic }
            )).toByteArray(Charsets.UTF_8)))
            add("schema/recording.proto",schema)
            sessions.zip(metadata).forEach { (dir,m) ->
                add("sessions/${m.id}/metadata.json",FileInputStream(File(dir,"metadata.json")))
                File(dir,"chunks").listFiles()!!.filter { it.name.endsWith(".pb.gz") }.sortedBy { it.name }.forEach {
                    add("sessions/${m.id}/chunks/${it.name}",FileInputStream(it))
                }
            }
            zip.putNextEntry(ZipEntry("checksums.sha256")); zip.write(sums.toString().toByteArray(Charsets.US_ASCII)); zip.closeEntry()
        }
    }
}
