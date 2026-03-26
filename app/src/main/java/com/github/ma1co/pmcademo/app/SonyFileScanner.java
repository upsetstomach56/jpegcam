package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
        if (!dcimDir.exists() || !dcimDir.isDirectory()) {
            if (triggerCallback) Log.e("JPEG.CAM", "DCIM directory not found: " + dcimDir.getAbsolutePath());
            return;
        }

        File newestFile = null;
        long maxModified = 0;

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                // UNIVERSAL FIX: Don't guess the folder name. Look inside ALL directories in DCIM.
                if (dir.isDirectory()) { 
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String name = f.getName().toUpperCase();
                            // Look for original Sony JPEGs (Ignore our FILM_ outputs and temp files)
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
                    boolean isReady = mCallback.isReadyToProcess();
                    Log.d("JPEG.CAM", "isReadyToProcess() evaluated to: " + isReady);
                    
                    if (isReady) {
                        Log.d("JPEG.CAM", "Firing onNewPhotoDetected callback to main thread!");
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mCallback.onNewPhotoDetected(currentPath);
                            }
                        });
                    } else {
                        Log.w("JPEG.CAM", "Engine blocked processing. (Either LUT is 0/OFF or processor is not initialized).");
                    }
                }
            }
        }
    }
}