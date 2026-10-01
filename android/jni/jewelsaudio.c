// JNI bridge to libopenmpt: the game plays BeyondNetwork.it directly, music and sound effects alike.
#include <jni.h>
#include <stdint.h>
#include <libopenmpt/libopenmpt.h>

#define FN(name) Java_com_local_jewels_Tracker_##name
#define MOD(h) ((openmpt_module *)(intptr_t)(h))

JNIEXPORT jlong JNICALL FN(nOpen)(JNIEnv *env, jclass cls, jbyteArray data) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    openmpt_module *m = openmpt_module_create_from_memory2(bytes, (size_t)len, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (m) {
        openmpt_module_set_repeat_count(m, -1);
        openmpt_module_set_render_param(m, OPENMPT_MODULE_RENDER_INTERPOLATIONFILTER_LENGTH, 8);
    }
    return (jlong)(intptr_t)m;
}

JNIEXPORT jint JNICALL FN(nRead)(JNIEnv *env, jclass cls, jlong h, jint rate, jshortArray buf, jint frames) {
    jshort *p = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);
    size_t n = openmpt_module_read_interleaved_stereo(MOD(h), rate, (size_t)frames, (int16_t *)p);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, p, 0);
    return (jint)n;
}

JNIEXPORT void JNICALL FN(nSetPos)(JNIEnv *env, jclass cls, jlong h, jint order, jint row) {
    openmpt_module_set_position_order_row(MOD(h), order, row);
}

JNIEXPORT jint JNICALL FN(nOrder)(JNIEnv *env, jclass cls, jlong h) { return openmpt_module_get_current_order(MOD(h)); }
JNIEXPORT jint JNICALL FN(nPattern)(JNIEnv *env, jclass cls, jlong h) { return openmpt_module_get_current_pattern(MOD(h)); }
JNIEXPORT jint JNICALL FN(nRow)(JNIEnv *env, jclass cls, jlong h) { return openmpt_module_get_current_row(MOD(h)); }
JNIEXPORT jint JNICALL FN(nSpeed)(JNIEnv *env, jclass cls, jlong h) { return openmpt_module_get_current_speed(MOD(h)); }

JNIEXPORT void JNICALL FN(nSetFactor)(JNIEnv *env, jclass cls, jlong h, jstring key, jdouble value) {
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    openmpt_module_ctl_set_floatingpoint(MOD(h), k, value);
    (*env)->ReleaseStringUTFChars(env, key, k);
}

JNIEXPORT void JNICALL FN(nClose)(JNIEnv *env, jclass cls, jlong h) {
    if (h) openmpt_module_destroy(MOD(h));
}

// ---- sound effects: a second copy of the module, parked on an empty row, whose instruments are played as notes
#include <libopenmpt/libopenmpt_ext.h>

#define SFX(name) Java_com_local_jewels_Sfx_##name
#define EXT(h) ((openmpt_module_ext *)(intptr_t)(h))

// "Main Looper" (order 179) is empty after its first row; at the slowest speed one row lasts about 22 minutes
#define PARK_ORDER 179
#define PARK_ROW 1

static openmpt_module_ext_interface_interactive ia;
static openmpt_module_ext_interface_interactive2 ia2;

JNIEXPORT jlong JNICALL SFX(nOpen)(JNIEnv *env, jclass cls, jbyteArray data, jint gainMb) {
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    openmpt_module_ext *x = openmpt_module_ext_create_from_memory(bytes, (size_t)len, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (!x) return 0;
    if (!openmpt_module_ext_get_interface(x, LIBOPENMPT_EXT_C_INTERFACE_INTERACTIVE, &ia, sizeof ia)
        || !openmpt_module_ext_get_interface(x, LIBOPENMPT_EXT_C_INTERFACE_INTERACTIVE2, &ia2, sizeof ia2)) {
        openmpt_module_ext_destroy(x);
        return 0;
    }
    openmpt_module *m = openmpt_module_ext_get_module(x);
    openmpt_module_set_repeat_count(m, -1);
    openmpt_module_set_render_param(m, OPENMPT_MODULE_RENDER_INTERPOLATIONFILTER_LENGTH, 8);
    openmpt_module_set_render_param(m, OPENMPT_MODULE_RENDER_MASTERGAIN_MILLIBEL, gainMb);
    return (jlong)(intptr_t)x;
}

/** Back to the empty row. Seeking silences every channel, so only call this when nothing is sounding. */
JNIEXPORT void JNICALL SFX(nPark)(JNIEnv *env, jclass cls, jlong h) {
    openmpt_module_set_position_order_row(openmpt_module_ext_get_module(EXT(h)), PARK_ORDER, PARK_ROW);
    ia.set_current_speed(EXT(h), 65535);
}

JNIEXPORT jboolean JNICALL SFX(nParked)(JNIEnv *env, jclass cls, jlong h) {
    openmpt_module *m = openmpt_module_ext_get_module(EXT(h));
    return openmpt_module_get_current_order(m) == PARK_ORDER && openmpt_module_get_current_row(m) == PARK_ROW;
}

JNIEXPORT jint JNICALL SFX(nNumInstruments)(JNIEnv *env, jclass cls, jlong h) {
    return openmpt_module_get_num_instruments(openmpt_module_ext_get_module(EXT(h)));
}

JNIEXPORT jstring JNICALL SFX(nInstrumentName)(JNIEnv *env, jclass cls, jlong h, jint i) {
    const char *s = openmpt_module_get_instrument_name(openmpt_module_ext_get_module(EXT(h)), i);
    jstring r = (*env)->NewStringUTF(env, s ? s : "");
    openmpt_free_string(s);
    return r;
}

JNIEXPORT jint JNICALL SFX(nPlay)(JNIEnv *env, jclass cls, jlong h, jint ins, jint note, jdouble vol, jdouble pan) {
    return ia.play_note(EXT(h), ins, note, vol, pan);
}

JNIEXPORT void JNICALL SFX(nOff)(JNIEnv *env, jclass cls, jlong h, jint ch) {
    if (ch >= 0) ia2.note_off(EXT(h), ch);
}

JNIEXPORT jint JNICALL SFX(nRead)(JNIEnv *env, jclass cls, jlong h, jint rate, jshortArray buf, jint frames) {
    jshort *p = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);
    size_t n = openmpt_module_read_interleaved_stereo(openmpt_module_ext_get_module(EXT(h)), rate, (size_t)frames, (int16_t *)p);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, p, 0);
    return (jint)n;
}

JNIEXPORT void JNICALL SFX(nClose)(JNIEnv *env, jclass cls, jlong h) {
    if (h) openmpt_module_ext_destroy(EXT(h));
}
