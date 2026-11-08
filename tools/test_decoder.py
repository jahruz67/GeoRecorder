"""Independent corruption and wire-format tests. Run after :core:synthetic."""
import gzip
import hashlib
import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from decode import decode, message, IntegrityError, chunk_observations

EXAMPLE = Path(__file__).resolve().parents[1] / "deliverables/campus-recordings-v1.zip"

def patched_archive(destination, mutate, recalculate=False):
    with zipfile.ZipFile(EXAMPLE) as z:
        items = {n:z.read(n) for n in z.namelist()}
    mutate(items)
    if recalculate:
        items["checksums.sha256"] = "".join(hashlib.sha256(v).hexdigest()+"  "+n+"\n" for n,v in items.items() if n!="checksums.sha256").encode()
    with zipfile.ZipFile(destination,"w") as z:
        for name,data in items.items():
            z.writestr(name,data)

class DecoderTest(unittest.TestCase):
    def test_example_round_trip_all_observations(self):
        class Sink:
            count=0
            def write(self,line):
                self.count += 1
                obj=json.loads(line)
                self.assert_sample(obj)
            @staticmethod
            def assert_sample(obj):
                if obj["kind"]=="SENSOR":
                    assert obj["sensor"]["android_type"] == 1
                    assert len(obj["sensor"]["values"]) == 3
                assert obj["fields"][0]["value"]["flag"] is True
        sink=Sink()
        result=decode(EXAMPLE,sink)
        self.assertEqual(240014,result["total_observations"])
        self.assertEqual(240014,sink.count)
        self.assertEqual(14,len(result["sessions"][1]["kinds"]))
        self.assertTrue(result["sessions"][0]["synthetic"])

    def test_sha256_detects_modified_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"bad.zip"
            patched_archive(f,lambda items: items.__setitem__("manifest.json",items["manifest.json"]+b" "))
            with self.assertRaisesRegex(IntegrityError,"SHA-256"):
                decode(f)

    def test_corrupted_gzip_even_with_updated_checksum(self):
        def mutate(items):
            n=next(n for n in items if n.endswith(".pb.gz"))
            data=bytearray(items[n]); data[-8]^=128; items[n]=bytes(data)
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"bad.zip"; patched_archive(f,mutate,True)
            with self.assertRaises((IntegrityError,OSError,EOFError)):
                decode(f)

    def test_missing_chunk_detected_by_metadata(self):
        def mutate(items):
            items.pop(next(n for n in items if n.endswith(".pb.gz")))
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"bad.zip"; patched_archive(f,mutate,True)
            with self.assertRaises(IntegrityError):
                decode(f)

    def test_unchecked_archive_entry_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"bad.zip"
            patched_archive(f,lambda items: items.__setitem__("extra",b"unverified"))
            with self.assertRaisesRegex(IntegrityError,"Unchecked"):
                decode(f)

    def test_session_count_mismatch_rejected(self):
        def mutate(items):
            m=json.loads(items["manifest.json"]); m["session_count"]+=1; items["manifest.json"]=json.dumps(m).encode()
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"bad.zip"; patched_archive(f,mutate,True)
            with self.assertRaisesRegex(IntegrityError,"session count"):
                decode(f)

    def test_wire_signed_integer_double_and_float(self):
        self.assertEqual({"integer":-1},message(b"\x08\x01","Value"))
        self.assertEqual({"real":1.23456789},message(b"\x11"+struct.pack("<d",1.23456789),"Value"))
        self.assertEqual({"values":[1.0,2.0]},message(b"\x1a\x08"+struct.pack("<ff",1,2),"Sensor"))
        self.assertEqual({"integer":0},message(b"\x08\x00","Value"))

    def test_truncated_and_oversized_frame_rejected(self):
        for data in (b"\x08",b"\x1a\x10\x00",b"\x00"):
            with self.assertRaises(IntegrityError):
                message(data,"Observation")

    def test_proto_unknown_field_compatible(self):
        self.assertEqual({"integer":7},message(b"\x08\x0e\x98\x06\x01","Value"))

if __name__ == "__main__":
    unittest.main(verbosity=2)
