package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import java.io.File;

public class LutEngine {

    static {
        System.loadLibrary("native-lib");
    }

    private String currentLutName = "";

    private native boolean loadLutNative(String filePath);
    private native boolean applyLutNative(Bitmap bitmap);

    public String getCurrentLutName() {
        return currentLutName;
    }

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName)) return true;

        if (loadLutNative(cubeFile.getAbsolutePath())) {
            currentLutName = lutName;
            return true;
        }
        return false;
    }

    public boolean applyLutToBitmap(Bitmap bitmap) {
        if (bitmap == null) return false;
        return applyLutNative(bitmap);
    }
}