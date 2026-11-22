#!/bin/sh
# Hace «voz-de-prueba.apk» (solo para las pruebas en el emulador) con las herramientas del SDK de Android que deja la
# compilación de la app: aapt2, javac, d8, zipalign y apksigner. Uso: sh pruebas/voz/hacer.sh salida/voz-de-prueba.apk
set -e
OUT=${1:-voz-de-prueba.apk}
D=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -n 1)
PL=$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -n 1)/android.jar
echo "build-tools: $BT · plataforma: $PL"
W=$(mktemp -d)
"$BT/aapt2" compile --dir "$D/res" -o "$W/res.zip"
"$BT/aapt2" link -o "$W/u.apk" -I "$PL" --manifest "$D/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version 34 "$W/res.zip"
mkdir -p "$W/c"
javac -nowarn -source 11 -target 11 -cp "$PL" -d "$W/c" $(find "$D/src" -name '*.java') 2>&1 | grep -v "bootstrap class path" || true
"$BT/d8" --lib "$PL" --min-api 24 --output "$W" $(find "$W/c" -name '*.class')
(cd "$W" && zip -q u.apk classes.dex)
"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
keytool -genkeypair -keystore "$W/k.jks" -storepass prueba -keypass prueba -alias p -keyalg RSA -keysize 2048 -dname CN=prueba -validity 3650 > /dev/null 2>&1
"$BT/apksigner" sign --ks "$W/k.jks" --ks-pass pass:prueba --key-pass pass:prueba --out "$OUT" "$W/a.apk"
ls -la "$OUT"
