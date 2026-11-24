"""Check the shipped identity, shortcut, non-debuggable flag and release signer."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
client = json.loads((root / "client.json").read_text(encoding="utf-8"))
apk = Path(sys.argv[1])
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
suffix = ".exe" if os.name == "nt" else ""
aapt = str(tools / ("aapt" + suffix))
badging = subprocess.check_output([aapt, "dump", "badging", str(apk)]).decode("utf-8")
for expected in [f"name='{client['package']}'", f"versionCode='{client['versionCode']}'", f"versionName='{client['versionName']}'", f"application-label:'{client['name']}'"]:
    if expected not in badging:
        raise RuntimeError(f"Wrong release identity: {expected}")
if "application-debuggable" in badging:
    raise RuntimeError("Release client must not be debuggable")
manifest = subprocess.check_output([aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"]).decode("utf-8")
if "android.app.shortcuts" not in manifest:
    raise RuntimeError("Launcher shortcut metadata is missing")
resources = subprocess.check_output([aapt, "dump", "--values", "resources", str(apk)]).decode("utf-8")

def resource_value(name):
    match = re.search(rf'^\s+resource 0x[0-9a-f]+ {re.escape(client["package"])}:{re.escape(name)}:.*\n\s+\(string8\) "([^"]+)"', resources, re.MULTILINE)
    if match is None:
        raise RuntimeError(f"Missing release resource: {name}")
    return match.group(1)

shortcut = subprocess.check_output([aapt, "dump", "xmltree", str(apk), resource_value("xml/shortcuts")]).decode("utf-8")
if '"start_conversation"' not in shortcut or f'"{client["package"]}"' not in shortcut or '"io.github.mam15mon.dsh.feature.quick.QuickActivity"' not in shortcut:
    raise RuntimeError("Start-conversation shortcut targets the wrong activity")
with ZipFile(apk) as z:
    if resource_value("drawable/dsh_launcher_foreground") not in z.namelist():
        raise RuntimeError("This project's launcher icon is missing")
    if "assets/licenses/jackwener-DSH-Android.txt" not in z.namelist():
        raise RuntimeError("Upstream license is missing")
    main = root / "app/src/main"
    runtime_assets = ("engine.zip", "engine.sha256", "engine-version.txt", "shizuku/shizuku.apk", "shizuku/LICENSE")
    packaged = {f"assets/{name}": main / "assets" / name for name in runtime_assets}
    for lib in (main / "jniLibs/arm64-v8a").glob("*.so"):
        packaged[f"lib/arm64-v8a/{lib.name}"] = lib
    for entry, source in packaged.items():
        with z.open(entry) as embedded, source.open("rb") as original:
            if hashlib.file_digest(embedded, "sha256").digest() != hashlib.file_digest(original, "sha256").digest():
                raise RuntimeError(f"Packaged runtime differs from {source}: {entry}")
    with z.open("assets/engine.zip") as archive:
        expected = z.read("assets/engine.sha256").split()[0].decode("ascii")
        if hashlib.file_digest(archive, "sha256").hexdigest() != expected:
            raise RuntimeError("Engine archive does not match engine.sha256")
if resource_value("string/shortcut_start_conversation") != "开始对话":
    raise RuntimeError("Wrong launcher shortcut label")
signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
certificate = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)]).decode("utf-8")
keytool = Path(os.environ["JAVA_HOME"]) / "bin" / ("keytool" + suffix)
der = subprocess.check_output([str(keytool), "-exportcert", "-keystore", os.environ["DSH_ANDROID_KEYSTORE"], "-alias", "dsh_android", "-storepass:env", "DSH_ANDROID_STORE_PASSWORD"])
actual = re.search(r"Signer #1 certificate SHA-256 digest: ([a-f0-9]+)", certificate)
if actual is None or actual.group(1) != hashlib.sha256(der).hexdigest():
    raise RuntimeError("APK signer differs from the configured release key")
print(f"PASS: {client['name']} / {client['package']} / {client['versionName']}, launcher shortcut and release signer")
