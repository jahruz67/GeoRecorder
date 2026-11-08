package org.campusrecorder.core

import com.google.gson.GsonBuilder
import org.campusrecorder.proto.Recording.*
import java.io.*
import java.util.UUID

/** Single writer owns one session. Published chunks are immutable; metadata is replaced atomically. */
class SessionStore(
    val directory: File,
    val metadata: SessionMetadata,
    private val availableBytes: ()->Long = { directory.usableSpace },
    private val reserveBytes: Long = 32L*1024*1024,
    private val rotateBytes: Long = 1024*1024
) : Closeable {
    private var raw: File? = null
    private var disk: FileOutputStream? = null
    private var output: BufferedOutputStream? = null
    private var rawBytes=0L
    private var nextIndex=metadata.chunkCount
    private var lastSequence=0L
    private var closed=false
    private var committedBytes=directory.walkTopDown().filter { it.isFile && it.name!="metadata.json" && !it.name.endsWith(".tmp") }.sumOf { it.length() }
    private var lastCheckpointNs=System.nanoTime()
    init { directory.mkdirs(); File(directory,"chunks").mkdirs(); saveMetadata() }

    fun append(observation: Observation) {
        check(!closed) { "Session closed" }
        require(observation.sequence>lastSequence) { "Sequence must increase" }
        if(output==null) openJournal()
        val envelope=Envelope.newBuilder().setObservation(observation).build()
        if(envelope.serializedSize>ChunkIO.MAX_FRAME) throw IOException("Observation exceeds frame limit")
        envelope.writeDelimitedTo(output!!)
        rawBytes+=envelope.serializedSize+5
        lastSequence=observation.sequence; metadata.observationCount++
        if(rawBytes>=rotateBytes) checkpoint(true)
    }
    private fun openJournal() {
        if(availableBytes()<reserveBytes) throw InsufficientStorage("Storage below ${reserveBytes/1048576} MiB reserve")
        raw=File(directory,"chunks/%08d.open".format(nextIndex))
        check(!raw!!.exists()) { "Journal collision; recover first" }
        disk=FileOutputStream(raw!!); output=BufferedOutputStream(disk!!,64*1024)
        Envelope.newBuilder().setHeader(ChunkHeader.newBuilder().setSchemaVersion(1)
            .setSessionId(metadata.id).setChunkIndex(nextIndex)
            .setClockDomain("android.elapsedRealtimeNanos; includes suspend; resets on boot")
            .setCoordinateReference("EPSG:4326; GNSS altitude WGS84 ellipsoid unless explicitly MSL"))
            .build().writeDelimitedTo(output!!)
        rawBytes=0
    }
    fun checkpoint(rotate: Boolean = true) {
        output?.flush(); disk?.fd?.sync()
        if(rotate && raw!=null) {
            output!!.close(); output=null; disk=null
            val source=raw!!
            val target=File(directory,"chunks/%08d.pb.gz".format(nextIndex))
            ChunkIO.compressJournal(source,target,false)
            committedBytes+=target.length()
            if(!source.delete()) { metadata.notes.add("Redundant committed journal retained: ${source.name}"); committedBytes+=source.length() }
            raw=null; nextIndex++; metadata.chunkCount=nextIndex
        }
        metadata.bytes=committedBytes+(raw?.length() ?: 0L)+File(directory,"metadata.json").length()
        saveMetadata(); lastCheckpointNs=System.nanoTime()
        if(availableBytes()<reserveBytes) throw InsufficientStorage("Low storage: recording stopped; earlier chunks retained")
    }
    fun saveMetadata() = writeMetadata(directory, metadata)
    fun finish(state: String, wallMs: Long) {
        if(closed) return
        try { checkpoint() } finally {
            closed=true; output?.runCatching { close() }; output=null; disk=null
            metadata.state=state; metadata.endedWallMs=wallMs; saveMetadata()
        }
    }
    override fun close() = finish("stopped",System.currentTimeMillis())
    companion object {
        val gson=GsonBuilder().setPrettyPrinting().create()
        fun create(root: File, wallMs: Long, synthetic: Boolean=false, device: Map<String,Any> = emptyMap()): SessionStore {
            val id=UUID.randomUUID().toString()
            return SessionStore(File(root,id),SessionMetadata(id=id,startedWallMs=wallMs,synthetic=synthetic,device=device))
        }
        fun writeMetadata(dir: File, metadata: SessionMetadata) =
            ChunkIO.atomicWrite(File(dir,"metadata.json"),gson.toJson(metadata).toByteArray(Charsets.UTF_8))
        fun readMetadata(dir: File): SessionMetadata = File(dir,"metadata.json").reader().use { gson.fromJson(it,SessionMetadata::class.java) }
        fun list(root: File): List<Pair<File,SessionMetadata>> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull {
            try { it to readMetadata(it) } catch(e: Exception) {
                it to SessionMetadata(id=it.name,startedWallMs=0,state="metadata_unreadable",
                    notes=mutableListOf("Metadata unreadable: ${e.message}; original recording files retained; original date unavailable"))
            }
        }?.sortedByDescending { it.second.startedWallMs } ?: emptyList()
        fun recover(root: File): List<SessionMetadata> {
            return list(root).mapNotNull { (dir,m) ->
                val unfinished=File(dir,"chunks").listFiles()?.any { it.extension=="open" }==true
                if(m.state !in listOf("recording","paused","initializing","metadata_unreadable") && !unfinished) return@mapNotNull null
                if(m.state=="metadata_unreadable") {
                    val bad=File(dir,"metadata.json")
                    if(bad.exists() && !bad.renameTo(File(dir,"metadata.corrupt-${System.nanoTime()}.json")))
                        throw IOException("Cannot preserve damaged metadata for ${m.id}")
                }
                var damaged=false
                val chunks=File(dir,"chunks")
                chunks.listFiles()?.filter { it.extension=="open" }?.sortedBy { it.name }?.forEach { raw ->
                    val target=File(chunks,raw.nameWithoutExtension+".pb.gz")
                    try {
                        if(target.exists()) ChunkIO.scan(target) else ChunkIO.compressJournal(raw,target,true)
                        // Retain the original uncommitted bytes for forensic inspection.
                        raw.renameTo(File(chunks,raw.name+".recovered-journal"))
                        m.notes.add("Recovered ${raw.name}; partial last frame, if any, excluded; journal retained locally")
                    } catch(e: Exception) { damaged=true; m.notes.add("Recovery failed ${raw.name}: ${e.message}") }
                }
                var count=0L; var last=0L; var n=0
                chunks.listFiles()?.filter { it.name.endsWith(".pb.gz") }?.sortedBy { it.name }?.forEachIndexed { index,chunk ->
                    try {
                        val s=ChunkIO.scan(chunk)
                        if(s.header.sessionId!=m.id || s.header.chunkIndex!=index || chunk.name!="%08d.pb.gz".format(index) || (s.count>0 && s.first<=last)) throw IntegrityException("Session/index/sequence mismatch")
                        count+=s.count; if(s.count>0) last=s.last; n++
                    } catch(e: Exception) { damaged=true; m.notes.add("Corrupt ${chunk.name}: ${e.message}") }
                }
                m.notes.add("Unexpected termination: durable count=$count; uncheckpointed/queued observations may be lost")
                m.observationCount=count; m.chunkCount=n; m.state=if(damaged) "damaged" else if(m.state in listOf("recording","paused","initializing","metadata_unreadable")) "interrupted" else m.state
                m.endedWallMs=System.currentTimeMillis(); m.bytes=dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                writeMetadata(dir,m); m
            }
        }
    }
}
