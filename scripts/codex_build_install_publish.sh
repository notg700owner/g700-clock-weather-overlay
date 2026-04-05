#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

UPDATE_OWNER="${UPDATE_OWNER:-notg700owner}"
UPDATE_REPO="${UPDATE_REPO:-g700-clock-weather-overlay}"
UPDATE_BRANCH="${UPDATE_BRANCH:-main}"
UPDATE_METADATA_PATH="${UPDATE_METADATA_PATH:-update/update.json}"
PACKAGE_NAME="${PACKAGE_NAME:-com.g700.clockweather}"
APK_SOURCE="${APK_SOURCE:-app/build/outputs/apk/release/app-release.apk}"
APK_TARGET="${APK_TARGET:-update/g700-clock-weather-release.apk}"
GIT_AUTHOR_NAME_DEFAULT="${GIT_AUTHOR_NAME_DEFAULT:-G700 Release Bot}"
GIT_AUTHOR_EMAIL_DEFAULT="${GIT_AUTHOR_EMAIL_DEFAULT:-g700-release-bot@users.noreply.github.com}"

JAVA17_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [[ -z "${JAVA17_HOME}" ]]; then
  echo "Java 17 is required. Install a JDK 17 runtime or export JAVA_HOME first."
  exit 1
fi
export JAVA_HOME="${JAVA17_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -n1)"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n1)"

if [[ -z "${VERSION_CODE}" || -z "${VERSION_NAME}" ]]; then
  echo "Could not read versionCode/versionName from app/build.gradle.kts"
  exit 1
fi

mkdir -p update

./gradlew \
  -PupdateOwner="${UPDATE_OWNER}" \
  -PupdateRepo="${UPDATE_REPO}" \
  -PupdateBranch="${UPDATE_BRANCH}" \
  -PupdateMetadataPath="${UPDATE_METADATA_PATH}" \
  :app:assembleRelease

cp "${APK_SOURCE}" "${APK_TARGET}"

cat > "${UPDATE_METADATA_PATH}" <<EOF
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "https://raw.githubusercontent.com/${UPDATE_OWNER}/${UPDATE_REPO}/${UPDATE_BRANCH}/update/$(basename "${APK_TARGET}")",
  "notes": "Release ${VERSION_NAME}",
  "publishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git init -b main
fi

git config user.name "${GIT_AUTHOR_NAME_DEFAULT}"
git config user.email "${GIT_AUTHOR_EMAIL_DEFAULT}"

git add -A
if ! git diff --cached --quiet; then
  GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME_DEFAULT}" \
  GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL_DEFAULT}" \
  GIT_COMMITTER_NAME="${GIT_AUTHOR_NAME_DEFAULT}" \
  GIT_COMMITTER_EMAIL="${GIT_AUTHOR_EMAIL_DEFAULT}" \
  git commit -m "Release ${VERSION_NAME}" || true
fi

if git remote get-url origin >/dev/null 2>&1; then
  CURRENT_BRANCH="$(git symbolic-ref --short HEAD 2>/dev/null || echo main)"
  git push origin "${CURRENT_BRANCH}" || echo "Git push failed. Fix the remote/auth and rerun."
else
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if gh repo view "${UPDATE_OWNER}/${UPDATE_REPO}" >/dev/null 2>&1; then
      git remote add origin "https://github.com/${UPDATE_OWNER}/${UPDATE_REPO}.git"
    else
      gh repo create "${UPDATE_OWNER}/${UPDATE_REPO}" \
        --public \
        --source=. \
        --remote=origin \
        --push \
        --description "Single-purpose HDMI clock and weather overlay for the Jetour G700 head unit."
    fi
    CURRENT_BRANCH="$(git symbolic-ref --short HEAD 2>/dev/null || echo main)"
    git push -u origin "${CURRENT_BRANCH}"
  else
    echo "No origin remote configured yet."
    echo "Install/authenticate gh or add origin manually to publish update assets."
  fi
fi

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  RELEASE_TAG="${RELEASE_TAG:-v${VERSION_NAME}}"
  RELEASE_TITLE="${RELEASE_TITLE:-G700 Clock & Weather ${VERSION_NAME}}"
  RELEASE_NOTES="${RELEASE_NOTES:-Release ${VERSION_NAME}}"
  if gh release view "${RELEASE_TAG}" --repo "${UPDATE_OWNER}/${UPDATE_REPO}" >/dev/null 2>&1; then
    gh release upload "${RELEASE_TAG}" "${APK_TARGET}" \
      --repo "${UPDATE_OWNER}/${UPDATE_REPO}" \
      --clobber
    gh release edit "${RELEASE_TAG}" \
      --repo "${UPDATE_OWNER}/${UPDATE_REPO}" \
      --title "${RELEASE_TITLE}" \
      --notes "${RELEASE_NOTES}"
  else
    gh release create "${RELEASE_TAG}" "${APK_TARGET}" \
      --repo "${UPDATE_OWNER}/${UPDATE_REPO}" \
      --title "${RELEASE_TITLE}" \
      --notes "${RELEASE_NOTES}"
  fi
fi

if adb devices | awk 'NR>1 && $2 == "device" { found=1 } END { exit found ? 0 : 1 }'; then
  adb install -r "${APK_SOURCE}"
  adb shell pm grant "${PACKAGE_NAME}" android.permission.ACCESS_COARSE_LOCATION || true
  adb shell pm grant "${PACKAGE_NAME}" android.permission.ACCESS_FINE_LOCATION || true
  adb shell pm grant "${PACKAGE_NAME}" android.permission.ACCESS_BACKGROUND_LOCATION || true
  adb shell pm grant "${PACKAGE_NAME}" android.permission.POST_NOTIFICATIONS || true
  adb shell cmd appops set "${PACKAGE_NAME}" REQUEST_INSTALL_PACKAGES allow || true
  adb shell dumpsys deviceidle whitelist +"${PACKAGE_NAME}" || true
  adb shell am start -S -W \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -n "${PACKAGE_NAME}/.MainActivity" || true
else
  echo "No adb device is attached right now, so install/grant steps were skipped."
fi

echo
echo "Release ${VERSION_NAME} built, installed, and staged for GitHub raw updates."
