package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.Intent;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        new PreloadLutTask().execute(lutPath);
    }

    public void processJpeg(String inPath, String outDir, int qualityIndex, RTLProfile profile) {
        new ProcessTask(inPath, outDir, qualityIndex, profile).execute();
    }

    private class PreloadLutTask extends AsyncTask<String, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            if (mCallback != null) {
                mCallback.onPreloadStarted();
            }
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String path = params[0];
            if (path == null || path.equals("NONE")) {
                return false;
            }
            return LutEngine.loadLut(path);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (mCallback != null) {
                mCallback.onPreloadFinished(success);
            }
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
            if (mCallback != null) {
                mCallback.onProcessStarted();
            }
        }

        @Override
        protected String doInBackground(Void... voids) {
            File dir = new File(outDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String finalOutPath = new File(dir, "FILM_" + timeStamp + ".JPG").getAbsolutePath();

            String fileToProcess = inPath;
            int scaleDenom = 1;
            boolean usedThumbnail = false;

            // THUMBNAIL OPTIMIZATION: For Proxy Quality, rip the 2MP JPEG directly from EXIF
            if (qualityIndex == 0) {
                try {
                    ExifInterface exif = new ExifInterface(inPath);
                    byte[] thumb = exif.getThumbnail();
                    if (thumb != null && thumb.length > 0) {
                        File temp = new File(outDir, "temp_proxy.jpg");
                        FileOutputStream fos = new FileOutputStream(temp);
                        fos.write(thumb);
                        fos.close();
                        fileToProcess = temp.getAbsolutePath();
                        usedThumbnail = true;
                    } else {
                        scaleDenom = 4; // Fallback
                    }
                } catch (Exception e) {
                    scaleDenom = 4;
                }
            } else if (qualityIndex == 1) {
                scaleDenom = 2; // HIGH: 6MP
            } else {
                scaleDenom = 1; // ULTRA: 24MP
            }

            // Execute Native NEON Image Processing
            boolean success = LutEngine.processImageNative(
                    fileToProcess,
                    finalOutPath,
                    scaleDenom,
                    profile.opacity,
                    profile.grain,
                    profile.grainSize,
                    profile.vignette,
                    profile.rollOff
            );

            if (usedThumbnail) {
                new File(fileToProcess).delete();
            }

            if (success) {
                // EXIF RESTORATION: Copy metadata back so the Sony Playback Database accepts the file
                try {
                    ExifInterface oldExif = new ExifInterface(inPath);
                    ExifInterface newExif = new ExifInterface(finalOutPath);
                    String[] tags = {
                        ExifInterface.TAG_ORIENTATION, 
                        ExifInterface.TAG_DATETIME, 
                        ExifInterface.TAG_MAKE, 
                        ExifInterface.TAG_MODEL, 
                        "FNumber", 
                        "ExposureTime", 
                        "ISOSpeedRatings"
                    };
                    for (String t : tags) {
                        String v = oldExif.getAttribute(t);
                        if (v != null) newExif.setAttribute(t, v);
                    }
                    newExif.saveAttributes();
                } catch (Exception e) {}

                // Tell the Sony Avindex that a new graded file exists
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
            if (mCallback != null) {
                mCallback.onProcessFinished(resultPath);
            }
        }
    }
}