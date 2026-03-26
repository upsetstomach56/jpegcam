package com.github.ma1co.pmcademo.app;

import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Filepaths {

    public static List<File> getStorageRoots() {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(Environment.getExternalStorageDirectory()); // Primary (Internal/SD0)
        roots.add(new File("/storage/sdcard1"));              // Physical SD on A7II (SD1)
        roots.add(new File("/mnt/sdcard"));
        roots.add(new File("/storage/extSdCard"));
        return roots;
    }

    public static File getAppDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM");
            if (dir.exists()) return dir;
        }
        File defaultDir = new File(Environment.getExternalStorageDirectory(), "JPEGCAM");
        if (!defaultDir.exists()) defaultDir.mkdirs();
        return defaultDir;
    }

    public static File getLutDir() {
        for (File root : getStorageRoots()) {
            File dir = new File(root, "JPEGCAM/LUTS");
            if (dir.exists()) return dir;
        }
        File defaultDir = new File(getAppDir(), "LUTS");
        if (!defaultDir.exists()) defaultDir.mkdirs();
        return defaultDir;
    }

    public static File getRecipeDir() { File d = new File(getAppDir(), "RECIPES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getLensesDir() { File d = new File(getAppDir(), "LENSES"); if (!d.exists()) d.mkdirs(); return d; }
    public static File getGradedDir() { File d = new File(getAppDir(), "GRADED"); if (!d.exists()) d.mkdirs(); return d; }

    public static File getDcimDir() {
        File sd1 = new File("/storage/sdcard1/DCIM");
        if (sd1.exists()) return sd1;
        return new File(Environment.getExternalStorageDirectory(), "DCIM");
    }

    public static void buildAppStructure() {
        getAppDir(); getLutDir(); getRecipeDir(); getLensesDir(); getGradedDir();
    }
}