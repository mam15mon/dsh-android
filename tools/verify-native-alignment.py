"""Check the actual APK's ELF LOAD/RELRO alignment and ZIP alignment."""
from pathlib import Path
import os
import struct
import subprocess
import sys
import zipfile


def verify(apk, sdk):
    failures = []
    count = 0
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/arm64-v8a/") or not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                raise RuntimeError(f"Expected ELF64 little-endian: {entry.filename}")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, number = struct.unpack_from("<HH", data, 54)
            headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(number)]
            loads = [h for h in headers if h[0] == 1]
            if not loads or any(h[7] < 16384 or h[7] & (h[7] - 1) or (h[2] - h[3]) % 16384 for h in loads):
                failures.append(f"{entry.filename}: LOAD alignment")
            # Android's dynamic linker enforces RELRO; static executables use their own startup.
            if any(h[0] == 2 for h in headers):
                for h in headers:
                    if h[0] != 0x6474E552:
                        continue
                    end = h[3] + h[6]
                    if end % 16384 and any(l[1] & 2 and l[3] <= h[3] and end < l[3] + l[6] for l in loads):
                        failures.append(f"{entry.filename}: RELRO alignment")
            count += 1
            print(f"ELF checked: {entry.filename}")
    if count == 0:
        raise RuntimeError("APK contains no arm64 native libraries")
    if failures:
        raise RuntimeError("\n".join(failures))
    zipalign = Path(sdk) / "build-tools/36.0.0" / ("zipalign.exe" if os.name == "nt" else "zipalign")
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)], check=True)
    print(f"PASS: {count} ELF files and APK ZIP alignment")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(os.environ["ANDROID_HOME"]))
