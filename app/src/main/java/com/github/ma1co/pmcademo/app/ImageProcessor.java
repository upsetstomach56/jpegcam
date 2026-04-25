package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.FileWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageProcessor {
    private LutEngine mEngine;
    private Context mContext;
    private ProcessorCallback mCallback;

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String result);
    }

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mEngine = new LutEngine();
    }

    private String cleanLogValue(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void appendLogLine(File logFile, String line) throws Exception {
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileWriter writer = null;
        try {
            writer = new FileWriter(logFile, true);
            writer.write(line);
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
        mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(logFile)));
    }

    private void appendProcessingTiming(File original, File outFile, String result,
                                        long waitMs, long textureMs, long nativeMs, long javaTotalMs,
                                        long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts,
                                        int qualityIdx, int scale, int finalJpegQuality,
                                        int finalGrainSize, int finalBloom, int numCores,
                                        int finalGrainEngine,
                                        RTLProfile p, boolean applyCrop, boolean isDiptych,
                                        String nativeTiming) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        long scannerDetectMs = (scannerStartedMs > 0 && detectedMs >= scannerStartedMs) ? detectedMs - scannerStartedMs : -1;
        long detectToStableMs = (detectedMs > 0 && stableMs >= detectedMs) ? stableMs - detectedMs : -1;
        long preProcessTotalMs = (scannerStartedMs > 0 && stableMs >= scannerStartedMs) ? stableMs - scannerStartedMs : -1;
        String line = timestamp
                + "\tresult=" + cleanLogValue(result)
                + "\tfile=" + cleanLogValue(original.getName())
                + "\tinput_bytes=" + original.length()
                + "\toutput_bytes=" + (outFile.exists() ? outFile.length() : 0)
                + "\tjava_total=" + javaTotalMs
                + "\twait=" + waitMs
                + "\tscanner_detect=" + scannerDetectMs
                + "\tdetect_to_stable=" + detectToStableMs
                + "\tpre_process_total=" + preProcessTotalMs
                + "\tscanner_attempts=" + scannerAttempts
                + "\ttexture=" + textureMs
                + "\tnative=" + nativeMs
                + "\tquality_idx=" + qualityIdx
                + "\tscale=" + scale
                + "\tjpeg_q=" + finalJpegQuality
                + "\tcrop=" + applyCrop
                + "\tdiptych=" + isDiptych
                + "\tcores=" + numCores
                + "\twhite_balance=" + cleanLogValue(p.whiteBalance)
                + "\twb_shift=" + p.wbShift
                + "\twb_shift_gm=" + p.wbShiftGM
                + "\tcolor_mode=" + cleanLogValue(p.colorMode)
                + "\topacity=" + p.opacity
                + "\tgrain=" + p.grain
                + "\tgrain_size=" + finalGrainSize
                + "\tgrain_engine=" + finalGrainEngine
                + "\tvignette=" + p.vignette
                + "\trolloff=" + p.rollOff
                + "\tcolor_chrome=" + p.colorChrome
                + "\tchrome_blue=" + p.chromeBlue
                + "\tshadow_toe=" + p.shadowToe
                + "\tsubtractive_sat=" + p.subtractiveSat
                + "\thalation=" + p.halation
                + "\tbloom=" + finalBloom
                + "\t" + cleanLogValue(nativeTiming)
                + "\n";

        File[] logFiles = new File[] {
                new File(Filepaths.getAppDir(), "TIMING.TXT"),
                new File(Filepaths.getGradedDir(), "TIMING.TXT"),
                new File(Filepaths.getLogDir(), "TIMING.TXT"),
                new File(Filepaths.getLogDir(), "processing_times.txt")
        };

        for (File logFile : logFiles) {
            try {
                appendLogLine(logFile, line);
                Log.d("JPEG.CAM_TIMING", "Wrote timing log: " + logFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e("JPEG.CAM_TIMING", "Failed timing log " + logFile.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }

    public void triggerLutPreload(String lutPath, String lutName) {
        new PreloadLutTask().execute(lutPath, lutName);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p, boolean applyCrop, boolean isDiptych) {
        processJpeg(originalPath, outDirPath, qualityIndex, jpegQuality, p, applyCrop, isDiptych, 0, 0, 0, 0);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p,
                            boolean applyCrop, boolean isDiptych,
                            long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        processJpeg(originalPath, outDirPath, qualityIndex, jpegQuality, p, applyCrop, isDiptych,
                null, null, scannerStartedMs, detectedMs, stableMs, scannerAttempts);
    }

    public void processJpeg(String originalPath, String outDirPath, int qualityIndex, int jpegQuality, RTLProfile p,
                            boolean applyCrop, boolean isDiptych, String lutPath, String lutName,
                            long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
        new ProcessTask(qualityIndex, jpegQuality, p, outDirPath, applyCrop, isDiptych,
                lutPath, lutName,
                scannerStartedMs, detectedMs, stableMs, scannerAttempts).execute(originalPath);
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) {
            return mEngine.loadLut(new File(params[0]), params[1]);
        }
        @Override protected void onPostExecute(Boolean success) { mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<String, Void, String> {
        private int qualityIdx;
        private int jpegQuality;
        private RTLProfile p;
        private String outDir;
        private boolean applyCrop; // <-- NEW
        private boolean isDiptych;
        private String lutPath;
        private String lutName;
        private long scannerStartedMs;
        private long detectedMs;
        private long stableMs;
        private int scannerAttempts;

        public ProcessTask(int q, int jpegQuality, RTLProfile p, String out, boolean crop, boolean isDiptych,
                           String lutPath, String lutName,
                           long scannerStartedMs, long detectedMs, long stableMs, int scannerAttempts) {
            this.qualityIdx  = q;
            this.jpegQuality = jpegQuality;
            this.p           = p;
            this.outDir      = out;
            this.applyCrop   = crop; // <-- NEW
            this.isDiptych   = isDiptych;
            this.lutPath     = lutPath;
            this.lutName     = lutName;
            this.scannerStartedMs = scannerStartedMs;
            this.detectedMs = detectedMs;
            this.stableMs = stableMs;
            this.scannerAttempts = scannerAttempts;
        }

        @Override protected void onPreExecute() { mCallback.onProcessStarted(); }

        @Override protected String doInBackground(String... params) {
            try {
                long taskStartMs = System.currentTimeMillis();
                File original = new File(params[0]);
                if (!original.exists()) return "ERR";

                long waitStartMs = System.currentTimeMillis();
                if (stableMs <= 0) {
                    long lastSize = -1; int timeout = 0;
                    while (timeout < 50) {
                        long currentSize = original.length();
                        if (currentSize > 0 && currentSize == lastSize) break;
                        lastSize = currentSize;
                        Thread.sleep(100); timeout++;
                    }
                }
                long waitEndMs = System.currentTimeMillis();

                if (lutPath != null || lutName != null) {
                    if (!mEngine.loadLut(lutPath, lutName)) return "FAILED";
                }

                File dir = new File(outDir);
                if (!dir.exists()) dir.mkdirs();

                File outFile = new File(dir, original.getName());

                // 0=1/4 RES (4), 1=HALF RES (2), 2=FULL RES (1)
                int scale = (qualityIdx == 0) ? 4 : (qualityIdx == 2 ? 1 : 2);

                int finalJpegQuality = this.jpegQuality;
                // Still enforce safe limits for downscaled proxies to save RAM
                if (scale == 4) {
                    finalJpegQuality = Math.min(85, this.jpegQuality);
                } else if (scale == 2) {
                    finalJpegQuality = Math.min(90, this.jpegQuality);
                }

                long textureStartMs = System.currentTimeMillis();
                long textureEndMs = textureStartMs;
                
                // --- DIPTYCH COMPENSATOR ---
                // Safely steps down physical effects to account for the smaller 6MP canvas
                int finalGrainSize = p.grainSize;
                int finalBloom = p.bloom;
                
                if (isDiptych) {
                    finalGrainSize = Math.max(0, p.grainSize - 1);
                    
                    int[] bloomMap = {0, 5, 6, 1, 2, 3, 4};
                    int currentBloomIdx = 0;
                    for (int i = 0; i < bloomMap.length; i++) {
                        if (bloomMap[i] == p.bloom) currentBloomIdx = i;
                    }
                    finalBloom = bloomMap[Math.max(0, currentBloomIdx - 1)];
                }

                int cxxGrainEngine = p.advancedGrainExperimental;
                if (p.grain > 0) {
                    File texFile = MenuController.getGrainTextureFile(finalGrainSize);
                    if (mEngine.loadGrainTexture(texFile)) {
                        cxxGrainEngine = 2;
                    }
                    textureEndMs = System.currentTimeMillis();
                }

                int numCores = Runtime.getRuntime().availableProcessors();
                Log.d("JPEG.CAM", "Using " + numCores + " cores for processing.");

                long nativeStartMs = System.currentTimeMillis();
                boolean success = mEngine.applyLutToJpeg(
                    original.getAbsolutePath(), outFile.getAbsolutePath(),
                    scale, p.opacity, p.grain, finalGrainSize, p.vignette, p.rollOff,
                    p.colorChrome, p.chromeBlue, p.shadowToe, p.subtractiveSat,
                    p.halation, finalBloom, 
                    cxxGrainEngine,
                    finalJpegQuality, 
                    applyCrop, numCores);  // <--- ADDED numCores HERE
                long nativeEndMs = System.currentTimeMillis();
                String nativeTiming = mEngine.getLastNativeTiming();
                appendProcessingTiming(original, outFile, success ? "SAVED" : "FAILED",
                        waitEndMs - waitStartMs, textureEndMs - textureStartMs,
                        nativeEndMs - nativeStartMs, nativeEndMs - taskStartMs,
                        scannerStartedMs, detectedMs, stableMs, scannerAttempts,
                        qualityIdx, scale, finalJpegQuality, finalGrainSize, finalBloom,
                        numCores, cxxGrainEngine, p, applyCrop, isDiptych, nativeTiming);
                if (success) {
                    Log.d("JPEG.CAM_TIMING", "wait=" + (waitEndMs - waitStartMs)
                            + "ms texture=" + (textureEndMs - textureStartMs)
                            + "ms native=" + (nativeEndMs - nativeStartMs)
                            + "ms total=" + (nativeEndMs - taskStartMs)
                            + "ms scale=" + scale
                            + " q=" + finalJpegQuality
                            + " bloom=" + finalBloom
                            + " grain=" + p.grain
                            + " " + nativeTiming);
                    return "SAVED";
                }
            } catch (Exception e) { Log.e("COOKBOOK", "Java error: " + e.getMessage()); }
            return "FAILED";
        }

        @Override protected void onPostExecute(String result) { mCallback.onProcessFinished(result); }
    }
}
