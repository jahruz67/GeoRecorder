# Campus recordings, version 1

## Validated archive

The standard export filename is `campus-recordings-v1.zip` (a document provider can add a suffix if that filename already exists).

```text
manifest.json
schema/recording.proto
sessions/{uuid}/metadata.json
sessions/{uuid}/chunks/00000000.pb.gz
sessions/{uuid}/chunks/00000001.pb.gz
...
checksums.sha256
```

All JSON is UTF-8. The manifest identifies `format=campus-recordings`, `schema_version=1`, creation wall time, ordered session IDs, session count, incomplete session IDs, and whether any session is synthetic. Session metadata uses Kotlin/Gson camelCase keys: `schemaVersion`, `id`, `startedWallMs`, optional `endedWallMs`, `state`, `activeDurationNs`, `observationCount`, `receivedObservations`, `acceptedObservations`, `droppedObservations`, `droppedBySource`, `chunkCount`, `bytes`, `synthetic`, `device`, and `notes`. Older metadata may lack newer accounting fields. Unknown metadata fields should be ignored. A zero `startedWallMs` with a recovery note means the date is unknown.

`observationCount` counts observation envelopes, including clock/health/lifecycle/capability records. A GNSS epoch or Wi-Fi scan is one envelope containing multiple measurement groups. The arrays/groups retain every measurement returned in that callback. Sensor sample counts are per callback, not per axis. `SOURCE_STATS` contains per-source sample/envelope counts; its explicit scope excludes deliberate pause gaps from the rate calculation. Negative timestamp intervals are reported separately and excluded from the rate denominator. On unexpected termination, received/accepted/drop counts are only those last durably recorded and may not describe the lost tail.

The checksum index has one line for every other ZIP member: lowercase SHA-256, two spaces, relative path, LF. It covers manifest, schema, metadata, and compressed chunk bytes. It excludes itself and provides integrity detection, not authenticity or encryption. ZIP entry order is not semantic. Duplicate entries, unsafe paths, unlisted members, missing files, or mismatched checksums are errors in the reference validator.

## Protobuf framing and chunk boundaries

Each `.pb.gz` is an independent gzip stream. Decompression yields this sequence:

1. Unsigned protobuf base-128 varint length, followed by one serialized `Envelope` containing a `ChunkHeader`.
2. Zero or more length-delimited `Envelope` messages, each containing one `Observation`.
3. One length-delimited `Envelope` containing a `ChunkTrailer`. No trailing bytes are allowed.

This is the framing produced by Java protobuf `writeDelimitedTo`, not a single top-level repeated-message container. A frame is limited to 4 MiB. Chunk indices are zero-based and contiguous. Their boundaries reflect writer checkpoints or roughly 1 MiB of uncompressed journal payload; they have no geographic or path meaning. Every chunk contains its session ID, schema version, coordinate reference, and clock domain, so it can be parsed independently with the schema. Device/sensor descriptions reside in session metadata and capability observations; interpreting physical units still requires these and Android's sensor definitions.

The trailer contains observation count, first/last sequence, and whether this chunk was recovered from an unfinished journal. Empty chunks use count/first/last zero. Gzip CRC and trailer are verified before publication and export. The archive decoder also validates the cross-chunk sequence and session counts. Truncated gzip, a missing trailer, truncated protobuf length/payload, unexpected envelopes, or incorrect counts are errors.

## Ordering and clocks

`sequence` increases in callback arrival order within one session. It is assigned before bounded-queue submission; missing sequence values can indicate recorded queue drops. Raw observations are never sorted, interpolated, smoothed, merged, or deduplicated. Hardware timestamps can regress or arrive out of order; that is preserved and exposed in source statistics.

`monotonic_ns` is the source's hardware timestamp in Android's elapsed-realtime domain where provided; otherwise it is the callback's `elapsedRealtimeNanos`. `received_monotonic_ns` is the callback reception time in the same domain. Elapsed realtime includes suspend and resets on reboot. No timestamp is converted through floating point before storage. Sensor, location, and BLE timestamps are directly preserved. For raw GNSS, the receiver clock's optional elapsed-realtime timestamp is used when present; otherwise callback time is used. GNSS receiver `getTimeNanos` and satellite received-SV time are **different time domains** preserved as fields, not substituted for elapsed realtime.

`CLOCK_ANCHOR` records `wall_utc_ms`, `elapsed_before_ns`, `elapsed_after_ns`, `uptime_ms`, and wall read resolution. The wall-clock read lies within the monotonic bracket. This bounds read latency, not UTC clock error. Anchors are emitted at start/resume/stop and about every 30 seconds, including while paused. Clock changes are visible as differences between anchors. There is no assumption that wall time is continuous or perfectly synchronized.

