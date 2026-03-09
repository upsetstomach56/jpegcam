package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import java.io.*;

public class ImageProcessor {
    public interface ProcessorCallback {
        void onPreloadStarted(); void onPreloadFinished(boolean success);
        void onProcessStarted(); void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onPreloadStarted(); }
        @Override protected Boolean doInBackground(String... params) { return LutEngine.loadLut(params[0]); }
        @Override protected void onPostExecute(Boolean success) { if (mCallback != null) mCallback.onPreloadFinished(success); }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath, outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String in, String out, int q, RTLProfile p) {
            this.inPath = in; this.outDir = out; this.qualityIndex = q; this.profile = p;
        }

        @Override protected void onPreExecute() { if (mCallback != null) mCallback.onProcessStarted(); }

        @Override protected String doInBackground(Void... voids) {
            Log.d("filmOS", "BYTE-STREAM RIP STARTING...");
            
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            // 8.3 Filename
            String timeTag = Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase();
            String finalOutPath = new File(dir, "FLM" + timeTag.substring(timeTag.length()-5) + ".JPG").getAbsolutePath();
            
            String fileToProcess = inPath;
            int scaleDenom = (qualityIndex == 0) ? 4 : (qualityIndex == 1) ? 2 : 1;
            boolean usedTemp = false;

            // --- MANUAL THUMBNAIL EXTRACTION (BYPASSES EXIFINTERFACE) ---
            if (qualityIndex == 0) {
                try {
                    byte[] thumb = manuallyExtractSonyThumb(inPath);
                    if (thumb != null) {
                        File temp = new File(outDir, "TEMP_RIP.JPG");
                        FileOutputStream fos = new FileOutputStream(temp);
                        fos.write(thumb); fos.close();
                        fileToProcess = temp.getAbsolutePath();
                        usedTemp = true;
                        scaleDenom = 1; // It's already the right size
                        Log.d("filmOS", "Manual Rip Success: " + thumb.length + " bytes.");
                    }
                } catch (Exception e) { Log.e("filmOS", "Manual Rip Failed: " + e.getMessage()); }
            }

            // Sony FUSE Workaround
            try { new FileOutputStream(finalOutPath).write(1); } catch (Exception e) {}

            Log.d("filmOS", "Calling Native Engine...");
            boolean success = LutEngine.processImageNative(
                    fileToProcess, finalOutPath, scaleDenom,
                    profile.opacity, profile.grain, profile.grainSize,
                    profile.vignette, profile.rollOff
            );

            if (usedTemp) new File(fileToProcess).delete();

            if (success) {
                Log.d("filmOS", "SUCCESS! File saved to: " + finalOutPath);
                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                return finalOutPath;
            }
            return null;
        }

        private byte[] manuallyExtractSonyThumb(String path) {
            try {
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path));
                // We only scan the first 128KB where the thumbnail lives
                byte[] header = new byte[131072]; 
                int read = bis.read(header);
                bis.close();

                // Find the SECOND JPEG Start (FF D8) - the first is the main image
                int soiCount = 0;
                int start = -1;
                for (int i = 0; i < read - 1; i++) {
                    if ((header[i] & 0xFF) == 0xFF && (header[i+1] & 0xFF) == 0xD8) {
                        soiCount++;
                        if (soiCount == 2) { start = i; break; }
                    }
                }

                if (start != -1) {
                    // Find the End marker (FF D9)
                    for (int i = start + 2; i < read - 1; i++) {
                        if ((header[i] & 0xFF) == 0xFF && (header[i+1] & 0xFF) == 0xD9) {
                            int end = i + 2;
                            byte[] thumb = new byte[end - start];
                            System.arraycopy(header, start, thumb, 0, thumb.length);
                            return thumb;
                        }
                    }
                }
            } catch (Exception e) { Log.e("filmOS", "Byte scan failed: " + e.getMessage()); }
            return null;
        }

        @Override protected void onPostExecute(String res) { if (mCallback != null) mCallback.onProcessFinished(res); }
    }
}