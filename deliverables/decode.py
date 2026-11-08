#!/usr/bin/env python3
"""Campus Recorder v1 streaming reference decoder; Python 3.10+, standard library only.

This explicitly decodes the published protobuf wire schema. It is independent of Android
and of generated Java classes; unknown protobuf fields are skipped for forward compatibility.
No files are extracted from the ZIP. JSONL blob values are base64 strings.
"""
from __future__ import annotations
import argparse
import base64
import gzip
import hashlib
import io
import json
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import BinaryIO, Iterator

MAX_FRAME = 4 * 1024 * 1024
KINDS = ["KIND_UNSPECIFIED", "SENSOR", "LOCATION", "GNSS_RAW", "GNSS_SATELLITES", "GNSS_NAVIGATION",
         "GNSS_NMEA", "WIFI_SCAN", "BLE_SCAN", "WIFI_RTT", "CLOCK_ANCHOR", "LIFECYCLE", "HEALTH", "CAPABILITY", "SOURCE_STATS"]
# field number -> (name, wire semantic type, repeated)
SCHEMA = {
    "Value": {1:("integer","sint",False), 2:("real","double",False), 3:("text","string",False), 4:("blob","bytes",False), 5:("flag","bool",False)},
    "Field": {1:("name","string",False), 2:("value","Value",False)},
    "Group": {1:("name","string",False), 2:("fields","Field",True)},
    "Sensor": {1:("android_type","int32",False), 2:("accuracy","int32",False), 3:("values","packed_float",True)},
    "Observation": {1:("sequence","uint",False), 2:("source_id","string",False), 3:("kind","kind",False),
        4:("monotonic_ns","int64",False), 5:("received_monotonic_ns","int64",False),
        6:("fields","Field",True), 7:("groups","Group",True), 8:("sensor","Sensor",False)},
    "Header": {1:("schema_version","uint",False), 2:("session_id","string",False), 3:("chunk_index","uint",False),
        4:("clock_domain","string",False), 5:("coordinate_reference","string",False)},
    "Trailer": {1:("observation_count","uint",False), 2:("first_sequence","uint",False), 3:("last_sequence","uint",False), 4:("recovered","bool",False)},
    "Envelope": {1:("header","Header",False), 2:("observation","Observation",False), 3:("trailer","Trailer",False)}
}

class IntegrityError(ValueError):
    pass

def varint(stream: BinaryIO, eof_ok: bool=False) -> int | None:
    value = 0
    for shift in range(0, 70, 7):
        b = stream.read(1)
        if not b:
            if eof_ok and shift == 0:
                return None
            raise IntegrityError("Truncated protobuf varint")
        value |= (b[0] & 127) << shift
        if not b[0] & 128:
            if shift == 63 and b[0] > 1:
                raise IntegrityError("Protobuf varint overflow")
            return value
    raise IntegrityError("Invalid protobuf varint")

def exact(stream: BinaryIO, n: int) -> bytes:
    if n < 0 or n > MAX_FRAME:
        raise IntegrityError("Protobuf field/frame exceeds size bound")
    result = stream.read(n)
    if len(result) != n:
        raise IntegrityError("Truncated protobuf field/frame")
    return result

