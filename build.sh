#!/usr/bin/env bash
# Build + (opz.) deploy dell'APK H96 HW Stream — Windows (git-bash) E Linux, stesso script.
# Uso:  ./build.sh            -> solo build
#       ./build.sh <IP-box>   -> build + install via adb di rete + autostart
set -euo pipefail
cd "$(dirname "$0")"

# ---------- OS ----------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) WINDOWS=1 ;;
  *)                    WINDOWS=0 ;;
esac
if [[ $WINDOWS -eq 1 ]]; then
  # OBBLIGATORIO su git-bash: msys converte i path /data /sdcard /sys negli argomenti adb
  export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
fi

# Path "nativo" per Gradle/local.properties: D:/... su Windows, assoluto su Linux.
native_path() {
  if [[ $WINDOWS -eq 1 ]]; then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi
}

# ---------- Android SDK ----------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  for c in ./android-sdk "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    if [[ -n "$c" && -d "$c" ]]; then SDK="$c"; break; fi
  done
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo "ERRORE: Android SDK non trovato." >&2
  echo "  Imposta ANDROID_SDK_ROOT, oppure metti/linka l'SDK in ./android-sdk" >&2
  echo "  (serve: platforms;android-34, build-tools, ndk;26.1.10909125, cmake, platform-tools)" >&2
  exit 1
fi
SDK="$(native_path "$SDK")"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"

# local.properties: generato se manca (SEMPRE forward slash, i backslash sono escape)
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=$SDK" > local.properties
  echo ">> generato local.properties (sdk.dir=$SDK)"
fi

# ---------- sorgenti nativi (libusb/libuvc, non versionati) ----------
if [[ ! -d libusb || ! -d libuvc ]]; then
  echo ">> libusb/libuvc mancanti: setup-native.sh"
  ./setup-native.sh
fi

# ---------- Gradle: dist locale in tools/ se c'e' (setup storico Windows), altrimenti wrapper ----------
if [[ $WINDOWS -eq 1 && -f tools/gradle/gradle-8.7/bin/gradle.bat ]]; then
  GRADLE=./tools/gradle/gradle-8.7/bin/gradle.bat
elif [[ -x tools/gradle/gradle-8.7/bin/gradle ]]; then
  GRADLE=./tools/gradle/gradle-8.7/bin/gradle
else
  GRADLE=./gradlew   # scarica Gradle 8.7 da solo alla prima esecuzione
fi

APK=app/build/outputs/apk/debug/app-debug.apk
echo ">> build ($GRADLE)"
"$GRADLE" :app:assembleDebug --no-daemon --console=plain
ls -lh "$APK"

# ---------- deploy opzionale ----------
if [[ $# -ge 1 ]]; then
  IP="$1"
  EXE=""; [[ $WINDOWS -eq 1 ]] && EXE=".exe"
  if [[ -f "$SDK/platform-tools/adb$EXE" ]]; then
    ADB="$SDK/platform-tools/adb$EXE"
  elif command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  else
    echo "ERRORE: adb non trovato (ne' in $SDK/platform-tools ne' nel PATH)" >&2
    exit 1
  fi
  echo ">> connect $IP"
  "$ADB" connect "$IP:5555"
  echo ">> install"
  "$ADB" -s "$IP:5555" install -r "$APK"
  # permessi persi a ogni reinstall + flush su disco: il box si spegne a corrente
  # (presa), senza sync un blackout subito dopo il deploy corrompe base.apk e l'app sparisce
  "$ADB" -s "$IP:5555" shell 'pm grant com.h96.hwstream android.permission.CAMERA; dumpsys deviceidle whitelist +com.h96.hwstream >/dev/null; sync'
  echo ">> avvio servizio"
  "$ADB" -s "$IP:5555" shell am start -n com.h96.hwstream/.MainActivity
  echo ">> fatto. UI: http://$IP:8080"
fi
