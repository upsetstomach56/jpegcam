package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    private String lastSeenFilePath = "";
    
    private HandlerThread scannerThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    private boolean isPolling = false;

    public interface ScannerCallback {
        void onNewPhotoDetected(String filePath);
        boolean isReadyToProcess(); 
    }

    public SonyFileScanner(ScannerCallback callback) {
        this.mCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper()); 
        
        Log.d("JPEG.CAM", "SonyFileScanner initialized. Root: " + Filepaths.getDcimDir().getAbsolutePath());
        
        findNewestFile(false); 
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        start();
    }

    public void start() {
        if (!isPolling) {
            isPolling = true;
            scheduleNextPoll();
        }
    }

    public void stop() {
        isPolling = false;
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null);
        }
    }

    public void checkNow() {
        if (backgroundHandler != null) {
            backgroundHandler.post(new Runnable() {
                @Override public void run() { findNewestFile(true); }
            });
        }
    }

    private void scheduleNextPoll() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPolling) {
                    findNewestFile(true);
                    scheduleNextPoll(); 
                }
            }
        }, 1000);
    }

    private void findNewestFile(boolean triggerCallback) {
        File dcimDir = Filepaths.getDcimDir(); 
        if (!dcimDir.exists() || !dcimDir.isDirectory()) return;

        File newestFile = null;
        long maxModified = 0;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                String dirName = dir.getName().toUpperCase();
                // STRICT CHECK: Only look inside valid photo folders (ignores corrupt thumbnails!)
                if (dir.isDirectory() && (dirName.endsWith("MSDCF") || dirName.contains("ALPHA") || dirName.contains("SONY"))) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("TEMP_")) {
                                if (f.lastModified() > maxModified) {
                                    maxModified = f.lastModified();
                                    newestFile = f;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (newestFile != null) {
            final String currentPath = newestFile.getAbsolutePath();
            if (!currentPath.equals(lastSeenFilePath)) {
                Log.d("JPEG.CAM", "NEW FILE DETECTED: " + currentPath);
                lastSeenFilePath = currentPath;
                
                if (triggerCallback && mCallback != null) {
                    if (mCallback.isReadyToProcess()) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { mCallback.onNewPhotoDetected(currentPath); }
                        });
                    } else {
                        Log.w("JPEG.CAM", "Engine blocked processing. (LUT is 0/OFF or processor not initialized).");
                    }
                }
            }
        }
    }
}