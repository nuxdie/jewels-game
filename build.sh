#!/usr/bin/env bash
# Builds Jewels.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=${JAVA_HOME:-$SDK/jdk}
export PATH=$JAVA_HOME/bin:$PATH

# libopenmpt is a git submodule; fetch it if the repo was cloned without --recursive
[ -f android/jni/libopenmpt/build/android_ndk/Android.mk ] || git submodule update --init --depth 1

rm -rf build && mkdir -p build/gen build/classes build/dex build/native

# native: libopenmpt + the JNI bridge (incremental after the first build)
NDK=${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
"$NDK/ndk-build" -j"$(nproc)" -C android/jni NDK_PROJECT_PATH=.. APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK=Application.mk >/dev/null
cp -r android/libs build/native/lib

"$BT/aapt2" compile --dir android/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" \
  --manifest android/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35 \
  -A android/assets -0 wav -0 it --java build/gen build/res.zip

javac -nowarn -Xlint:-options --release 11 -cp "$JAR" -d build/classes \
  $(find android/src build/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -0 ../unsigned.apk classes.dex)
(cd build/native && zip -qr ../unsigned.apk lib)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

KS=android/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias jewels -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Jewels (local)" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out Jewels.apk build/aligned.apk
ls -lh Jewels.apk
