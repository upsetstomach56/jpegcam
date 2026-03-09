package com.github.ma1co.pmcademo.app;

import android.os.FileObserver;
import java.io.File;

public class SonyFileScanner {

    private String dcimRoot;
    private ScannerCallback mCallback;
    private FileObserver mObserver;
    private boolean isRunning = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(String path, ScannerCallback callback) {
        // Path comes in as DCIM/100MSDCF. We step up one level to DCIM to catch 101MSDCF, etc.
        File f = new File(path);
        if (f.getParent() != null) {
            this.dcimRoot = f.getParent();
        } else {
            this.dcimRoot = path;
        }
        this.mCallback = callback;
    }

    public void start() {
        if (isRunning) return;
        
        File dcimDir = new File(dcimRoot);
        final File targetFolder = getLatestSonyFolder(dcimDir);
        
        if (targetFolder == null) {
            return;
        }

        isRunning = true;

        // Hook directly into the Linux kernel to watch for closed files.
        // FileObserver.CLOSE_WRITE means the BIONZ chip is 100% finished writing to the SD card.
        mObserver = new FileObserver(targetFolder.getAbsolutePath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null) {
                    String upperPath = path.toUpperCase();
                    // Ignore files we processed ourselves to prevent infinite loops
                    if (upperPath.endsWith(".JPG") && !upperPath.startsWith("PRCS") && !upperPath.startsWith("FILM_")) {
                        
                        if (mCallback != null && mCallback.isReadyToProcess()) {
                            // Reconstruct the full absolute path
                            String fullPath = new File(targetFolder, path).getAbsolutePath();
                            mCallback.onNewPhotoDetected(fullPath);
                        }
                    }
                }
            }
        };
        
        mObserver.startWatching();
    }

    public void stop() {
        isRunning = false;
        if (mObserver != null) {
            mObserver.stopWatching();
            mObserver = null;
        }
    }

    // Helper method to find the active Sony directory (e.g., 100MSDCF vs 101MSDCF)
    private File getLatestSonyFolder(File dcim) {
        if (!dcim.exists() || !dcim.isDirectory()) return null;
        File latest = null;
        File[] files = dcim.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && f.getName().toUpperCase().endsWith("MSDCF")) {
                    if (latest == null || f.getName().compareTo(latest.getName()) > 0) {
                        latest = f;
                    }
                }
            }
        }
        return latest;
    }
}