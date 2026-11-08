"""Package reviewable source and test evidence; never includes SDKs, local.properties or signing secrets."""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/"deliverables"
REPORTS=OUT/"test-results"
REPORTS.mkdir(parents=True,exist_ok=True)

for source,dest in [
    ("core/build/reports/tests/test","core-html"),
    ("core/build/test-results/test","core-junit"),
    ("app/build/reports/tests/testDebugUnitTest","android-host-html"),
    ("app/build/test-results/testDebugUnitTest","android-host-junit")]:
    shutil.copytree(ROOT/source,REPORTS/dest,dirs_exist_ok=True)

for source,dest in [
    (".tools/final-build.log","gradle-build.log"),
    (".tools/python-tests.log","python-tests.log"),
    (".tools/decoder-validation.json","decoder-validation.json"),
    (".tools/signing-verification.txt","signing-verification.txt"),
    (".tools/zipalign-verification.txt","zipalign-verification.txt"),
    (".tools/apk-xmltree.txt","apk-xmltree.txt"),
    (".tools/apk-manifest.txt","apk-manifest.txt"),
    ("app/build/reports/lint-results-debug.txt","lint-debug.txt"),
    ("app/build/reports/lint-results-debug.html","lint-debug.html"),
    ("deliverables/synthetic-benchmark.json","synthetic-benchmark.json")]:
    shutil.copy2(ROOT/source,REPORTS/dest)

totals={}
for label,folder in [("core","core-junit"),("android_host","android-host-junit")]:
    cases=[]
    for path in (REPORTS/folder).glob("TEST-*.xml"):
        suite=ET.parse(path).getroot()
        cases.append({"suite":suite.get("name"),**{k:int(suite.get(k,0)) for k in ("tests","failures","errors","skipped")}})
    assert cases and all(c["failures"]==0 and c["errors"]==0 for c in cases)
    totals[label]=cases
totals["python"]={"tests":9,"status":"passed; see python-tests.log"}
totals["device_instrumentation"]={"compiled_cases":5,"executed":False,
    "reason":"No physical device; hypervisor driver absent; native headless emulator software-emulation attempts exited 0xC0000005 before boot."}
totals["date_local"]="2026-11-08"
(REPORTS/"summary.json").write_text(json.dumps(totals,indent=2),encoding="utf-8")
shutil.copy2(ROOT/"schema/recording.proto",OUT/"recording.proto")
shutil.copy2(ROOT/"tools/decode.py",OUT/"decode.py")
shutil.copy2(ROOT/"app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",OUT/"CampusRecorder-device-tests.apk")

top=["README.md","CURRENT_STATUS.md",".gitignore","settings.gradle.kts","build.gradle.kts","gradle.properties","gradlew","gradlew.bat",
     ".github/workflows/android-build.yml",
     "app/build.gradle.kts","core/build.gradle.kts"]
files=[ROOT/p for p in top]
for folder in ["app/src","core/src","schema","docs","tools","gradle/wrapper"]:
    files += [p for p in (ROOT/folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
files += [OUT/"campus-recordings-v1.zip",OUT/"synthetic-benchmark.json"]
files += [p for p in REPORTS.rglob("*") if p.is_file()]
with zipfile.ZipFile(OUT/"CampusRecorder-source.zip","w",zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for path in sorted(set(files)):
        name=path.relative_to(ROOT).as_posix()
        assert not any(part in name.split("/") for part in (".tools",".git",".gradle","build","__pycache__"))
        assert not name.endswith((".p12",".jks",".keystore"))
        archive.write(path,"CampusRecorder/"+name)

names=["CampusRecorder-1.0.0.apk","CampusRecorder-source.zip","CampusRecorder-device-tests.apk",
       "campus-recordings-v1.zip","recording.proto","decode.py","signing-certificate.pem","synthetic-benchmark.json"]
(OUT/"SHA256SUMS.txt").write_text("".join(hashlib.file_digest((OUT/n).open("rb"),"sha256").hexdigest()+"  "+n+"\n" for n in names),encoding="ascii")
print(json.dumps({"source_files":len(set(files)),"test_results":totals,"delivered_files":names},indent=2))
