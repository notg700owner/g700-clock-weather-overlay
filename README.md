# G700 Clock & Weather

G700 Clock & Weather is a stripped-down Android Automotive utility for the Jetour G700 head unit. The app now does one job: keep a clock and optional weather overlay on the secondary HDMI-style display, with protected boot auto-start, a hidden calibration page, and GitHub-backed self-update checks.

Package ID: `com.g700.clockweather`

## What this build includes

- A simplified car-style control screen with one toggle for the clock and one toggle for the weather.
- A basic, lightweight settings UI with no overlay preview and no copied vehicle-dashboard elements.
- A wider landscape layout that uses the full head-unit screen more cleanly and keeps the app's own top chrome out of the way.
- An optional `Internet weather` switch under the weather card. When it is off, the app uses the car API outside temperature. When it is on and connectivity is available, the app uses online weather and falls back to the car temperature automatically.
- The local vehicle temperature path prefers `carManager.getEXTERNALTEMPERATURE_C()` and subscribes to `onEXTERNALTEMPERATURE_C(float)` updates when that callback surface exists on the head unit.
- Internet reachability is checked with a direct connection attempt instead of depending on network-state APIs, which avoids the permission issue seen on some head units.
- GitHub update checks and APK downloads are forced uncached, so the in-app updater sees newly pushed releases instead of stale raw-content responses.
- If the vehicle temperature API fails, the app now surfaces the manager/exception details in the runtime status instead of only showing a generic missing-value message.
- A hidden calibration page opened from `Calibrate`, with `-100`, `-10`, `-1`, `+1`, `+10`, and `+100` nudges for clock and weather X/Y offsets.
- A protected foreground service that auto-starts after boot, waits through a settle delay, and blocks future auto-starts after repeated unhealthy launches.
- A secondary-display overlay using the Android `Presentation` API, so it targets HDMI / display 2 without `SYSTEM_ALERT_WINDOW`.
- Weather lookup from the current device location using Open-Meteo.
- Update checks on launch and on demand, reading a simple JSON manifest from GitHub raw content and downloading the linked APK.

## Permissions requested on launch

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
  Requested only when `Internet weather` is enabled, so the app can resolve online weather for the current position.
- `ACCESS_BACKGROUND_LOCATION`
  Requested only when `Internet weather` is enabled and background refresh is allowed after boot.
- `POST_NOTIFICATIONS`
  Required for the foreground service notification on Android 13+.
- Battery optimization exemption
  Requested so the boot/runtime service is less likely to be killed.

The app also declares `REQUEST_INSTALL_PACKAGES` so it can hand off a downloaded APK to the Android package installer when an update is available, and `android.car.permission.CAR_EXTERIOR_ENVIRONMENT` so it can read the vehicle outside temperature through the car property API.

## Update feed layout

The in-app updater expects a public GitHub repo with these files:

- `update/update.json`
- `update/g700-clock-weather-release.apk`

Default repo wiring in this project points to:

- owner: `notg700owner`
- repo: `g700-clock-weather-overlay`
- branch: `main`

You can override those values at build time with Gradle properties:

```bash
./gradlew \
  -PupdateOwner=YOUR_USER \
  -PupdateRepo=YOUR_REPO \
  -PupdateBranch=main \
  -PupdateMetadataPath=update/update.json \
  :app:assembleRelease
```

## Local build

The project now includes a Gradle wrapper. Java 17 is recommended.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## One-shot build, install, and publish helper

Use [`scripts/codex_build_install_publish.sh`](scripts/codex_build_install_publish.sh) to:

1. build a release APK
2. copy the APK into `update/`
3. rewrite `update/update.json`
4. initialize a local git repo if needed
5. create the GitHub repo automatically when `gh` is installed and authenticated
6. commit and push the update feed
7. create or refresh the matching GitHub Release and attach the APK asset
8. install the APK over `adb` when a device is attached
9. grant the runtime permissions and best-effort battery/install app-ops

The script also forces a generic release-bot git author so future release commits do not inherit your machine-level git identity.

Example:

```bash
chmod +x scripts/codex_build_install_publish.sh
./scripts/codex_build_install_publish.sh
```

If you want a different GitHub repo for the update feed:

```bash
UPDATE_OWNER=YOUR_USER UPDATE_REPO=YOUR_REPO ./scripts/codex_build_install_publish.sh
```

## GitHub publishing note

This workspace was copied in without a `.git` directory, so the script will initialize a local repo automatically. If no `origin` remote exists yet, create the GitHub repo first and then add it:

```bash
git remote add origin git@github.com:YOUR_USER/YOUR_REPO.git
git push -u origin main
```

## Validation

- `gradle :app:assembleDebug`

The debug build compiles successfully in this workspace. The release path depends on your local signing inputs from `keystore.properties` and the private keystore file, which are intentionally ignored by `.gitignore`.
