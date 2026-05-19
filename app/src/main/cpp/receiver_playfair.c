#include <jni.h>
#include "playfair/playfair.h"

JNIEXPORT void JNICALL
Java_com_ambient_tvclock_receiver_airplay_PlayfairDecrypt_nativeDecrypt(
        JNIEnv *env,
        jclass clazz,
        jbyteArray message3,
        jbyteArray cipherText,
        jbyteArray keyOut) {
    jbyte *msg = (*env)->GetByteArrayElements(env, message3, NULL);
    jbyte *cipher = (*env)->GetByteArrayElements(env, cipherText, NULL);
    jbyte *out = (*env)->GetByteArrayElements(env, keyOut, NULL);
    playfair_decrypt((unsigned char *) msg, (unsigned char *) cipher, (unsigned char *) out);
    (*env)->ReleaseByteArrayElements(env, message3, msg, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, cipherText, cipher, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, keyOut, out, 0);
}
