"""Make a Stash speech model pack (card 26d) from the official sherpa-onnx Moonshine archive.

    python -I tools/make_model_pack.py <archive.tar.bz2 | extracted folder> [--out pack.zip] [--push]

The input is the archive from github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
(sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2 or the tiny one), or the folder it unpacks to.
Every file is checked against the sizes and sha256 below (the same as KnownModels in
app/src/main/java/app/stash/speech/ModelPacks.kt; a unit test keeps the two equal). The zip holds
`<pack id>/<file>` entries, stored (not compressed), and nothing else. Only English Moonshine packs are
listed: a non-English Moonshine file (non-commercial licence) is refused here and by the app.

--push copies the zip into the app's files on the connected phone with adb and asks the debug build to import
it (app.stash.debug.ModelImportReceiver), then prints files/debug/model-import.json. It writes to the phone:
only with the owner's yes. This script never downloads anything.
"""
import argparse
import hashlib
import os
import subprocess
import sys
import tarfile
import time
import zipfile

TOKENS = ("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049")
LICENSE = ("LICENSE", 13_344, "6148d7574a6554b7379b633cfd4c4fe5840c3f548d13bc83e00b52dc6fa00abd")

PACKS = {
    "moonshine-base-en-2026-02-27": {
        "archive": "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
        "files": [
            ("encoder_model.ort", 31_326_816, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
            ("decoder_model_merged.ort", 109_424_400, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
            TOKENS,
            LICENSE,
        ],
    },
    "moonshine-tiny-en-2026-02-27": {
        "archive": "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27",
        "files": [
            ("encoder_model.ort", 13_281_600, "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221"),
            ("decoder_model_merged.ort", 30_412_256, "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0"),
            TOKENS,
            LICENSE,
        ],
    },
}


def sha256(data_iter):
    h = hashlib.sha256()
    n = 0
    for chunk in data_iter:
        h.update(chunk)
        n += len(chunk)
    return n, h.hexdigest()


def chunks(f, size=1 << 20):
    while True:
        b = f.read(size)
        if not b:
            return
        yield b


def pick_pack(name):
    for pid, p in PACKS.items():
        if p["archive"] in name:
            return pid, p
    sys.exit("Not a known English Moonshine pack: %s\nKnown: %s" % (name, ", ".join(p["archive"] for p in PACKS.values())))


def open_source(path):
    """Yields (pack id, pack, {file name: callable returning a binary file object})."""
    base = os.path.basename(os.path.normpath(path))
    if os.path.isdir(path):
        pid, p = pick_pack(base)
        return pid, p, {name: (lambda n=name: open(os.path.join(path, n), "rb")) for name, _, _ in p["files"]}
    if path.endswith((".tar.bz2", ".tbz2", ".tar")):
        pid, p = pick_pack(base)
        tar = tarfile.open(path, "r:*")
        members = {}
        for m in tar.getmembers():
            if m.isfile():
                members[os.path.basename(m.name)] = m
        return pid, p, {name: (lambda n=name: tar.extractfile(members[n])) for name, _, _ in p["files"] if name in members}
    sys.exit("Give the .tar.bz2 archive or the folder it unpacks to: %s" % path)


def make(src, out):
    pid, p, opener = open_source(src)
    for name, size, digest in p["files"]:
        if name not in opener:
            sys.exit("Missing from the archive: %s" % name)
        with opener[name]() as f:
            n, h = sha256(chunks(f))
        if n != size or h != digest:
            sys.exit("Wrong file %s: %d bytes, sha256 %s; expected %d bytes, %s" % (name, n, h, size, digest))
        print("ok  %-26s %11d  %s" % (name, n, h))
    tmp = out + ".part"
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as z:
        for name, _, _ in p["files"]:
            info = zipfile.ZipInfo("%s/%s" % (pid, name), date_time=(2026, 2, 27, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            with opener[name]() as f, z.open(info, "w") as dst:
                for b in chunks(f):
                    dst.write(b)
    os.replace(tmp, out)
    print("Wrote %s (%d bytes), pack %s" % (out, os.path.getsize(out), pid))
    return pid


def adb(*args, stdin=None):
    r = subprocess.run(["adb", *args], stdin=stdin, capture_output=True)
    if r.returncode != 0:
        sys.exit("adb %s failed: %s" % (" ".join(args), r.stderr.decode(errors="replace").strip()))
    return r.stdout.decode(errors="replace")


def push(zip_path):
    print("Copying the pack into the app's files (debug build, run-as)...")
    with open(zip_path, "rb") as f:
        adb("exec-in", "run-as", "app.stash", "sh", "-c",
            "mkdir -p files/model-import && cat > files/model-import/pack.zip", stdin=f)
    adb("shell", "run-as", "app.stash", "rm", "-f", "files/debug/model-import.json")
    t0 = time.time()
    adb("shell", "am", "broadcast", "-n", "app.stash/app.stash.debug.ModelImportReceiver")
    for _ in range(120):
        time.sleep(1)
        r = subprocess.run(["adb", "exec-out", "run-as", "app.stash", "cat", "files/debug/model-import.json"], capture_output=True)
        if r.returncode == 0 and r.stdout.strip().startswith(b"{"):
            print("Import finished after about %.0f s:" % (time.time() - t0))
            print(r.stdout.decode(errors="replace"))
            return
    sys.exit("No result after 2 minutes: read files/debug/model-import.json later.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", help="the official .tar.bz2 archive, or the folder it unpacks to")
    ap.add_argument("--out", help="zip to write (default: <pack id>.zip here)")
    ap.add_argument("--push", action="store_true", help="copy to the phone and import (debug build; writes to the phone)")
    a = ap.parse_args()
    pid, _, _ = open_source(a.source)
    out = a.out or (pid + ".zip")
    make(a.source, out)
    if a.push:
        push(out)


if __name__ == "__main__":
    main()
