# Architecture and reliability

## Modules and ownership

`:app` contains the native Android UI, foreground service, permissions, and hardware collectors. It uses Android framework services directly, with no Play Services, cloud SDK, telemetry, database, or map dependency. `:core` is a platform-independent Kotlin/JVM library containing protobuf records, delivery metrics, bounded acquisition queue, append-oriented journal, chunk verification, recovery, and streaming exports. Its synthetic generator is a desktop testing entry point; the production service never calls it.

The service starts only after an explicit action from a visible activity and location permission. It immediately enters the `location` foreground service state, then starts an acquisition HandlerThread. Sensor callbacks and framework radio/location callbacks are serialized onto this handler. Callback data is copied into immutable protobuf messages before returning to the platform. A separate disk writer owns the session files.

The queue accepts at most 16,384 messages and 16 MiB of serialized payload, whichever limit is reached first. Individual frames cannot exceed 4 MiB. A full queue never blocks a sensor callback: it records a drop with its source ID. Sequence numbers advance for rejected queue submissions, exposing gaps. The bound excludes ordinary Java object overhead and protobuf construction in progress. Metadata and health events report received, accepted, written, dropped, and pending counts; normal orderly completion satisfies `received = accepted + dropped` and `accepted = durable observation count`. Drops include queued messages abandoned after a writer failure; an interrupted write can require recovery to determine its final durable count. Unobservable hardware/OS losses are never represented as exact drop counts.

## Storage transaction

1. Each session gets a UUID and atomically replaced `metadata.json` in internal app storage.
2. The writer creates a numbered `.open` journal containing a delimited chunk header, then appends delimited observations. Recording does not modify previously committed chunks.
3. Every two seconds, or at approximately 1 MiB of journal payload, it flushes and fsyncs the journal. The deadline is best effort under slow disks and CPU pressure.
4. It streams the journal into a temporary gzip file, adds a count/sequence trailer, finishes the gzip stream, flushes and fsyncs the file, and verifies the completed chunk. It atomically renames the file to `NNNNNNNN.pb.gz` before unlinking the redundant journal.
5. Metadata is written to a same-directory temporary file, fsynced, and replaced with an atomic move. Directory fsync is attempted after publication; support is recorded in the session lifecycle. Some file systems/platforms do not expose it. No implementation can guarantee survival of arbitrary hardware or storage-controller failure.

The metadata size estimate is refreshed at checkpoints and can differ slightly from final metadata length. Checkpoint size calculation uses cached committed file lengths, avoiding a scan of every historical chunk on each checkpoint. Exporting verifies the immutable files again and computes their SHA-256 digests while streaming them to the document provider.

A 32 MiB physical free-space reserve is deliberately conservative. Crossing it or encountering a write failure terminates recording and exposes an error. Earlier sessions are never deleted automatically. Metadata updates can themselves fail when a volume is completely full; recovery then uses the last available metadata and journal. No automatic storage reclamation is performed.

## Pause, stop, and interruptions

Pause unregisters collectors, persists a lifecycle record and delivery statistics, drains the writer queue, commits the session, and releases the partial wake lock. Health/clock records can still be added while paused; no new measurement samples are accepted. Resume from the visible app establishes a new clock anchor, restarts collectors, and excludes the intentional pause gap from source-rate estimates. Active duration excludes pauses.

Stop unregisters collectors and drains acquisition before final lifecycle, clock, and statistics records, then commits the file and removes the foreground notification. A renewable, bounded partial wake lock supports continuous acquisition with non-wakeup sensors while recording. It is released on pause/stop/error. This does not bypass Doze, permission revocation, force-stop, thermal restrictions, or manufacturer process policies.

The service uses `START_NOT_STICKY` and has no boot receiver. It cannot promise continuous sampling after a process kill. On next launch, recovery salvages complete delimited frames from an unfinished journal and adds a `recovered=true` trailer. Any partial/unparseable suffix is kept in the original journal, renamed `.recovered-journal`. A crash between chunk publication and journal deletion does not duplicate observations. Recovery rescans complete chunks and recomputes durable counts. It marks interruptions and corrupted chunks explicitly. Lost queued or not-yet-flushed data after a sudden kill cannot be exactly reconstructed.

If metadata is unreadable, its original bytes are retained and metadata is reconstructed from validated chunks. Its original date is marked unknown. A damaged immutable chunk remains intact, and regular validated export is blocked. **Export recovery files** makes a separately named forensic ZIP containing the original files, temporary/recovery journals, notice, schema, and checksums. It is intentionally distinguishable from a validated recording archive.

Continue on an interrupted session starts a new session linked by `previous_session_id`. It does not append across an unknown gap or a reboot, where elapsed-realtime timestamps may have reset.

## Privacy

There is no INTERNET permission. Data remains in internal private storage; automatic backup and device transfer are excluded. No unique phone serial, IMEI, account identity, camera, microphone, video, photo, or audio data is requested. Device model/build/sensor characteristics support processing and reproducibility. Radio identifiers and advertisement bytes are retained because they are positioning observations; exported data is sensitive.

Export uses the Storage Access Framework, with a destination explicitly chosen by the user. A chosen document provider can itself be a cloud provider. No background upload occurs in this app. Regular export requires a stopped/recovered session, preventing a race with live chunk publication. A failed export attempts to remove its incomplete destination; a provider can refuse deletion, so consumers must always validate archives.
