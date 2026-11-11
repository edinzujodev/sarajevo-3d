"""
Sarajevo 3D server.

Runs on your PC. The phone app uploads every photo here. The server groups
photos into "places" by GPS (photos within PLACE_RADIUS_M of each other belong
to the same place). When a place has MIN_PHOTOS photos it sends them to
NodeODM (OpenDroneMap, running in Docker on the same PC), waits for the 3D
model, converts it to a .glb file and shows it on a map at http://<pc>:8000/.

No API keys, no cloud: everything runs on your own computer.
"""

import json
import math
import os
import shutil
import threading
import time
import uuid
import zipfile
from pathlib import Path

import requests
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image

# ---- settings (change with environment variables) ---------------------------
NODEODM_URL = os.environ.get("NODEODM_URL", "http://localhost:3000")
MIN_PHOTOS = int(os.environ.get("MIN_PHOTOS", "30"))      # photos before the first build
REBUILD_AFTER = int(os.environ.get("REBUILD_AFTER", "20"))  # new photos before a rebuild
PLACE_RADIUS_M = float(os.environ.get("PLACE_RADIUS_M", "40"))
ODM_OPTIONS = [
    {"name": "pc-quality", "value": os.environ.get("PC_QUALITY", "medium")},
    {"name": "feature-quality", "value": "high"},
    {"name": "mesh-size", "value": 300000},
]

BASE = Path(__file__).resolve().parent
DATA = BASE / "data"
PHOTOS = DATA / "photos"
MODELS = DATA / "models"
WORK = DATA / "work"
DB_FILE = DATA / "places.json"
for d in (PHOTOS, MODELS, WORK):
    d.mkdir(parents=True, exist_ok=True)

lock = threading.Lock()
app = FastAPI(title="Sarajevo 3D")


# ---- small JSON "database" ---------------------------------------------------
def load_db():
    if DB_FILE.exists():
        return json.loads(DB_FILE.read_text(encoding="utf-8"))
    return {"places": []}


def save_db(db):
    tmp = DB_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(db, indent=2), encoding="utf-8")
    tmp.replace(DB_FILE)


def find_place(db, place_id):
    for p in db["places"]:
        if p["id"] == place_id:
            return p
    return None


# ---- GPS helpers ------------------------------------------------------------
def _to_deg(value):
    d, m, s = (float(x) for x in value)
    return d + m / 60 + s / 3600


def read_gps(path):
    """Return (lat, lon) from a JPEG's EXIF, or None."""
    try:
        exif = Image.open(path).getexif()
        gps = exif.get_ifd(0x8825)
        if not gps or 2 not in gps or 4 not in gps:
            return None
        lat = _to_deg(gps[2]) * (-1 if gps.get(1) == "S" else 1)
        lon = _to_deg(gps[4]) * (-1 if gps.get(3) == "W" else 1)
        if lat == 0 and lon == 0:
            return None
        return lat, lon
    except Exception:
        return None


def distance_m(a_lat, a_lon, b_lat, b_lon):
    r = 6371000
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# ---- building 3D with NodeODM -------------------------------------------------
def build_place(place_id):
    """Send all photos of a place to NodeODM and store the finished model."""
    with lock:
        db = load_db()
        place = find_place(db, place_id)
        photos = list(place["photos"])
        place["status"] = "processing"
        place["progress"] = 0
        place["error"] = None
        save_db(db)

    def update(**fields):
        with lock:
            db = load_db()
            p = find_place(db, place_id)
            p.update(fields)
            save_db(db)

    try:
        files = [("images", (name, open(PHOTOS / place_id / name, "rb"), "image/jpeg"))
                 for name in photos]
        try:
            r = requests.post(
                f"{NODEODM_URL}/task/new",
                files=files,
                data={"name": f"sarajevo3d-{place_id}", "options": json.dumps(ODM_OPTIONS)},
                timeout=600,
            )
        finally:
            for _, (_, fh, _) in files:
                fh.close()
        r.raise_for_status()
        task = r.json().get("uuid")
        if not task:
            raise RuntimeError(f"NodeODM did not start a task: {r.text[:300]}")
        update(task=task)

        # Wait for NodeODM. Status codes: 10 queued, 20 running, 30 failed, 40 done, 50 cancelled.
        while True:
            time.sleep(10)
            info = requests.get(f"{NODEODM_URL}/task/{task}/info", timeout=60).json()
            code = info.get("status", {}).get("code")
            update(progress=round(info.get("progress", 0)))
            if code == 40:
                break
            if code in (30, 50):
                msg = info.get("status", {}).get("errorMessage") or "NodeODM task failed"
                raise RuntimeError(msg)

        # Download the results and keep the textured 3D model.
        work = WORK / place_id
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir(parents=True)
        zip_path = work / "all.zip"
        with requests.get(f"{NODEODM_URL}/task/{task}/download/all.zip", stream=True, timeout=600) as dl:
            dl.raise_for_status()
            with open(zip_path, "wb") as f:
                for chunk in dl.iter_content(1 << 20):
                    f.write(chunk)
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(work)

        version = time.strftime("%Y%m%d-%H%M%S")
        out = MODELS / f"{place_id}-{version}.glb"
        make_glb(work, out)
        with lock:
            db = load_db()
            p = find_place(db, place_id)
            p["status"] = "ready"
            p["progress"] = 100
            p["model"] = f"/models/{out.name}"
            p.setdefault("versions", []).append({"date": version, "model": p["model"],
                                                  "photos": len(photos)})
            p["built_with"] = len(photos)
            save_db(db)
    except Exception as e:  # noqa: BLE001
        update(status="failed", error=str(e)[:500])


