#!/usr/bin/env bash
# TinyStatus - TinyMakerWiFi busena ant riesto. Surenkama be Gradle.
#
# Gryna Java + viena biblioteka (Wearable Support, komplikacijoms), todel
# uztenka javac + d8 + aapt2.
# (Gradle demonui sioje masinoje blokuojama loopback jungtis.)
#
# Naudojimas:  bash tools/tinystatus/build.sh

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE"
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

# Versija imama is git, ne rasoma ranka.
#
# versionCode PRIVALO dideti, kitaip naudotojas negales atsinaujinti:
# sistema atmeta ta pati numeri be jokio paaiskinimo. Commit'u skaicius
# tam tinka - jis niekada nemazeja ir nereikalauja nieko prisiminti.
# versionCode PRIVALO tik dideti - Android atmeta atnaujinima su mazesniu
# numeriu be jokio paaiskinimo.
#
# Iki 2026-09-10 programele gyveno `vdigi` repozitorijoje, ir numeris buvo
# TOS repozitorijos komitu skaicius; paskutine viesa laida buvo 0.1.254.
# Atskyrus koda i sia repozitorija komitu liko 23, tad be poslinkio numeris
# butu kritęs nuo 254 iki 23, ir jau idiegusieji nebegaletu atsinaujinti.
# BAZE parinkta taip, kad numeris tęstusi ten, kur buvo nutruks.
#
# 2026-09-11 +10 (254 -> 264): atsinaujinimo zondui laikrodyje buvo idiegtos
# rankiniu VERSION_CODE surinktos 291 ir 292, o komitu skaicius dar maziau.
# Be poslinkio kita laida atrodytu ne naujesne, ir "Check for updates" jos
# nesiulytu. Baze gali tik dideti.
VERSION_BASE=264
VERSION_CODE="${VERSION_CODE:-$((VERSION_BASE + $(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 1)))}"
VERSION_NAME="${VERSION_NAME:-0.1.$VERSION_CODE}"
# Parašo raktas BENDRAS visoms sio autoriaus Wear programelems ir i git
# nededamas. Keisti ji negalima: kitu raktu pasirasyto APK naudotojas
# nebegaletu idiegti virs seno - Android tokio atnaujinimo nepriima.
KS="${WEAR_KEYSTORE:-/c/Users/SViktoras/Documents/WatchFaces/keystore/vdigi.keystore}"

export JAVA_HOME="$JDK"

# Wear komplikaciju teikejui reikia senosios Wearable Support bibliotekos:
# joje yra ComplicationProviderService. Gradle nereikia - uztenka is AAR
# istraukti classes.jar. Parsisiunciam patys, kad nieko nelaikytume git'e.
# Tas pats kelias, kaip tools/valloxwatch/build.sh (2026-09-06).
LIBS="$HERE/libs"
WEARABLE="$LIBS/wearable-2.9.0.jar"
if [ ! -f "$WEARABLE" ]; then
  echo "==> 0/5  parsisiuncia Wearable Support (vienkartinis)"
  mkdir -p "$LIBS"
  AAR="$LIBS/wearable-2.9.0.aar"
  curl -sL -o "$AAR" "https://dl.google.com/dl/android/maven2/com/google/android/support/wearable/2.9.0/wearable-2.9.0.aar"
  python - "$AAR" "$WEARABLE" <<'PYX'
import shutil, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z, open(sys.argv[2], "wb") as f:
    shutil.copyfileobj(z.open("classes.jar"), f)
PYX
  rm -f "$AAR"
  echo "    $(stat -c%s "$WEARABLE") B"
fi

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
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

echo "==> 3/5  javac"
# Sarasa PRIVALO sudaryti Windows keliai: argumentus Git Bash konvertuoja pats,
# bet @failo turinio - ne, ir javac gauna "\c\Users\..."
find "$HERE/src" "$OUT/gen" -name '*.java' -exec cygpath -w {} + > "$OUT/sources.txt"
if ! "$JDK/bin/javac" -nowarn -source 11 -target 11 \
     -cp "$(cygpath -w "$PLATFORM");$(cygpath -w "$WEARABLE")" \
     -d "$OUT/classes" "@$OUT/sources.txt" \
     > "$OUT/javac.log" 2>&1; then
  grep -v 'bootstrap class path' "$OUT/javac.log" >&2
  exit 1
fi

echo "==> 4/5  d8"
find "$OUT/classes" -name '*.class' -exec cygpath -w {} + > "$OUT/classes.txt"
# Bibliotekos klases turi patekti i dex - irenginyje ju nera.
"$BT/d8.bat" --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$OUT/dex" "@$OUT/classes.txt" "$(cygpath -w "$WEARABLE")"

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
echo "Versija:  $VERSION_NAME  (code $VERSION_CODE)"
echo
echo "Idiegimas: adb -s <ip> install -r $OUT/tinystatus.apk"
