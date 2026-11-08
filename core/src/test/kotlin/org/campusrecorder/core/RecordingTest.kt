package org.campusrecorder.core

import org.campusrecorder.proto.Recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.zip.ZipFile
import java.util.zip.GZIPOutputStream

class RecordingTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun root()=temporary.newFolder()
    private fun observation(sequence: Long,time: Long=sequence*1000)=syntheticRecord(sequence).toBuilder().setSequence(sequence).setMonotonicNs(time).build()
    @Test fun timestampsAndIntegerPrecisionArePreserved() {
        val store=SessionStore.create(root(),123456)
        store.append(observation(1,9999)); store.append(observation(2,8888))
        val clock=Observation.newBuilder().setSequence(3).setSourceId("clock").setKind(Kind.CLOCK_ANCHOR)
            .addAllFields(fields("hardware_ns" to Long.MAX_VALUE-100,"wall_utc_ms" to 1800000000000L,"elapsed_before_ns" to 100,"elapsed_after_ns" to 110)).build()
        store.append(clock); store.close()
        val records=mutableListOf<Observation>()
        ChunkIO.scan(File(store.directory,"chunks/00000000.pb.gz")) { records.add(it) }
        assertEquals(listOf(9999L,8888L,0L),records.map { it.monotonicNs })
        assertEquals(Long.MAX_VALUE-100,records[2].fieldsList[0].value.integer)
    }
    @Test fun sourceRatesDetectRegressionWithoutInventingDrops() {
        val meter=SourceMeter(); listOf(100L,200,150,300).forEach { meter.add(it) }
        assertEquals(4,meter.count); assertEquals(1,meter.regressions); assertEquals(150,meter.maxGapNs)
        assertEquals(8_000_000.0,meter.hz()!!,.01)
    }
    @Test fun intentionalPauseGapsDoNotDistortDeliveredRate() {
        val meter=SourceMeter(); meter.add(0); meter.add(1_000_000_000)
        meter.beginSegment(); meter.add(100_000_000_000); meter.add(101_000_000_000)
        assertEquals(1.0,meter.hz()!!,0.0); assertEquals(1_000_000_000,meter.maxGapNs)
    }
    @Test fun nonIncreasingSequenceRejected() {
        val store=SessionStore.create(root(),0); store.append(observation(1))
        assertThrows(IllegalArgumentException::class.java) { store.append(observation(1)) }; store.close()
    }
    @Test fun pauseResumePersistsAllAcceptedEvents() {
        val store=SessionStore.create(root(),0); val engine=RecordingEngine(store)
        repeat(1000) { assertTrue(engine.submit(syntheticRecord(it.toLong()))) }
        engine.transition("paused",1_000_000)
        assertEquals("paused",SessionStore.readMetadata(store.directory).state)
        engine.transition("recording",1_000_000)
        repeat(1000) { assertTrue(engine.submit(syntheticRecord((it+1000).toLong()))) }
        engine.transition("stopped",2_000_000); engine.awaitClosed()
        assertEquals(2000,engine.received.get()); assertEquals(2000,engine.written.get()); assertEquals(0,engine.dropped.get())
        assertEquals(2000,SessionStore.readMetadata(store.directory).observationCount)
    }
    @Test fun queueOverloadIsExactlyAccounted() {
        val store=SessionStore.create(root(),0); val engine=RecordingEngine(store,capacity=1)
        repeat(30000) { engine.submit(syntheticRecord(it.toLong())) }
        engine.transition("stopped",0); engine.awaitClosed()
        assertEquals(engine.received.get(),engine.accepted.get()+engine.dropped.get())
        assertEquals(engine.accepted.get(),engine.written.get())
        assertTrue(engine.dropped.get()>0)
        assertEquals(engine.dropped.get(),engine.sourceDrops.values.sumOf { it.get() })
    }
    @Test fun storageExhaustionPreservesCommittedHistory() {
        var free=100_000_000L
        val dir=File(root(),"session")
        val store=SessionStore(dir,SessionMetadata(id="session",startedWallMs=0),availableBytes={free},reserveBytes=100)
        store.append(observation(1)); store.checkpoint()
        val original=File(dir,"chunks/00000000.pb.gz").readBytes()
        free=0
        assertThrows(InsufficientStorage::class.java) { store.append(observation(2)) }
        assertArrayEquals(original,File(dir,"chunks/00000000.pb.gz").readBytes())
        assertEquals(1,ChunkIO.scan(File(dir,"chunks/00000000.pb.gz")).count)
    }
    @Test fun crashWithPartialJournalSalvagesCompleteFrames() {
        val r=root(); val dir=File(r,"crashed").apply { mkdirs() }; File(dir,"chunks").mkdirs()
        SessionStore.writeMetadata(dir,SessionMetadata(id="crashed",startedWallMs=0))
        val raw=File(dir,"chunks/00000000.open")
        FileOutputStream(raw).use { out ->
            Envelope.newBuilder().setHeader(ChunkHeader.newBuilder().setSchemaVersion(1).setSessionId("crashed")).build().writeDelimitedTo(out)
            repeat(10) { Envelope.newBuilder().setObservation(observation(it+1L)).build().writeDelimitedTo(out) }
            out.write(byteArrayOf(127,1,2,3)); out.fd.sync()
        }
        val recovered=SessionStore.recover(r).single()
        assertEquals("interrupted",recovered.state); assertEquals(10,recovered.observationCount)
        assertTrue(ChunkIO.scan(File(dir,"chunks/00000000.pb.gz")).recovered)
        assertTrue(File(dir,"chunks/00000000.open.recovered-journal").exists())
        assertTrue(SessionStore.recover(r).isEmpty())
    }
    @Test fun committedJournalCrashDoesNotDuplicate() {
        val r=root(); val store=SessionStore.create(r,0)
        store.append(observation(1)); store.checkpoint()
        // A crash after gzip rename but before journal unlink leaves both names.
        java.util.zip.GZIPInputStream(FileInputStream(File(store.directory,"chunks/00000000.pb.gz"))).use { input ->
            FileOutputStream(File(store.directory,"chunks/00000000.open")).use { out ->
                while(true) { val e=ChunkIO.read(input) ?: break; if(e.hasTrailer()) break; e.writeDelimitedTo(out) }
            }
        }
        assertEquals(1,SessionStore.recover(r).single().observationCount)
    }
    @Test fun exportRoundTripAndChecksums() {
        val store=SessionStore.create(root(),0)
        repeat(1234) { store.append(observation(it+1L)) }; store.close()
        val archive=temporary.newFile("export.zip")
        FileOutputStream(archive).use { ArchiveExporter.export(listOf(store.directory),ByteArrayInputStream("syntax = \"proto3\";".toByteArray()),it) }
        ZipFile(archive).use { zip ->
            val lines=zip.getInputStream(zip.getEntry("checksums.sha256")).bufferedReader().readLines()
            lines.forEach { line -> val (hash,path)=line.split("  ",limit=2); assertEquals(hash,zip.getInputStream(zip.getEntry(path)).use { ChunkIO.sha256(it) }) }
            assertEquals(5,zip.size())
        }
    }
    @Test fun corruptedGzipAndMissingTrailerAreDetected() {
        val store=SessionStore.create(root(),0); store.append(observation(1)); store.close()
        val chunk=File(store.directory,"chunks/00000000.pb.gz")
        val b=chunk.readBytes(); b[b.size/2]=(b[b.size/2].toInt() xor 64).toByte(); chunk.writeBytes(b)
        assertThrows(IOException::class.java) { ChunkIO.scan(chunk) }
        GZIPOutputStream(FileOutputStream(chunk)).use { out -> Envelope.newBuilder().setHeader(ChunkHeader.newBuilder().setSchemaVersion(1)).build().writeDelimitedTo(out) }
        assertThrows(IntegrityException::class.java) { ChunkIO.scan(chunk) }
    }
    @Test fun activeAndDamagedSessionsCannotBeExported() {
        val store=SessionStore.create(root(),0)
        assertThrows(IllegalArgumentException::class.java) { ArchiveExporter.export(listOf(store.directory),ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream()) }
        store.metadata.state="damaged"; store.saveMetadata()
        assertThrows(IllegalArgumentException::class.java) { ArchiveExporter.export(listOf(store.directory),ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream()) }
    }
    @Test fun corruptMetadataIsReconstructedAndOriginalBytesRetained() {
        val r=root(); val store=SessionStore.create(r,1000)
        store.append(observation(1)); store.close()
        File(store.directory,"metadata.json").writeText("{broken metadata")
        val m=SessionStore.recover(r).single()
        assertEquals("interrupted",m.state); assertEquals(1,m.observationCount); assertEquals(0,m.startedWallMs)
        assertTrue(store.directory.listFiles()!!.any { it.name.startsWith("metadata.corrupt-") && it.readText()=="{broken metadata" })
    }
    @Test fun corruptCommittedDataRemainsAvailableInForensicExport() {
        val r=root(); val store=SessionStore.create(r,1000)
        store.append(observation(1)); store.checkpoint()
        val chunk=File(store.directory,"chunks/00000000.pb.gz")
        val broken=chunk.readBytes(); broken[broken.size/2]=(broken[broken.size/2].toInt() xor 127).toByte(); chunk.writeBytes(broken)
        assertEquals("damaged",SessionStore.recover(r).single().state)
        val archive=temporary.newFile("recovery.zip")
        archive.outputStream().use { RecoveryExporter.export(store.directory,"syntax=\"proto3\";".byteInputStream(),it) }
        ZipFile(archive).use { zip ->
            val entry=zip.getEntry("sessions/${store.metadata.id}/chunks/00000000.pb.gz")
            assertArrayEquals(broken,zip.getInputStream(entry).readBytes())
            assertNotNull(zip.getEntry("RECOVERY-NOTICE.txt"))
        }
    }
    @Test fun sustainedLogicalHourRemainsCompleteAcrossChunks() {
        val store=SessionStore(File(root(),"long"),SessionMetadata(id="long",startedWallMs=0),reserveBytes=0,rotateBytes=64000)
        val n=360000L
        for(i in 1..n) store.append(observation(i,i*10_000_000))
        store.close()
        var total=0L; var last=0L
        File(store.directory,"chunks").listFiles()!!.sortedBy { it.name }.forEach { f ->
            val s=ChunkIO.scan(f) { o -> assertTrue(o.sequence>last); last=o.sequence }
            total+=s.count
        }
        assertEquals(n,total); assertTrue(store.metadata.chunkCount>100)
    }
    @Test fun unavailableSourceStatusIsAnOrdinaryPreservedObservation() {
        val store=SessionStore.create(root(),0)
        store.append(Observation.newBuilder().setSequence(1).setKind(Kind.CAPABILITY).setSourceId("sensor:6:-1")
            .addAllFields(fields("available" to false,"message" to "Pressure hardware unavailable")).build())
        store.close()
        ChunkIO.scan(File(store.directory,"chunks/00000000.pb.gz")) { o -> assertFalse(o.fieldsList.first().value.flag); assertEquals(Kind.CAPABILITY,o.kind) }
    }
}
