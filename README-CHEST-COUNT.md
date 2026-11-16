# Chest Count

Automatic Fortnite chest-counting Android prototype.

## Project status
Stage 1: Android screen-capture permission and capture pipeline prototype.

## Important limitations
- This prototype does not detect or count chests yet.
- Android requires the user to approve screen capture each session.
- Screen capture is used only while the user explicitly starts the foreground capture service.
- Fortnite updates, visual effects, Android restrictions, and device performance may affect future detection accuracy.
- No guarantee of reliable automatic chest recognition until tested on real gameplay footage.

## Build
The Android app lives in `chest-count-android/`. GitHub Actions builds a debug APK when files in that folder change or when manually triggered.

## Development stages
1. Verify Android screen capture on the target Samsung phone.
2. Save and inspect sample frames locally for visual-detection experiments.
3. Prototype chest/open-event recognition against sample footage.
4. Add duplicate-event protection and confidence thresholds.
5. Add the floating HUD only after detection is validated.
6. Test performance and reliability on-device.

## Privacy
Captured frames are processed on-device in this prototype and are not uploaded by the app.
