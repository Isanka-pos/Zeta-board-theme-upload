"""Makes the small "delta" update files for the in-app updater.

Runs in GitHub Actions after the new ZetaBoard.apk and update.json exist, before the release is created.
For each of the last few published versions it downloads that version's APK, builds a patch
(old APK -> new APK) with bsdiff, repacks it as a zlib based file (ZDP1, read by DeltaPatch.kt)
and records it in update.json. A phone on version N then downloads only the patch (usually a few MB
instead of the whole APK) and rebuilds the new APK from the APK it already has.

If anything fails here the build still publishes the full APK, and the app simply downloads the full file.
Needs: bsdiff (apt install bsdiff), the gh CLI and env GH_TOKEN, REPO, VC.
"""
import bz2
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zlib

KEEP = 3          # patches for the last 3 versions (older phones just do the full download)
MAX_RATIO = 0.6   # a patch bigger than 60% of the APK is not worth it


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def convert(bsdiff_data):
    """BSDIFF40 (bzip2 blocks) -> ZDP1 (zlib blocks)."""
    d = bsdiff_data
    if d[:8] != b"BSDIFF40":
        raise ValueError("not a BSDIFF40 patch")
    clen, dlen, nsize = struct.unpack("<qqq", d[8:32])
    ctrl = bz2.decompress(d[32:32 + clen])
    diff = bz2.decompress(d[32 + clen:32 + clen + dlen])
    rest = d[32 + clen + dlen:]
    extra = bz2.decompress(rest) if rest else b""
    out = b"ZDP1" + struct.pack(">q", nsize)
    for blob in (ctrl, diff, extra):
        c = zlib.compress(blob, 9)
        out += struct.pack(">q", len(c)) + c
    return out


def previous_codes(vc):
    res = subprocess.run(
        ["gh", "release", "list", "--limit", "15", "--json", "tagName", "-q", ".[].tagName"],
        capture_output=True, text=True, check=True)
    codes = []
    for tag in res.stdout.split():
        m = re.fullmatch(r"v(\d+)", tag)
        if m and int(m.group(1)) < vc:
            codes.append(int(m.group(1)))
    return sorted(codes, reverse=True)[:KEEP]


def main():
    repo = os.environ["REPO"]
    vc = int(os.environ["VC"])
    new_apk = "ZetaBoard.apk"
    new_size = os.path.getsize(new_apk)

    with open("update.json", encoding="utf-8") as f:
        upd = json.load(f)
    upd["apkSize"] = new_size
    upd["apkSha256"] = sha256(new_apk)
    upd["patches"] = {}
    # write it now so the file is valid even if the patch step fails below
    with open("update.json", "w", encoding="utf-8") as f:
        json.dump(upd, f, ensure_ascii=False)

    try:
        codes = previous_codes(vc)
    except Exception as e:
        print("could not list releases:", e)
        return

    for code in codes:
        try:
            with tempfile.TemporaryDirectory() as tmp:
                subprocess.run(["gh", "release", "download", "v%d" % code, "-p", "ZetaBoard.apk", "-D", tmp],
                               check=True)
                old_apk = os.path.join(tmp, "ZetaBoard.apk")
                raw = os.path.join(tmp, "p.bsdiff")
                subprocess.run(["bsdiff", old_apk, new_apk, raw], check=True)
                with open(raw, "rb") as f:
                    zdp = convert(f.read())
            if len(zdp) > new_size * MAX_RATIO:
                print("v%d: patch is %d bytes, too big, skipped" % (code, len(zdp)))
                continue
            name = "ZetaBoard-from-%d.zdp" % code
            with open(name, "wb") as f:
                f.write(zdp)
            upd["patches"][str(code)] = {
                "url": "https://github.com/%s/releases/download/v%d/%s" % (repo, vc, name),
                "size": len(zdp),
            }
            print("v%d -> v%d: patch %d bytes (full APK %d bytes)" % (code, vc, len(zdp), new_size))
        except Exception as e:
            print("v%d: patch failed (%s), skipped" % (code, e))

    with open("update.json", "w", encoding="utf-8") as f:
        json.dump(upd, f, ensure_ascii=False)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("patch step failed:", e)
        sys.exit(0)
