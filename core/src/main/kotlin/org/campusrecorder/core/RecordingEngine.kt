package org.campusrecorder.core

import org.campusrecorder.proto.Recording.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Acquisition never waits for storage. A bounded queue makes overload explicit and measurable. */
class RecordingEngine(val store: SessionStore, capacity: Int = 16384,
    private val checkpointMs: Long = 2000) {
    private data class Command(val state: String?, val durationNs: Long, val done: CountDownLatch)
    private val queue=ArrayBlockingQueue<Any>(capacity)
    private val lock=Any()
    private var sequence=0L
    private var accepting=true
    val received=AtomicLong()
    val accepted=AtomicLong()
    val dropped=AtomicLong()
    val written=AtomicLong()
    private val queuedBytes=AtomicLong()
    val sourceDrops=java.util.concurrent.ConcurrentHashMap<String,AtomicLong>()
    @Volatile var failure: String?=null; private set
    @Volatile var paused=false; private set
    @Volatile var activeDurationNs=0L
    val pending get()=queue.size
    private val worker=Thread({ writeLoop() },"campus-disk-writer").apply { start() }
    fun submit(record: Observation): Boolean = synchronized(lock) {
        if(!accepting || failure!=null) return false
        received.incrementAndGet()
        val o=record.toBuilder().setSequence(++sequence).build()
        val size=o.serializedSize.toLong()
        if(size>ChunkIO.MAX_FRAME || queuedBytes.get()+size>16L*1024*1024) {
            dropped.incrementAndGet(); sourceDrops.computeIfAbsent(o.sourceId) { AtomicLong() }.incrementAndGet(); false
        } else {
            queuedBytes.addAndGet(size)
            if(queue.offer(o)) { accepted.incrementAndGet(); true }
            else { queuedBytes.addAndGet(-size); dropped.incrementAndGet(); sourceDrops.computeIfAbsent(o.sourceId) { AtomicLong() }.incrementAndGet(); false }
        }
    }
    fun transition(state: String, durationNs: Long, timeoutSeconds: Long=30) {
        if(failure!=null) return
        val c=Command(state,durationNs,CountDownLatch(1))
        synchronized(lock) { if(state!="paused" && state!="recording") accepting=false }
        if(!queue.offer(c,timeoutSeconds,TimeUnit.SECONDS)) throw IllegalStateException("Writer unavailable")
        if(!c.done.await(timeoutSeconds,TimeUnit.SECONDS)) throw IllegalStateException("Writer checkpoint timeout")
    }
    fun flush(durationNs: Long) {
        if(failure!=null) return
        val c=Command(null,durationNs,CountDownLatch(1))
        check(queue.offer(c,30,TimeUnit.SECONDS) && c.done.await(30,TimeUnit.SECONDS)) { "Writer checkpoint timeout" }
    }
    private fun accounting() {
        store.metadata.droppedObservations=dropped.get()
        store.metadata.receivedObservations=received.get()
        store.metadata.acceptedObservations=accepted.get()
        store.metadata.droppedBySource=sourceDrops.mapValues { it.value.get() }.toMutableMap()
    }
    private fun writeLoop() {
        var checkpointAt=System.nanoTime()
        try {
            while(true) {
                val item=queue.poll(100,TimeUnit.MILLISECONDS)
                when(item) {
                    is Observation -> {
                        queuedBytes.addAndGet(-item.serializedSize.toLong())
                        store.append(item); written.incrementAndGet()
                    }
                    is Command -> {
                        try {
                            activeDurationNs=item.durationNs; store.metadata.activeDurationNs=item.durationNs
                            accounting()
                            if(item.state==null) store.checkpoint()
                            else if(item.state=="paused" || item.state=="recording") {
                                paused=item.state=="paused"; store.metadata.state=item.state!!; store.checkpoint()
                            } else { store.finish(item.state!!,System.currentTimeMillis()); return }
                        } finally { item.done.countDown() }
                    }
                }
                if(System.nanoTime()-checkpointAt>=checkpointMs*1_000_000) {
                    store.metadata.activeDurationNs=activeDurationNs
                    accounting(); store.checkpoint()
                    checkpointAt=System.nanoTime()
                }
            }
        } catch(e: Exception) {
            synchronized(lock) { accepting=false; failure="${e.javaClass.simpleName}: ${e.message}" }
            var abandoned=0L
            queue.forEach { if(it is Observation) { abandoned++; sourceDrops.computeIfAbsent(it.sourceId) { AtomicLong() }.incrementAndGet() } else if(it is Command) it.done.countDown() }
            dropped.addAndGet(abandoned); queue.clear()
            store.metadata.notes.add(failure!!)
            accounting()
            val state=if(e is InsufficientStorage) "storage_full" else "error"
            store.metadata.state=state
            try { store.finish(state,System.currentTimeMillis()) } catch(_: Exception) {}
            runCatching { store.saveMetadata() }
        }
    }
    fun awaitClosed(ms: Long=30000) { worker.join(ms); check(!worker.isAlive) { "Writer did not close" } }
}
