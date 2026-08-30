# Depth Live Wallpaper Android Release

## Current Play upload artifact

- Version name: `0.1.7`
- Version code: `8`
- Application ID: `xyz.abhinava.depthwallpaper`
- Signed AAB: `app/build/outputs/bundle/release/app-release.aab`
- Public artifact: `https://ai.abhinava.xyz/agent/downloads/depth-wallpaper-0.1.7-v8-release.aab`
- Copied artifacts:
  - `/home/abhinava/.openclaw/workspace/managed-nginx/public/downloads/depth-wallpaper-0.1.7-v8-release.aab`
  - `/home/abhinava/.openclaw/workspace/public-downloads/depth-wallpaper-0.1.7-v8-release.aab`
- SHA-256: `5a7cbb27ed5717bfe6e8deea94fa3fac0e3d61e983abdf018dec3d754ab5d8d8`

## Release notes

- Brings Android gallery flow closer to the iOS app:
  - Featured, Browse, and Favourites sections in one screen.
  - Category rail backed by server `/wallpaper-api/pack-categories`.
  - Server-provided `category` on packs is parsed and used for filtering.
  - On-device favourites with stable per-installation like reporting.
  - Search toggle matches title/code and offers code lookup for hidden/code-only packs.
  - Featured ranks by likes, then views, then recency.
  - Display order stays frozen while liking, so cards do not jump under the user's finger.
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
