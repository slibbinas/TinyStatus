#!/usr/bin/env bash
# TinyStatus - TinyMakerWiFi busena ant riesto. Surenkama be Gradle.
#
# Gryna Java, jokiu biblioteku, todel uztenka javac + d8 + aapt2.
# (Gradle demonui sioje masinoje blokuojama loopback jungtis.)
#
# Naudojimas:  bash tools/tinystatus/build.sh

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SDK="${ANDROID_SDK:-/c/Users/SViktoras/dev-tools/android-sdk}"
# NEIMTI is JAVA_HOME: masinoje jis rodo i sistemini JRE, kuris neturi javac.
JDK="${VDIGI_JDK:-/c/Users/SViktoras/dev-tools/jdk-21.0.12.1+1}"
[ -x "$JDK/bin/javac" ] || { echo "Nerastas javac: $JDK/bin/javac" >&2; exit 1; }
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"

PKG="lt.vsid.tinystatus"
MIN_SDK=33
TARGET_SDK=36
OUT="$HERE/build"
KS="$ROOT/keystore/vdigi.keystore"

export JAVA_HOME="$JDK"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/5  aapt2 compile"
"$BT/aapt2.exe" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> 2/5  aapt2 link (+ R.java)"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code 1 --version-name 1.0.0 \
  "$OUT/res.zip"

echo "==> 3/5  javac"
# Sarasa PRIVALO sudaryti Windows keliai: argumentus Git Bash konvertuoja pats,
# bet @failo turinio - ne, ir javac gauna "\c\Users\..."
find "$HERE/src" "$OUT/gen" -name '*.java' -exec cygpath -w {} + > "$OUT/sources.txt"
if ! "$JDK/bin/javac" -nowarn -source 11 -target 11 \
     -cp "$PLATFORM" -d "$OUT/classes" "@$OUT/sources.txt" \
     > "$OUT/javac.log" 2>&1; then
  grep -v 'bootstrap class path' "$OUT/javac.log" >&2
  exit 1
fi

echo "==> 4/5  d8"
find "$OUT/classes" -name '*.class' -exec cygpath -w {} + > "$OUT/classes.txt"
"$BT/d8.bat" --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$OUT/dex" "@$OUT/classes.txt"

echo "==> 5/5  pakuoju, lygiuoju, pasirasau"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import shutil, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.copy(apk, tmp)
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
shutil.move(tmp, apk)
print("    classes.dex idetas")
PY

"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias vdigi --min-sdk-version "$MIN_SDK" \
  --out "$OUT/tinystatus.apk" "$OUT/aligned.apk"
rm -f "$OUT/base.apk" "$OUT/aligned.apk" "$OUT/res.zip" "$OUT/tinystatus.apk.idsig"

echo
echo "PARUOSTA: $OUT/tinystatus.apk  ($(stat -c%s "$OUT/tinystatus.apk") B)"
echo
echo "Idiegimas: adb -s <ip> install -r $OUT/tinystatus.apk"
