"""Collect notification SDK license declarations and bundled notices from Notify's build cache."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
cache = root / ".local-build/gradle/caches/modules-2/files-2.1"
tree = (root / ".local-build/notification-dependencies.txt").read_text()
coordinates = set()
for group, name, version, replacement in re.findall(
        r"([\w.-]+):([\w.-]+):([\w.-]+)(?: -> ([\w.-]+))?", tree):
    if (group in {"com.google.firebase", "com.google.android.datatransport", "androidx.datastore",
                  "com.google.errorprone", "javax.inject"}
            or name in {"play-services-cloud-messaging", "play-services-stats",
                        "kotlinx-coroutines-play-services", "legacy-support-core-utils",
                        "documentfile", "localbroadcastmanager", "print"}):
        coordinates.add((group, name, replacement or version))
text = [
    "NOTIFY ANDROID NOTIFICATION SDK — DEPENDENCY NOTICES",
    "Published dependencies are unmodified. This inventory covers notification additions, not the entire app.",
    "Firebase and AndroidX source: https://github.com/firebase/firebase-android-sdk and https://android.googlesource.com/platform/frameworks/support/",
    "Google SDK binary license terms: https://developer.android.com/studio/terms.html",
    "Google Services Gradle plugin 4.4.4 (build only): Apache-2.0; https://github.com/google/play-services-plugins",
    "Notify's notification sound is original synthesized audio, with no third-party recording or composition.",
]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
documents = {}
for group, name, version in sorted(coordinates):
    directory = cache / group / name / version
    poms = list(directory.glob("*/*.pom"))
    licenses = []
    if poms:
        pom = ET.parse(poms[0])
        licenses = [(node.findtext("m:name", namespaces=ns), node.findtext("m:url", namespaces=ns))
                    for node in pom.findall("m:licenses/m:license", ns)]
    text += [f"\n{group}:{name}:{version}",
             "License: " + ("; ".join(f"{name} — {url}" for name, url in licenses)
                           or "Apache-2.0 (shared pre-existing dependency; see upstream source)")]
    for artifact in directory.glob("*/*"):
        if artifact.suffix not in {".aar", ".jar"}:
            continue
        with ZipFile(artifact) as archive:
            for path in archive.namelist():
                if ("license" in path.lower() or "notice" in path.lower()) and not path.endswith("/"):
                    data = archive.read(path)
                    if len(data) > 2 and not path.endswith(".json"):
                        digest = hashlib.sha256(data).hexdigest()
                        documents[digest] = data.decode("utf-8")
                        text.append(f"Bundled notice {path}: SHA-256 {digest}")
            if name == "play-services-cloud-messaging":
                index = json.loads(archive.read("third_party_licenses.json"))
                content = archive.read("third_party_licenses.txt")
                entry = index["Error Prone"]
                apache = content[entry["start"]:entry["start"] + entry["length"]].decode()
text += ["\nSHARED APACHE-2.0 LICENSE\n", apache]
text += [
    "\nAndroidX DataStore also repackages protobuf-java 4.28.2 under its own namespace.",
    "Version verified from the shipped RuntimeVersion class. Upstream license:",
    "https://github.com/protocolbuffers/protobuf/blob/v28.2/LICENSE",
    (root / "crypto/licenses/protobuf-4.28.2-LICENSE.txt").read_text(),
]
for digest, content in sorted(documents.items()):
    text += [f"\nBUNDLED NOTICES — SHA-256 {digest}\n", content]
output = root / "app/src/main/assets/third-party/notify-notifications.txt"
output.write_text("\n".join(text) + "\n")
print(f"Collected {len(coordinates)} notification dependency entries and {len(documents)} bundled notices.")
