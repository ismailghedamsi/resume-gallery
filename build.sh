#!/bin/bash
# From-scratch build: javac + aapt + d8 + zipalign + apksigner. No Gradle.
set -e
JDK=~/workspace/fossify-gallery-mod/jdk
SDK=~/workspace/fossify-gallery-mod/android-sdk
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
PASS="$(cat ~/workspace/fossify-gallery-mod/keystore.pass)"
KS=~/workspace/fossify-gallery-mod/enhanced-gallery.jks
ALIAS=enhancedgallery
W=~/workspace/enhanced-gallery
OUT=~/workspace/your_files/enhancedgallery.apk
export PATH="$JDK/bin:$PATH"

cd "$W"
rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== 1. resources + R.java"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$AJAR" -J build/gen -F build/res.zip

echo "== 2. javac"
find app/src build/gen -name '*.java' > build/sources.txt
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$AJAR" -classpath "$AJAR" \
    -d build/classes @build/sources.txt

echo "== 3. d8 dex"
(cd build/classes && "$JDK/bin/jar" cf ../classes.jar .)
"$BT/d8" --lib "$AJAR" --release --min-api 29 --output build/dex build/classes.jar

echo "== 4. apk"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$AJAR" -F build/app-unsigned.apk
cp build/dex/classes.dex build/
(cd build && "$BT/aapt" add app-unsigned.apk classes.dex)

echo "== 5. zipalign + sign"
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk
export SIGPASS="$PASS"
"$BT/apksigner" sign --ks "$KS" --ks-pass env:SIGPASS --ks-key-alias "$ALIAS" \
    --out "$OUT" build/app-aligned.apk
unset SIGPASS

echo "== 6. verify"
"$BT/apksigner" verify --print-certs "$OUT"
"$BT/aapt" dump badging "$OUT" | head -8
unzip -l "$OUT" | grep -E 'classes.dex|AndroidManifest.xml'
ls -lh "$OUT"
echo "BUILD OK: $OUT"
