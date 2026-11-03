"""Verify the standalone full-core Android build, without installing it."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


CONSCRYPT_SHA256 = "752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1"
REQUIRED_CLASSES = {
    "Lcom/dsha/sidecar/net/runtime/NativeRuntime;",
    "Lcom/dsha/sidecar/net/proxy/native/NativeProxyServer;",
    "Lcom/dsha/sidecar/net/proxy/ech/ConscryptEchConnector;",
    "Lorg/conscrypt/NativeEchPolicyAdapter;",
    "Lorg/conscrypt/Conscrypt;",
    "Lorg/bouncycastle/cert/X509CertificateHolder;",
    "Lj$/time/Clock;",
    "Lj$/nio/file/Files;",
}


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Expected standard DEX")
    string_count, string_offset, type_count, type_offset = struct.unpack_from("<IIII", data, 56)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    if string_offset + string_count * 4 > len(data) or type_offset + type_count * 4 > len(data):
        raise ValueError("Invalid DEX identifier table")
    if class_offset + class_count * 32 > len(data):
        raise ValueError("Invalid DEX class table")
    classes = set()
    for index in range(class_count):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        if type_index >= type_count:
            raise ValueError("Invalid DEX type index")
        string_index = struct.unpack_from("<I", data, type_offset + type_index * 4)[0]
        if string_index >= string_count:
            raise ValueError("Invalid DEX string index")
        position = struct.unpack_from("<I", data, string_offset + string_index * 4)[0]
        for _ in range(5):
            value = data[position]
            position += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        classes.add(data[position:data.index(0, position)].decode("ascii"))
    return classes


def verify(apk, conscrypt_aar):
    if hashlib.sha256(conscrypt_aar.read_bytes()).hexdigest() != CONSCRYPT_SHA256:
        raise ValueError("Conscrypt Android AAR hash mismatch")
    library = "lib/arm64-v8a/libconscrypt_jni.so"
    with zipfile.ZipFile(apk) as archive, zipfile.ZipFile(conscrypt_aar) as provider:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        native = sorted(name for name in names if name.startswith("lib/"))
        if native != [library]:
            raise ValueError(f"Unexpected native libraries: {native}")
        if any("node" in name.lower() or name.endswith(".zip") for name in names):
            raise ValueError("Unexpected Node/payload entry")
        embedded = archive.read(library)
        if embedded != provider.read("jni/arm64-v8a/libconscrypt_jni.so"):
            raise ValueError("Packaged JNI differs from pinned provider")
        if embedded[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", embedded, 18)[0] != 183:
            raise ValueError("Expected little-endian arm64 ELF")
        classes = set()
        dex_names = sorted(name for name in names if name.startswith("classes") and name.endswith(".dex"))
        for name in dex_names:
            classes.update(dex_classes(archive.read(name)))
        missing = REQUIRED_CLASSES - classes
        if missing:
            raise ValueError(f"Missing compiled classes: {sorted(missing)}")
        return {
            "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "conscryptSha256": CONSCRYPT_SHA256,
            "nativeLibraries": native,
            "dexFiles": dex_names,
            "definedClassCount": len(classes),
            "requiredClasses": sorted(REQUIRED_CLASSES),
            "deviceExecution": False,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--conscrypt-aar", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    arguments = parser.parse_args()
    report = verify(arguments.apk, arguments.conscrypt_aar)
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    arguments.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