def make_glb(work, out):
    """Find ODM's textured model and save it as a single .glb file."""
    tex = work / "odm_texturing"
    glb = next(iter(sorted(tex.glob("*.glb"))), None) if tex.exists() else None
    if glb:  # newer ODM versions write a .glb themselves
        shutil.copy(glb, out)
        return
    objs = sorted(work.rglob("odm_textured_model_geo.obj")) or sorted(work.rglob("*.obj"))
    if not objs:
        raise RuntimeError("No 3D model in the NodeODM results")
    import trimesh  # imported here so the server starts even without it

    scene = trimesh.load(objs[0], force="scene")
    # Move the model to the origin so viewers can frame it (ODM uses map coordinates).
    scene.apply_translation(-scene.bounds.mean(axis=0))
    scene.export(out)


def maybe_build(place_id):
    with lock:
        db = load_db()
        p = find_place(db, place_id)
        count = len(p["photos"])
        built = p.get("built_with", 0)
        busy = p["status"] == "processing"
    if busy:
        return
    if (built == 0 and count >= MIN_PHOTOS) or (built and count - built >= REBUILD_AFTER):
        threading.Thread(target=build_place, args=(place_id,), daemon=True).start()


# ---- web API -----------------------------------------------------------------
@app.post("/upload")
async def upload(photo: UploadFile = File(...)):
    tmp = WORK / f"upload-{uuid.uuid4().hex}.jpg"
    with open(tmp, "wb") as f:
        shutil.copyfileobj(photo.file, f)
    gps = read_gps(tmp)
    if gps is None:
        tmp.unlink(missing_ok=True)
        raise HTTPException(400, "Photo has no GPS position. Wait for GPS in the app and try again.")
    lat, lon = gps

    with lock:
        db = load_db()
        best, best_d = None, None
        for p in db["places"]:
            d = distance_m(lat, lon, p["lat"], p["lon"])
            if d <= PLACE_RADIUS_M and (best_d is None or d < best_d):
                best, best_d = p, d
        if best is None:
            best = {"id": uuid.uuid4().hex[:8], "lat": lat, "lon": lon, "photos": [],
                    "status": "collecting", "progress": 0, "model": None, "error": None,
                    "created": time.strftime("%Y-%m-%d %H:%M:%S")}
            db["places"].append(best)
        folder = PHOTOS / best["id"]
        folder.mkdir(parents=True, exist_ok=True)
        name = os.path.basename(photo.filename or "") or f"{uuid.uuid4().hex}.jpg"
        if (folder / name).exists():
            name = f"{uuid.uuid4().hex[:6]}-{name}"
        shutil.move(str(tmp), folder / name)
        best["photos"].append(name)
        n = len(best["photos"])
        # Keep the place centre as the average of its photos.
        best["lat"] += (lat - best["lat"]) / n
        best["lon"] += (lon - best["lon"]) / n
        save_db(db)
        place_id = best["id"]

    maybe_build(place_id)
    need = max(0, MIN_PHOTOS - n)
    return {"place": place_id, "photos": n, "needed_for_3d": need}


@app.get("/places")
def places():
    db = load_db()
    return [{k: v for k, v in p.items() if k != "photos"} | {"photo_count": len(p["photos"])}
            for p in db["places"]]


@app.post("/places/{place_id}/build")
def build_now(place_id: str):
    with lock:
        db = load_db()
        p = find_place(db, place_id)
        if not p:
            raise HTTPException(404, "No such place")
        if p["status"] == "processing":
            return {"ok": True, "status": "already processing"}
        if len(p["photos"]) < 5:
            raise HTTPException(400, "Need at least 5 photos")
    threading.Thread(target=build_place, args=(place_id,), daemon=True).start()
    return {"ok": True}


@app.get("/health")
def health():
    try:
        info = requests.get(f"{NODEODM_URL}/info", timeout=5).json()
        return {"server": "ok", "nodeodm": "ok", "nodeodm_version": info.get("engineVersion")}
    except Exception as e:  # noqa: BLE001
        return {"server": "ok", "nodeodm": f"not reachable at {NODEODM_URL}: {e}"}


@app.get("/", response_class=HTMLResponse)
def index():
    return FileResponse(BASE / "index.html")


app.mount("/models", StaticFiles(directory=MODELS), name="models")
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")


# Resume builds that were interrupted when the server stopped.
def _resume():
    db = load_db()
    for p in db["places"]:
        if p["status"] == "processing":
            p["status"] = "collecting"
            p["built_with"] = p.get("built_with", 0) if p.get("model") else 0
    save_db(db)
    for p in db["places"]:
        maybe_build(p["id"])


_resume()
