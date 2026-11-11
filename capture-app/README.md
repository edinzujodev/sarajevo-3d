# Sarajevo 3D Capture, version 0.4

See ../README.md for the whole system (server that builds the 3D models).

A small Android app: tap the button, it takes one photo and writes the phone's
GPS position into it. Photos are saved in one folder per day, ready for OpenDroneMap / WebODM, which turns the photos into a measured
3D model. No API keys, no Google services, no internet needed.

## Install on your phone (Android 10 or newer)

1. Install Android Studio on your PC: https://developer.android.com/studio
2. File > Open, choose this `capture-app` folder. Let it sync (first time it
   downloads Gradle and libraries, a few minutes).
3. On the phone: Settings > About phone > tap "Build number" 7 times, then
   Settings > Developer options > turn on "USB debugging".
4. Connect the phone with a USB cable, accept the prompt on the phone.
5. In Android Studio pick your phone at the top and press the green Run button.
   "Sarajevo 3D" now appears on the phone.

To make an APK file to share with your team: Build > Build App Bundle(s) / APK(s)
> Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`; send it to
a phone and open it (allow "install unknown apps").

## Taking photos

- Wait until the top line shows GPS with ±10 m or better.
- Tap "Take photo". That's it; photos land in `Pictures/Sarajevo3D/<date>/`.
- For a 3D model, a place needs many photos from different spots (roughly 30 or
  more per building, each overlapping the next). They don't have to come from
  one person: photos from everyone of the same place can be combined.
- Sharp photos in daylight work best; cloudy days are ideal.

## Making the 3D model (on your PC)

1. Install Docker Desktop, then WebODM: https://github.com/OpenDroneMap/WebODM
   (`git clone`, then `./webodm.sh start`, open http://localhost:8000).
2. Copy the walk folder from the phone by USB (it is in Pictures/Sarajevo3D).
3. In WebODM: Add Project > Select Images and GCP > pick all photos of one place (from everyone who photographed it).
4. Options: start with the "Default" preset. Process.
5. When done, open "View 3D Model" for the textured model and point cloud.
   The measure tool gives distances, areas and heights.

Expect: 30 to 300 photos per building, 20 to 90 minutes to process on a normal PC.

## Known limits of 0.4

- Not yet compiled or tested on a real phone: the first Android Studio build may
  show small errors to fix.
- Photos wait on the phone and are sent to your own server (button "Server") automatically when on Wi-Fi.
- Phone GPS is 3 to 10 m off in narrow streets, so the model's size is accurate
  but its position on the map is only roughly right.
