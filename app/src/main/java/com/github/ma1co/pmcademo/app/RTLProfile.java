package com.github.ma1co.pmcademo.app;

public class RTLProfile {
    public String profileName; 

    // Look / Base
    public int lutIndex = 0;
    public int opacity = 100;
    public int grain = 0;
    public int grainSize = 1;
    public int rollOff = 0;
    public int vignette = 0;

    // Color & Tone
    public String whiteBalance = "AUTO";
    public int wbShift = 0;
    public int wbShiftGM = 0;
    public String dro = "OFF";
    public int contrast = 0;
    public int saturation = 0;
    public int sharpness = 0;

    // PHASE 3: HIDDEN HARDWARE COLOR MATRIX (-7 to +7)
    public int colorDepthRed = 0;
    public int colorDepthGreen = 0;
    public int colorDepthBlue = 0;
    public int colorDepthCyan = 0;
    public int colorDepthMagenta = 0;
    public int colorDepthYellow = 0;

    // PHASE 3: EXPERIMENTAL OPTICS
    public String colorMode = "standard"; 
    public String pictureEffect = "off";
    public String peToyCameraTone = "normal";
    public int vignetteHardware = 0; // -16 to +16

    // PHASE 4: CHANNEL MIXER (-100 to +100)
    public int mixRedBlue = 0;   // Adds Blue into Red channel
    public int mixGreenRed = 0;  // Adds Red into Green channel
    public int mixBlueGreen = 0; // Adds Green into Blue channel
    
    // DEEP HARDWARE HACKS
    public String proColorMode = "off";
    public int softFocusLevel = 1; 
    public int shadingRed = 0;     
    public int shadingBlue = 0;    
    public int sharpnessGain = 0;  

    public RTLProfile(int slotIndex) {
        this.profileName = "RECIPE " + (slotIndex + 1);
    }
    
    public RTLProfile() {
        this.profileName = "RECIPE";
    }
}