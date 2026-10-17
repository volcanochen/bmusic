#!/usr/bin/env bash
# Offline build for BMusic (no gradle, uses SDK tools directly).
set -e

# All paths below are already real Windows paths: disable MSYS path mangling.
export MSYS2_ARG_CONV_EXCL="*"
export MSYS_NO_PATHCONV=1

# pwd -W keeps a real windows path (C:/...) that every SDK tool understands
ROOT="$(cd "$(dirname "$0")" && pwd -W)"
SDK="C:/Users/volcano/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
PL="$SDK/platforms/android-34/android.jar"
JDKDIR="C:/Program Files/Android/Android Studio/jbr/bin"
JAVA="$JDKDIR/java.exe"
JAVAC="$JDKDIR/javac.exe"
KEYTOOL="$JDKDIR/keytool.exe"

OUT="$ROOT/build"
GEN="$OUT/gen"
OBJ="$OUT/obj"
BIN="$OUT/bin"
RES="$ROOT/res"
SRC="$ROOT/src"
MAN="$ROOT/AndroidManifest.xml"

rm -rf "$GEN" "$OBJ" "$BIN" "$OUT/app.apk" "$OUT/app-aligned.apk" "$OUT/bmusic.apk" "$OUT/resc"
mkdir -p "$GEN" "$OBJ" "$BIN" "$OUT/resc"

echo "== 1/7 compile resources =="
"$BT/aapt2" compile --dir "$RES" -o "$OUT/resc" -v > /dev/null
mapfile -t RESZIPS < <(find "$OUT/resc" -name "*.flat")

echo "== 2/7 link apk =="
"$BT/aapt2" link -I "$PL" --manifest "$MAN" \
  -o "$OUT/app.apk" \
  --java "$GEN" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 12 --version-name "1.0.11" \
  -A "$ROOT/assets" \
  "${RESZIPS[@]}"

echo "== 3/7 javac =="
rm -f "$OUT/sources.txt"
find "$SRC" "$GEN" -name "*.java" > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn \
  -source 8 -target 8 \
  -bootclasspath "$PL" -classpath "$PL" \
  -d "$OBJ" @"$OUT/sources.txt" 2>"$OUT/javac.log" \
  || { echo "JAVAC FAILED"; cat "$OUT/javac.log"; exit 1; }

echo "== 4/7 dex =="
rm -rf "$BIN"; mkdir -p "$BIN"
# class 文件先打成 jar 再喂 d8，避免 class 文件增多后命令行超长
rm -f "$OUT/classes.jar"
"$JDKDIR/jar.exe" cf "$OUT/classes.jar" -C "$OBJ" .
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$PL" --min-api 24 \
  --output "$BIN" "$OUT/classes.jar"

echo "== 5/7 pack dex into apk =="
if [ ! -f "$ROOT/debug.keystore" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$ROOT/debug.keystore" -alias android \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Volcano OU=Dev O=BMusic L=Xiamen S=Fujian C=CN" -noprompt
fi

PYTHON=""
for p in "C:/Users/volcano/AppData/Local/Programs/Python/Python313/python.exe" \
         "C:/Users/volcano/.workbuddy/binaries/python/versions/3.13.12/python.exe"; do
  if [ -f "$p" ]; then PYTHON="$p"; break; fi
done
"$PYTHON" - "$OUT" <<'PYEOF'
import sys, zipfile, os, glob
out = sys.argv[1]
apk = os.path.join(out, "app.apk")
dex = os.path.join(out, "bin", "classes.dex")
zf = zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED)
if "classes.dex" in zf.namelist():
    pass
zf.write(dex, "classes.dex")
zf.close()
print("classes.dex packed")
PYEOF

echo "== 6/7 zipalign =="
"$BT/zipalign" -f -p 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

echo "== 7/7 sign =="
rm -f "$OUT/bmusic.apk"
"$JAVA" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$ROOT/debug.keystore" --ks-key-alias android \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/bmusic.apk" --v1-signing-enabled true --v2-signing-enabled true \
  "$OUT/app-aligned.apk"

ls -l "$OUT/bmusic.apk"
echo "BUILD OK"
