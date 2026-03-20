package com.github.ma1co.pmcademo.app;

import java.io.File;

public class LutEngine {
    static { System.loadLibrary("native-lib"); }
    private String currentLutName = "";
    
    private native boolean loadLutNative(String filePath);
    
    // --- UPDATED: Added colorChrome and chromeBlue to match C++ signature ---
    private native boolean processImageNative(String inPath, String outPath, int scaleDenom, int opacity, int grain, int grainSize, int vignette, int rollOff, int colorChrome, int chromeBlue, int shadowToe, int subtractiveSat, int halation, int jpegQuality);

    public boolean loadLut(File cubeFile, String lutName) {
        if (lutName.equals(currentLutName)) return true;
        if (loadLutNative(cubeFile.getAbsolutePath())) {
            currentLutName = lutName; return true;
        }
        return false;
    }

    // --- UPDATED: Added colorChrome and chromeBlue here as well ---
    public boolean applyLutToJpeg(String in, String out, int scale, int opacity, int grain, int grainSize, int vignette, int rollOff, int colorChrome, int chromeBlue, int shadowToe, int subtractiveSat, int halation, int quality) {
        return processImageNative(in, out, scale, opacity, grain, grainSize, vignette, rollOff, colorChrome, chromeBlue, shadowToe, subtractiveSat, halation, quality);
    }
}