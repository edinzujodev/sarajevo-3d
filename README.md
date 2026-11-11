# Sarajevo 3D

Take photos with the phone app; your PC turns them into 3D models and shows them
on a map. Everything runs on your own PC: no API keys, no cloud.

```
phone app  --photo + GPS over Wi-Fi-->  server (PC, port 8000)  --photos-->  NodeODM (PC, Docker, port 3000)
                                              |  groups photos into places by GPS  |  builds the 3D model
                                              <--------------- 3D model -----------
                                        map + 3D viewer at http://<PC address>:8000
```

## What you need on the PC (once)

1. **Docker Desktop**: https://www.docker.com/products/docker-desktop/ (start it).
2. **Python 3.10 or newer**: https://www.python.org/downloads/ (tick "Add Python to PATH").
3. **Android Studio** for the phone app: https://developer.android.com/studio

## Start it

1. Double-click `server\start-nodeodm.bat`. The first time it downloads OpenDroneMap
   (about 3 GB). It keeps running in Docker after that.
2. Double-click `server\start-server.bat`. The first time it installs the Python
   packages. It prints your PC's address, for example `192.168.1.20`.
   If Windows Firewall asks, allow access on private networks.
3. Open http://localhost:8000 on the PC: the map with places and the 3D viewer.
   http://localhost:8000/health shows whether the server can reach NodeODM.

## Install the phone app

1. In Android Studio: File > Open > `capture-app`. Wait for the sync.
2. On the phone: Settings > About phone > tap "Build number" 7 times, then
   Developer options > turn on "USB debugging". Connect the USB cable.
3. Pick your phone at the top of Android Studio and press Run.
4. In the app tap **Server** and type the PC's address with port 8000, for example
   `192.168.1.20:8000`. The phone must be on the same Wi-Fi as the PC.

## Using it

- Tap **Take photo**. Each photo is saved on the phone and sent to the server.
- The server groups photos taken within 40 m of each other into one place.
- When a place has **30 photos**, the server starts building the 3D model
  automatically (or press "Build 3D now" on the map page, from 5 photos).
  After 20 more photos it rebuilds and keeps the old version.
- Building takes about 10 to 60 minutes per place on a normal PC.
- Tap **3D map** in the app to open the map page on the phone.

For a good model: photograph the same building from many spots around it, each
photo overlapping the next, in daylight. Photos from different people combine.

## Settings (environment variables before start-server.bat)

| Variable | Default | Meaning |
| --- | --- | --- |
| MIN_PHOTOS | 30 | photos before the first automatic build |
| REBUILD_AFTER | 20 | new photos before a rebuild |
| PLACE_RADIUS_M | 40 | photos within this distance count as one place |
| PC_QUALITY | medium | point cloud quality: lowest, low, medium, high, ultra |
| NODEODM_URL | http://localhost:3000 | where NodeODM runs |

## Files

- `capture-app/` Android app (Kotlin).
- `server/server.py` the server; `server/index.html` the map and 3D viewer page.
- `server/data/` created when running: photos per place, finished models (.glb), places.json.

## Known limits

- The Android app has not been compiled yet; the first build may need small fixes.
- OpenDroneMap is made mostly for drone photos. Street-level phone photos work,
  but a building with few angles may come out patchy. More photos fix most of it.
- Models are shown one by one, not yet joined into one city.
- Phone GPS can be 3 to 10 m off, so place positions are approximate.
- No face blurring yet; keep that in mind before sharing the map publicly.
