#!/usr/bin/env python3
"""
Builds the wallpaper store from the pictures in ./wallpapers

  wallpapers/<Category>/<picture>.jpg|png|webp

Creates (run it on your computer or in Termux, then commit + push):
  public/files/<id>.jpg      full picture (long side max 2560 px)
  public/files/<id>_t.jpg    thumbnail   (long side 480 px)
  data/wallpapers.json       the list the app reads

Needs:  pip install pillow
"""
import hashlib, json, os, re, sys
from PIL import Image, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "wallpapers")
OUT = os.path.join(ROOT, "public", "files")
DATA = os.path.join(ROOT, "data", "wallpapers.json")
EXT = {".jpg", ".jpeg", ".png", ".webp"}


def pretty(name):
    name = re.sub(r"[_\-]+", " ", os.path.splitext(name)[0]).strip()
    return name[:1].upper() + name[1:] if name else "Wallpaper"


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    items = []
    for cat in sorted(os.listdir(SRC)):
        cdir = os.path.join(SRC, cat)
        if not os.path.isdir(cdir):
            continue
        for fn in sorted(os.listdir(cdir)):
            if os.path.splitext(fn)[1].lower() not in EXT:
                continue
            path = os.path.join(cdir, fn)
            try:
                im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
            except Exception as e:
                print("skip", path, e)
                continue
            wid = hashlib.sha1((cat + "/" + fn).encode()).hexdigest()[:12]
            full = im.copy()
            full.thumbnail((2560, 2560))
            full_path = os.path.join(OUT, wid + ".jpg")
            full.save(full_path, "JPEG", quality=88, optimize=True)
            th = im.copy()
            th.thumbnail((480, 480))
            th.save(os.path.join(OUT, wid + "_t.jpg"), "JPEG", quality=80, optimize=True)
            items.append({
                "id": wid,
                "name": pretty(fn),
                "category": cat,
                "thumb": "/files/" + wid + "_t.jpg",
                "full": "/files/" + wid + ".jpg",
                "width": full.width,
                "height": full.height,
                "size": os.path.getsize(full_path),
            })
            print("ok", cat, fn)
    os.makedirs(os.path.dirname(DATA), exist_ok=True)
    with open(DATA, "w", encoding="utf-8") as f:
        json.dump({"items": items}, f, ensure_ascii=False, indent=1)
    print(len(items), "wallpapers")


if __name__ == "__main__":
    sys.exit(main())
