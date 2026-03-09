package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class ImageProcessor {

    public interface ProcessorCallback {
        void onPreloadStarted();
        void onPreloadFinished(boolean success);
        void onProcessStarted();
        void onProcessFinished(String resultPath);
    }

    private Context mContext;
    private ProcessorCallback mCallback;

    public ImageProcessor(Context context, ProcessorCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void triggerLutPreload(String lutPath, String name) {
        Log.d("filmOS", "ImageProcessor: Triggering LUT Preload for " + name);
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        Log.d("filmOS", "ImageProcessor.processJpeg called! Queuing AsyncTask...");
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            if (mCallback != null) mCallback.onPreloadStarted();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String path = params[0];
            if (path == null || path.equals("NONE")) return false;
            Log.d("filmOS", "PreloadLutTask: Loading LUT natively...");
            return LutEngine.loadLut(path);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            Log.d("filmOS", "PreloadLutTask finished. Success: " + success);
            if (mCallback != null) mCallback.onPreloadFinished(success);
        }
    }

    private class ProcessTask extends AsyncTask<Void, Void, String> {
        private String inPath;
        private String outDir;
        private int qualityIndex;
        private RTLProfile profile;

        public ProcessTask(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
            this.inPath = inPath;
            this.outDir = outDir;
            this.qualityIndex = qualityIndex;
            this.profile = profile;
        }

        @Override
        protected void onPreExecute() {
            if (mCallback != null) mCallback.onProcessStarted();
        }

        @Override
        protected String doInBackground(Void... voids) {
            Log.d("filmOS", "ProcessTask.doInBackground started.");
            
            File dir = new File(outDir);
            if (!dir.exists()) dir.mkdirs();

            // --- 8.3 FILENAME FIX ---
            // We use 'FLM' prefix + 5 chars of hex timestamp to stay under 8 chars total.
            // Example: FLM_A1B2.JPG
            String hexTime = Integer.toHexString((int) (System.currentTimeMillis() / 1000)).toUpperCase();
            String shortHex = hexTime.substring(hexTime.length() - 4); 
            String finalOutPath = new File(dir, "FLM_" + shortHex + ".JPG").getAbsolutePath();

            String fileToProcess = inPath;
            int scaleDenom = 1;
            boolean usedThumbnail = false;

            if (qualityIndex == 0) {
                try {
                    Log.d("filmOS", "Ripping EXIF thumb...");
                    ExifInterface exif = new ExifInterface(inPath);
                    byte[] thumb = exif.getThumbnail();
                    if (thumb != null) {
                        // Temp file must also follow 8.3 naming!
                        File temp = new File(outDir, "TEMP_RIP.JPG");
                        FileOutputStream fos = new FileOutputStream(temp);
                        fos.write(thumb);
                        fos.close();
                        fileToProcess = temp.getAbsolutePath();
                        usedThumbnail = true;
                    } else {
                        scaleDenom = 4;
                    }
                } catch (Exception e) {
                    scaleDenom = 4;
                }
            } else if (qualityIndex == 1) {
                scaleDenom = 2; 
            }

            // --- PRE-CREATE WORKAROUND ---
            try {
                Log.d("filmOS", "Java pre-creating: " + finalOutPath);
                new FileOutputStream(finalOutPath).close();
            } catch (Exception e) {
                Log.e("filmOS", "Pre-create fail: " + e.getMessage());
            }

            Log.d("filmOS", "Calling Native Engine...");
            boolean success = LutEngine.processImageNative(
                    fileToProcess, finalOutPath, scaleDenom,
                    profile.opacity, profile.grain, profile.grainSize,
                    profile.vignette, profile.rollOff
            );
            
            Log.d("filmOS", "Native Engine returned: " + success);

            if (usedThumbnail) new File(fileToProcess).delete();

            if (success) {
                try {
                    ExifInterface oldExif = new ExifInterface(inPath);
                    ExifInterface newExif = new ExifInterface(finalOutPath);
                    String[] tags = { "FNumber", "ExposureTime", "ISOSpeedRatings", "FocalLength", "Orientation", "DateTime" };
                    for (String t : tags) {
                        String v = oldExif.getAttribute(t);
                        if (v != null) newExif.setAttribute(t, v);
                    }
                    newExif.saveAttributes();
                } catch (Exception e) {}

                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(new File(finalOutPath))));
                return finalOutPath;
            } else {
                File f = new File(finalOutPath);
                if (f.exists()) f.delete();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultPath) {
            Log.d("filmOS", "ProcessTask complete. Result: " + resultPath);
            if (mCallback != null) mCallback.onProcessFinished(resultPath);
        }
    }
}