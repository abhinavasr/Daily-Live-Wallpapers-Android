# Depth Live Wallpaper Android Release

## Current Play upload artifact

- Version name: `0.1.0`
- Version code: `1`
- Application ID: `xyz.abhinava.depthwallpaper`
- Signed AAB: `app/build/outputs/bundle/release/app-release.aab`
- Copied artifact: `/home/abhinava/.openclaw/workspace/public-downloads/depth-wallpaper/depth-wallpaper-0.1.0-v1-release.aab`
- SHA-256: `747defee39491d984fecf6a733e9d0cfd91de0d8e89744d05c19c13bccb2b2b1`

## Upload key

- Keystore: `keystores/depth-wallpaper-upload.jks`
- Signing properties: `release-signing.properties`
- Alias: `upload`
- SHA-1: `78:7C:97:61:4F:1D:16:B9:50:BF:01:57:59:A3:C7:A1:10:ED:CD:4F`
- SHA-256: `84:4B:77:E9:C4:C3:1E:34:C3:5C:4D:F4:3E:E7:BD:F9:89:B9:5F:A7:FB:EC:9B:AA:65:55:31:08:24:EF:46:BE`

Keep the keystore and `release-signing.properties` backed up securely. Losing the upload key can block future Play Store updates unless Play App Signing upload-key reset is available.

## Build command

```bash
cd /home/abhinava/.openclaw/workspace/side-projects/depth-live-wallpaper/android
JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21/libexec /home/linuxbrew/.linuxbrew/bin/gradle clean bundleRelease lintVitalRelease --no-daemon
```

The project pins Gradle Java home to JDK 21 in `gradle.properties` because Android Gradle lint failed under JDK 25.
