# Depth Live Wallpaper Android Release

## Current Play upload artifact

- Version name: `0.2.2`
- Version code: `13`
- Application ID: `xyz.abhinava.depthwallpaper`
- Target SDK: `36` / Android 16
- Optimization: R8 enabled (`minifyReleaseWithR8`) and release resource shrinking enabled
- Signed AAB: `app/build/outputs/bundle/release/app-release.aab`
- Public artifact: `https://ai.abhinava.xyz/agent/downloads/depth-wallpaper-0.2.2-v13-release.aab`
- Copied artifacts:
  - `/home/abhinava/.openclaw/workspace/managed-nginx/public/downloads/depth-wallpaper-0.2.2-v13-release.aab`
  - `/home/abhinava/.openclaw/workspace/public-downloads/depth-wallpaper-0.2.2-v13-release.aab`
- SHA-256: `f140d495240e37423409e097bbaa77ca7bae88ab5ebe6a1bd1c1629d42bce6a2`
- Size: `809,582` bytes
- Signing certificate SHA-1: `78:7C:97:61:4F:1D:16:B9:50:BF:01:57:59:A3:C7:A1:10:ED:CD:4F`
- Signing certificate SHA-256: `84:4B:77:E9:C4:C3:1E:34:C3:5C:4D:F4:3E:E7:BD:F9:89:B9:5F:A7:FB:EC:9B:AA:65:55:31:08:24:EF:46:BE`

## Release notes

- Updates Android UI/UX to follow the iPhone app direction while keeping Android-native live wallpaper behaviour:
  - Centered lightweight `Live Wallpapers` header with tighter top spacing.
  - Floating rounded search/sort action pill with cleaner icon sizing.
  - Search field uses “Search name or wallpaper code”.
  - Softer horizontal chip rail for Featured, Browse, Favourites, and server categories, with lightweight category icons.
  - Two-column artwork-first wallpaper grid with improved gutters.
  - Taller rounded portrait cards with stronger bottom gradient title overlay for legibility.
  - Favourite/like control floats over each card near the artwork edge with a translucent pill style.
  - Sort options: Most liked, Newest, A–Z, Z–A.
  - Featured ranking remains likes → views → recency.
  - Favourites remain on-device and report likes with stable per-installation ID.
  - Code lookup is still available for hidden/code-only wallpapers.
- Keeps Google Play readiness fixes:
  - Release manifest targets API 36 / Android 16.
  - R8 optimization enabled.
  - Release resource shrinking enabled.
- Keeps Android-specific live wallpaper picker/set flow intact.

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
gradle clean bundleRelease lintVitalRelease
```

The project pins Gradle Java home to JDK 21 in `gradle.properties` because Android Gradle lint failed under newer JDKs.
