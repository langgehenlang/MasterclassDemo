package com.sample.breakpad;


import android.util.Log;

public class BreakpadInit {
    static {
        System.loadLibrary("breakpad-core");
    }

    public static void initBreakpad(String path){
        Log.d("path", path + "");
        initBreakpadNative(path);
    }

    private static native void initBreakpadNative(String path);
}