def message(payload: bytes, name: str) -> dict:
    stream = io.BytesIO(payload)
    output = {}
    spec = SCHEMA[name]
    while (tag := varint(stream, True)) is not None:
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise IntegrityError("Invalid zero protobuf field tag")
        if wire == 0:
            raw = varint(stream)
        elif wire == 1:
            raw = exact(stream, 8)
        elif wire == 2:
            raw = exact(stream, varint(stream))
        elif wire == 5:
            raw = exact(stream, 4)
        else:
            raise IntegrityError(f"Unsupported protobuf wire type {wire}")
        if number not in spec:
            continue
        field, kind, repeated = spec[number]
        expected = 1 if kind == "double" else 2 if kind in SCHEMA or kind in ("string", "bytes", "packed_float") else 0
        if wire != expected and not (kind == "packed_float" and wire == 5):
            raise IntegrityError(f"Wrong wire type for {name}.{field}")
        if kind in SCHEMA:
            value = message(raw, kind)
        elif kind == "string":
            value = raw.decode("utf-8")
        elif kind == "bytes":
            value = base64.b64encode(raw).decode("ascii")
        elif kind == "double":
            value = struct.unpack("<d", raw)[0]
        elif kind == "packed_float":
            if len(raw) % 4:
                raise IntegrityError("Misaligned packed float")
            value = list(struct.unpack("<" + "f" * (len(raw)//4), raw))
        elif kind == "sint":
            value = (raw >> 1) ^ -(raw & 1)
        elif kind in ("int32", "int64"):
            bits = 32 if kind == "int32" else 64
            value = raw & ((1 << bits)-1)
            if value >= 1 << (bits-1):
                value -= 1 << bits
        elif kind == "bool":
            value = bool(raw)
        elif kind == "kind":
            value = KINDS[raw] if raw < len(KINDS) else raw
        else:
            value = raw
        if repeated:
            output.setdefault(field, []).extend(value if kind == "packed_float" else [value])
        else:
            if name in ("Envelope", "Value"):
                output.clear()  # protobuf oneof uses last recognized member
            output[field] = value
    return output

def frame(stream: BinaryIO) -> dict | None:
    size = varint(stream, True)
    if size is None:
        return None
    if not 0 < size <= MAX_FRAME:
        raise IntegrityError("Frame outside size bound")
    return message(exact(stream, size), "Envelope")

def chunk_observations(stream: BinaryIO, session_id: str, index: int) -> Iterator[dict]:
    with gzip.GzipFile(fileobj=stream) as gz:
        first = frame(gz)
        if not first or "header" not in first:
            raise IntegrityError("Missing chunk header")
        h = first["header"]
        if h.get("schema_version") != 1 or h.get("session_id") != session_id or h.get("chunk_index", 0) != index:
            raise IntegrityError("Chunk header/version/session/index mismatch")
        count = first_seq = last_seq = 0
        while True:
            e = frame(gz)
            if e is None:
                raise IntegrityError("Missing chunk trailer")
            if "trailer" in e:
                t = e["trailer"]
                if (t.get("observation_count",0), t.get("first_sequence",0), t.get("last_sequence",0)) != (count,first_seq,last_seq):
                    raise IntegrityError("Chunk trailer count or sequence mismatch")
                if gz.read(1):
                    raise IntegrityError("Data follows chunk trailer")
                return
            if "observation" not in e:
                raise IntegrityError("Unexpected chunk envelope")
            o = e["observation"]
            seq = o.get("sequence",0)
            if seq <= last_seq:
                raise IntegrityError("Non-increasing sequence")
            if not count:
                first_seq = seq
            last_seq = seq
            count += 1
            yield o

def verify_checksums(archive: zipfile.ZipFile) -> None:
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise IntegrityError("Duplicate ZIP entry")
    if any(n.startswith("/") or ".." in n.split("/") or "\\" in n for n in names):
        raise IntegrityError("Unsafe archive path")
    checks = archive.getinfo("checksums.sha256")
    if checks.file_size > 16*1024*1024:
        raise IntegrityError("Checksum index too large")
    seen = set()
    with archive.open(checks) as stream:
        for raw in stream:
            line = raw.decode("ascii").rstrip("\n")
            match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
            if not match:
                raise IntegrityError("Malformed checksum index")
            digest, name = match.groups()
            if name in seen or name == "checksums.sha256":
                raise IntegrityError("Duplicate/self checksum entry")
            seen.add(name)
            with archive.open(name) as item:
                actual = hashlib.file_digest(item,"sha256").hexdigest()
            if actual != digest:
                raise IntegrityError(f"SHA-256 mismatch: {name}")
    if seen != set(names)-{"checksums.sha256"}:
        raise IntegrityError("Unchecked or missing archive entries")

def bounded_json(archive: zipfile.ZipFile, name: str) -> dict:
    if archive.getinfo(name).file_size > 16*1024*1024:
        raise IntegrityError("JSON metadata too large")
    return json.loads(archive.read(name))

def decode(path: str | Path, output=None) -> dict:
    with zipfile.ZipFile(path) as archive:
        verify_checksums(archive)
        manifest = bounded_json(archive,"manifest.json")
        if manifest.get("schema_version") != 1 or manifest.get("format") != "campus-recordings":
            raise IntegrityError("Unsupported archive format/version")
        if "schema/recording.proto" not in archive.namelist():
            raise IntegrityError("Missing schema")
        ids = manifest["session_ids"]
        if len(set(ids)) != len(ids) or manifest.get("session_count") != len(ids):
            raise IntegrityError("Manifest session count mismatch")
        summary = {"schema_version":1,"sessions":[],"total_observations":0,"integrity":"verified"}
        expected_entries = {"manifest.json","schema/recording.proto","checksums.sha256"}
        for session_id in ids:
            if not re.fullmatch(r"[A-Za-z0-9_-]+",session_id):
                raise IntegrityError("Invalid session id")
            prefix = f"sessions/{session_id}/"
            metadata_path = prefix+"metadata.json"
            expected_entries.add(metadata_path)
            metadata = bounded_json(archive,metadata_path)
            if metadata.get("id") != session_id or metadata.get("schemaVersion") != 1:
                raise IntegrityError("Session metadata identity/version mismatch")
            names = sorted(n for n in archive.namelist() if n.startswith(prefix+"chunks/") and n.endswith(".pb.gz"))
            count = last = 0
            kinds = {}
            for index,name in enumerate(names):
                if name != prefix+f"chunks/{index:08d}.pb.gz":
                    raise IntegrityError("Missing/out-of-order chunk")
                expected_entries.add(name)
                with archive.open(name) as stream:
                    for observation in chunk_observations(stream,session_id,index):
                        if observation["sequence"] <= last:
                            raise IntegrityError("Session sequence ordering mismatch")
                        last = observation["sequence"]
                        count += 1
                        kind = observation.get("kind","KIND_UNSPECIFIED")
                        kinds[kind] = kinds.get(kind,0)+1
                        if output is not None:
                            output.write(json.dumps({"session_id":session_id,**observation},allow_nan=True,separators=(",",":"))+"\n")
            if count != metadata["observationCount"] or len(names) != metadata["chunkCount"]:
                raise IntegrityError("Session observation/chunk count mismatch")
            if metadata.get("state") in ("recording","paused","initializing","damaged"):
                raise IntegrityError("Archive contains unfinalized/damaged session")
            summary["sessions"].append({"id":session_id,"state":metadata["state"],"synthetic":metadata.get("synthetic",False),
                "observations":count,"chunks":len(names),"dropped_observations":metadata.get("droppedObservations",0),"kinds":kinds})
            summary["total_observations"] += count
        if set(archive.namelist()) != expected_entries:
            raise IntegrityError("Unexpected/orphan archive entries")
        return summary

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive",type=Path)
    parser.add_argument("--jsonl",help="Stream all observations to file; '-' means stdout")
    args = parser.parse_args()
    output = None
    try:
        if args.jsonl:
            output = sys.stdout if args.jsonl == "-" else open(args.jsonl,"w",encoding="utf-8")
        summary = decode(args.archive,output)
        print(json.dumps(summary,indent=2),file=sys.stderr if args.jsonl == "-" else sys.stdout)
        return 0
    except (IntegrityError,KeyError,ValueError,OSError,zipfile.BadZipFile,EOFError) as exc:
        print(f"INVALID RECORDING: {exc}",file=sys.stderr)
        return 2
    finally:
        if output is not None and output is not sys.stdout:
            output.close()

if __name__ == "__main__":
    raise SystemExit(main())
