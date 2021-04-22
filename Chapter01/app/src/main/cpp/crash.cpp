#include <jni.h>
#include <stdio.h>

void Crash() {
    volatile int *a = (int *) (NULL);
    *a = 1;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_lang_chapter01_MainActivity_crash(JNIEnv *env, jobject obj) {
    Crash();
}