package com.github.ma1co.pmcademo.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.HashSet;

public class SonyFileScanner {
    private ScannerCallback mCallback;
    
    // The Delta Tracker: Remembers every file that existed when the app booted
    private HashSet<String> knownFiles = new HashSet<String>();
    
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
        
        scannerThread = new HandlerThread("FileScannerThread");
        scannerThread.start();
        backgroundHandler = new Handler(scannerThread.getLooper());
        
        // Build the baseline index in the background so it doesn't slow down the app boot
        backgroundHandler.post(new Runnable() {
            @Override public void run() { 
                findNewestFile(false); 
                start(); // Only start polling AFTER the baseline is built
            }
        });
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

        File[] subDirs = dcimDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                // BUG FIX: Don't filter by name. If it's a directory in DCIM, it's a candidate.
                if (dir.isDirectory() && !dir.getName().startsWith(".")) { 
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            // Use Locale.US to ensure ".JPG" is always interpreted correctly
                            String name = f.getName().toUpperCase(java.util.Locale.US);
                            
                            if (name.endsWith(".JPG") && !name.startsWith("FILM_") && !name.startsWith("PRCS") && !name.startsWith("TEMP_")) {
                                
                                String currentFilePath = f.getAbsolutePath();
                                
                                if (!knownFiles.contains(currentFilePath)) {
                                    
                                    // SAFETY CHECK: Ensure the file is actually finished being written
                                    // If a 24MP file is 0kb, the camera is still writing it!
                                    if (f.length() < 1024) continue; 

                                    knownFiles.add(currentFilePath);
                                    
                                    if (triggerCallback) {
                                        Log.d("JPEG.CAM", "NEW FILE DETECTED: " + currentFilePath);
                                        
                                        // DEBUG TOAST 1: Confirm the scanner actually saw the file
                                        final String fileName = name; 
                                        mainHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                // Using a long toast so they have time to read it
                                                android.widget.Toast.makeText(mainHandler.getLooper().getThread().getContext(), 
                                                    "SCANNER SEEN: " + fileName, android.widget.Toast.LENGTH_SHORT).show();
                                            }
                                        });

                                        if (mCallback != null) {
                                            if (mCallback.isReadyToProcess()) {
                                                final String finalPathToProcess = currentFilePath; 
                                                mainHandler.post(new Runnable() {
                                                    @Override public void run() { 
                                                        // DEBUG TOAST 2: Confirm the engine is actually starting
                                                        android.widget.Toast.makeText(mainHandler.getLooper().getThread().getContext(), 
                                                            "ENGINE STARTING...", android.widget.Toast.LENGTH_SHORT).show();
                                                        mCallback.onNewPhotoDetected(finalPathToProcess); 
                                                    }
                                                });
                                            } else {
                                                // DEBUG TOAST 3: This is the most important one for your Asian tester!
                                                mainHandler.post(new Runnable() {
                                                    @Override public void run() {
                                                        android.widget.Toast.makeText(mainHandler.getLooper().getThread().getContext(), 
                                                            "BLOCKED: Engine not ready or Recipe OFF", android.widget.Toast.LENGTH_LONG).show();
                                                    }
                                                });
                                                Log.w("JPEG.CAM", "Engine blocked processing for: " + name);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}