Wi-Fi scan groups preserve Android's `timestamp_us` in microseconds since boot; the enclosing observation uses callback time. Wi-Fi RTT preserves `getRangingTimestampMillis`, in milliseconds since boot, per result. NMEA retains the sentence and supplied wall timestamp; the enclosing timestamp is callback time. Fields explicitly identify these domains.

Coordinates use WGS84 / EPSG:4326, latitude and longitude in decimal degrees. Android `Location.getAltitude` is meters above the WGS84 ellipsoid. Optional `getMslAltitudeMeters` is explicitly mean sea level from Android's supplied model, with independent optional uncertainty. No geoid conversion or floor assignment is invented. Pressure samples remain hPa; optional `derived_pressure_change_hpa` is marked as a difference from the first pressure sample, not elevation.

## Typed fields, nullability, and units

`Observation` contains kind, source ID, timestamp pair, repeated named `Field` values, repeated named `Group` objects, and an optional `SensorSample`. Source IDs distinguish sensor type/Android ID, individual location providers, GNSS streams, and radio APIs. Capability observations preserve source hardware name, vendor, range, resolution, power, min/max delay, FIFO capacity, wakeup/reporting flags, requested period, and registration result. Sensor values preserve Android's original float array order and precision, including nonfinite values. Accuracy is the raw Android accuracy enum.

`Value` is a protobuf `oneof` of signed 64-bit integer (`sint64`), IEEE double, string, byte array, or boolean. Integers are never coerced to double. A zero or false inside the oneof is a present observation. Missing optional values are omitted rather than filled with zero, and a missing `SensorSample` is distinct from an empty sample. Ordinary proto3 scalars outside a oneof use standard default values when absent. Consumers must not treat missing uncertainty as zero uncertainty.

Android GNSS/Location snapshots use exact public getter names as field names, for example `getReceivedSvTimeNanos`, `getPseudorangeRateMetersPerSecond`, `getAccumulatedDeltaRangeMeters`, `getBiasNanos`, and `getElapsedRealtimeUncertaintyNanos`. Public `hasX` predicates are retained; corresponding absent getters are omitted. Other public scalar getters, primitive arrays, nested GNSS/PVT values, and measurement/AGC/correlation groups are copied. Optional getter failures produce `{getter}_read_error`. Group paths preserve hierarchy and array indices (`clock`, `getMeasurements[0]`, and nested getter paths). Scalar array field names use `[index]`. The raw state flags retain their Android-defined bit values.

Radio field names encode units such as `rssi_dbm`, `frequency_mhz`, `timestamp_us`, `cn0_dbhz`, `carrier_frequency_hz`, `azimuth_deg`, and `elevation_deg`. RTT getter names use Android's documented units (`getDistanceMm`, `getDistanceStdDevMm`, `getRssi`, `getNumAttemptedMeasurements`, `getNumSuccessfulMeasurements`). Failed RTT results retain status and peer address without calling success-only getters. BLE advertisements are uninterpreted bytes; Android sentinel values such as unavailable transmit power remain raw values. Wi-Fi information elements preserve IDs/extensions and payload hex.

The schema enumerates all fourteen kinds: sensor, location, raw GNSS epoch, satellite status, navigation message, NMEA, Wi-Fi scan, BLE scan, RTT, clock anchor, lifecycle, health, capability, and source statistics. Consumers should accept unknown protobuf fields. Existing field numbers and enum meanings are never reused. Additive optional fields can remain v1; breaking semantic/framing changes require a new schema version and filename.

## Reference reader

`python tools/decode.py archive.zip` validates and summarizes the archive. Add `--jsonl output.jsonl` to stream every observation as typed JSON; bytes become base64. Missing proto3 scalar keys imply protobuf defaults. Nonfinite floats remain JSON `NaN`/`Infinity` tokens to preserve values, which some strict JSON consumers need to handle explicitly. Check exit status: 0 means valid, 2 means invalid. JSONL can contain a valid prefix when later validation fails; it is not an atomic import transaction.

The decoder uses the standard library and explicit protobuf wire decoding for the included schema; no Android or protoc installation is required. Its unknown-field handling supports additive evolution, but new named fields should be added to the reader's schema table to expose them in JSON. Standard generated protobuf readers can alternatively be produced with `protoc -I schema --python_out=... schema/recording.proto` and the matching Python protobuf runtime.

`campus-recovery.zip` is a separate forensic bundle. It can contain corrupt/incomplete bytes and is deliberately rejected by the validated archive reader. Retain originals when attempting custom forensic recovery.
