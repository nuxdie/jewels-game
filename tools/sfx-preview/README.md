# Sound effect previews

Sound effects are notes played on the music module's own instruments, defined in `android/assets/sfx.txt`.
To hear them without a phone, build libopenmpt for your machine and render them to WAV files:

```sh
cd android/jni/libopenmpt
mkdir -p /tmp/ompt && for f in $(awk '/LOCAL_SRC_FILES \+= \\/{f=1;next} f&&/\\$/{print $1;next} f{print $1;f=0}' build/android_ndk/Android.mk | grep '\.cpp$'); do
  g++ -std=c++20 -O2 -DLIBOPENMPT_BUILD -DMPT_WITH_ZLIB -I. -Isrc -Icommon -c $f -o /tmp/ompt/$(echo $f | tr / _).o; done
gcc -O1 -I. ../../../tools/sfx-preview/render.c /tmp/ompt/*.o -lstdc++ -lz -lm -o /tmp/ompt/render
cd ../../.. && mkdir -p /tmp/sfx && /tmp/ompt/render android/assets/music/BeyondNetwork.it android/assets/sfx.txt /tmp/sfx 1600
```

The last argument is the gain in millibels; keep it equal to `GAIN_MB` in `Sfx.java`.
