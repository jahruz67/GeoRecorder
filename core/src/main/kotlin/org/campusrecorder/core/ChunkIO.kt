package org.campusrecorder.core

import org.campusrecorder.proto.Recording.*
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ChunkIO {
    const val MAX_FRAME = 4 * 1024 * 1024
    data class Scan(val count: Long, val first: Long, val last: Long, val header: ChunkHeader, val recovered: Boolean)
    fun read(input: InputStream): Envelope? {
        var b = input.read()
        if (b < 0) return null
        var size = b and 127
        var shift = 7
        while (b and 128 != 0) {
            if (shift >= 35) throw IntegrityException("Invalid frame length")
            b = input.read(); if (b<0) throw EOFException("Truncated length")
            size = size or ((b and 127) shl shift); shift += 7
        }
        if (size <= 0 || size > MAX_FRAME) throw IntegrityException("Frame length $size outside bounds")
        val bytes = ByteArray(size); var pos=0
        while (pos<size) { val n=input.read(bytes,pos,size-pos); if(n<0) throw EOFException("Truncated frame"); pos+=n }
        return Envelope.parseFrom(bytes)
    }
    fun scan(file: File, consume: (Observation)->Unit = {}): Scan {
        GZIPInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val h = read(input) ?: throw IntegrityException("Empty chunk ${file.name}")
            if (!h.hasHeader() || h.header.schemaVersion != 1) throw IntegrityException("Missing/unsupported header")
            var count=0L; var first=0L; var last=0L
            while (true) {
                val env=read(input) ?: throw IntegrityException("Missing trailer")
                if (env.hasTrailer()) {
                    val t=env.trailer
                    if(t.observationCount!=count || t.firstSequence!=first || t.lastSequence!=last || read(input)!=null)
                        throw IntegrityException("Trailer mismatch")
                    return Scan(count,first,last,h.header,t.recovered)
                }
                if(!env.hasObservation()) throw IntegrityException("Unexpected envelope")
                val o=env.observation
                if(o.sequence<=last) throw IntegrityException("Non-increasing sequence")
                if(count==0L) first=o.sequence
                last=o.sequence; count++; consume(o)
            }
        }
    }
    fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }
    fun sha256(input: InputStream): String {
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(64*1024)
        while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp=File(file.parentFile,file.name+".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        // Same-directory rename is atomic on Android/Linux. Never fall back to copy/delete.
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(file.parentFile)
    }
    fun syncDirectory(directory: File): Boolean = try {
        FileChannel.open(directory.toPath(),StandardOpenOption.READ).use { it.force(true) }; true
    } catch(_: IOException) { false } // Windows does not expose directory fsync through FileChannel.
    fun compressJournal(raw: File, target: File, recovered: Boolean): Scan {
        val tmp=File(target.parentFile,target.name+".tmp")
        var count=0L; var first=0L; var last=0L
        var header: ChunkHeader? = null
        FileOutputStream(tmp).use { disk ->
            val gz=GZIPOutputStream(BufferedOutputStream(disk,64*1024))
            FileInputStream(raw).buffered().use { input ->
                val env=read(input) ?: throw IntegrityException("Empty journal")
                if(!env.hasHeader()) throw IntegrityException("Missing journal header")
                header=env.header; env.writeDelimitedTo(gz)
                while(true) {
                    val next=try { read(input) } catch(e: IOException) { if(recovered) null else throw e } ?: break
                    if(!next.hasObservation() || next.observation.sequence<=last) {
                        if(recovered) break else throw IntegrityException("Invalid journal record")
                    }
                    if(count==0L) first=next.observation.sequence
                    last=next.observation.sequence; count++; next.writeDelimitedTo(gz)
                }
            }
            Envelope.newBuilder().setTrailer(ChunkTrailer.newBuilder().setObservationCount(count)
                .setFirstSequence(first).setLastSequence(last).setRecovered(recovered)).build().writeDelimitedTo(gz)
            gz.finish(); gz.flush(); disk.fd.sync()
        }
        scan(tmp) // CRC and complete framing before publication.
        Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(target.parentFile)
        return Scan(count,first,last,header!!,recovered)
    }
}
