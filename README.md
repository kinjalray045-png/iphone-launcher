# iPhone-style Launcher (OnePlus 13)

An iOS-style Android home screen: squircle icons, 4-column grid, dock, page dots,
"Search" pill, date/time widget on page one, long-press for App Info / Remove App.

## Build the APK
Needs Android Studio (or the Android SDK + JDK 17) and internet for the first sync.

Android Studio: open this folder, let Gradle sync, then Build > Build APK(s).
Command line:  ./gradlew assembleDebug
Output:        app/build/outputs/apk/debug/app-debug.apk

## Install on the OnePlus 13
1. Copy the APK to the phone and open it (allow "install unknown apps" for your file manager).
2. Settings > Apps > Default apps > Home app > pick "Phone Launcher".
   (Or press Home and choose it under "Always".)

## Notes
- Not tested on a device. Written without access to an Android SDK.
- Icon shape, size and grid spacing are scaled from iPhone proportions, not pixel-exact.
- No Apple wallpapers, icons or fonts are included; your own wallpaper shows through.
- No blur on the dock/search pill (translucent fills instead). No icon wiggle/rearrange mode.
