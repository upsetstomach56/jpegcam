package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.FileObserver;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;
    
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private FileObserver dcimObserver;
    
    private List<Integer> supportedIsos;
    private int curIso;
    private int curExpComp;
    private float expStep;

    enum DialMode { shutter, aperture, iso, exposure, recipe }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        scanRecipes();
        setDialMode(DialMode.shutter);
    }

    // --- LUT ENGINE START ---
    private class CubeLUT {
        int size;
        float[] data;

        CubeLUT(File file) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line;
                int idx = 0;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("LUT_3D_SIZE")) {
                        size = Integer.parseInt(line.split(" ")[1]);
                        data = new float[size * size * size * 3];
                    } else if (line.length() > 0 && Character.isDigit(line.charAt(0))) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) {
                            data[idx++] = Float.parseFloat(parts[0]);
                            data[idx++] = Float.parseFloat(parts[1]);
                            data[idx++] = Float.parseFloat(parts[2]);
                        }
                    }
                }
                br.close();
            } catch (Exception e) {}
        }

        int getColor(int r, int g, int b) {
            float rf = (r / 255.0f) * (size - 1);
            float gf = (g / 255.0f) * (size - 1);
            float bf = (b / 255.0f) * (size - 1);
            int ri = (int)rf; int gi = (int)gf; int bi = (int)bf;
            int offset = (ri + size * (gi + size * bi)) * 3;
            return Color.rgb((int)(data[offset]*255), (int)(data[offset+1]*255), (int)(data[offset+2]*255));
        }
    }
    // --- LUT ENGINE END ---

    private class BakeTask extends AsyncTask<Void, String, Boolean> {
        String fileName;
        BakeTask(String name) { this.fileName = name; }

        @Override
        protected void onPreExecute() {
            tvRecipe.setText("COOKING: " + fileName.toUpperCase());
            tvRecipe.setTextColor(Color.RED);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                File lutFile = new File("/sdcard/LUTS/" + recipeList.get(recipeIndex));
                CubeLUT lut = new CubeLUT(lutFile);
                
                File imgFile = new File("/sdcard/DCIM/100MSDCF/" + fileName);
                Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bmp == null) return false;

                Bitmap out = bmp.copy(Bitmap.Config.ARGB_8888, true);
                for (int y = 0; y < out.getHeight(); y++) {
                    for (int x = 0; x < out.getWidth(); x++) {
                        int p = out.getPixel(x, y);
                        out.setPixel(x, y, lut.getColor(Color.red(p), Color.green(p), Color.blue(p)));
                    }
                }

                File outFile = new File("/sdcard/DCIM/100MSDCF/COOKED_" + fileName);
                FileOutputStream fos = new FileOutputStream(outFile);
                out.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
                return true;
            } catch (Exception e) { return false; }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            updateRecipeDisplay();
            setDialMode(mDialMode);
        }
    }

    private void setupObserver() {
        String path = "/sdcard/DCIM/100MSDCF";
        dcimObserver = new FileObserver(path, FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, final String file) {
                if (file != null && file.toUpperCase().endsWith(".JPG") && !file.startsWith("COOKED_")) {
                    if (recipeIndex > 0) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { new BakeTask(file).execute(); }
                        });
                    }
                }
            }
        };
        dcimObserver.startWatching();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            mCameraEx.setAutoPictureReviewControl(m_autoReviewControl);
            m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
            m_autoReviewControl.setPictureReviewTime(0);

            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
            curIso = pm.getISOSensitivity();
            curExpComp = p.getExposureCompensation();
            expStep = p.getExposureCompensationStep();

            mCameraEx.setShutterSpeedChangeListener(this);
            setupObserver();
            sendSonyBroadcast(true); 
            syncUI();
        } catch (Exception e) {}
    }

    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            if (speed.first == 1 && speed.second != 1) tvShutter.setText(speed.first + "/" + speed.second);
            else tvShutter.setText(speed.first + "\"");
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            tvISO.setText(curIso == 0 ? "ISO AUTO" : "ISO " + curIso);
            tvExposure.setText(String.format("%.1f", curExpComp * expStep));
        } catch (Exception e) {}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { exitApp(); return true; }
        if (scanCode == ScalarInput.ISV_KEY_DOWN) { cycleMode(); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_CLOCKWISE) { handleInput(1); return true; }
        if (scanCode == ScalarInput.ISV_DIAL_1_COUNTERCW) { handleInput(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(int delta) {
        if (mCameraEx == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            if (mDialMode == DialMode.shutter) {
                if (delta > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
            } else if (mDialMode == DialMode.aperture) {
                if (delta > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
            } else if (mDialMode == DialMode.iso) {
                int idx = supportedIsos.indexOf(curIso);
                int next = Math.max(0, Math.min(supportedIsos.size() - 1, idx + delta));
                curIso = supportedIsos.get(next);
                pm.setISOSensitivity(curIso);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.exposure) {
                curExpComp = Math.max(p.getMinExposureCompensation(), Math.min(p.getMaxExposureCompensation(), curExpComp + delta));
                p.setExposureCompensation(curExpComp);
                mCamera.setParameters(p);
            } else if (mDialMode == DialMode.recipe) {
                recipeIndex = (recipeIndex + delta + recipeList.size()) % recipeList.size();
                updateRecipeDisplay();
            }
            syncUI();
        } catch (Exception e) {}
    }

    private void cycleMode() {
        if (mDialMode == DialMode.shutter) setDialMode(DialMode.aperture);
        else if (mDialMode == DialMode.aperture) setDialMode(DialMode.iso);
        else if (mDialMode == DialMode.iso) setDialMode(DialMode.exposure);
        else if (mDialMode == DialMode.exposure) setDialMode(DialMode.recipe);
        else setDialMode(DialMode.shutter);
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
        tvExposure.setTextColor(mode == DialMode.exposure ? Color.GREEN : Color.WHITE);
        updateRecipeDisplay();
    }

    private void scanRecipes() {
        recipeList.clear();
        recipeList.add("NONE (DEFAULT)");
        File lutDir = new File("/sdcard/LUTS");
        if (lutDir.exists()) {
            File[] files = lutDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().startsWith("_") && f.getName().toUpperCase().contains("CUB")) 
                        recipeList.add(f.getName());
                }
            }
        }
        updateRecipeDisplay();
    }

    private void updateRecipeDisplay() {
        String name = recipeList.get(recipeIndex);
        String display = name.split("\\.")[0].toUpperCase();
        tvRecipe.setText("<  " + display + "  >");
        tvRecipe.setTextColor(mDialMode == DialMode.recipe ? Color.GREEN : Color.WHITE);
    }

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    private void exitApp() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("class_name", getClass().getName());
        intent.putExtra("pullingback_key", new String[] {});
        intent.putExtra("resume_key", new String[] {});
        sendBroadcast(intent);
        finish();
    }

    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) { syncUI(); }
    @Override public void surfaceCreated(SurfaceHolder h) { try { if (mCamera != null) { mCamera.setPreviewDisplay(h); mCamera.startPreview(); syncUI(); } } catch (Exception e) {} }
    @Override protected void onPause() { super.onPause(); if (dcimObserver != null) dcimObserver.stopWatching(); if (mCameraEx != null) { m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime); mCameraEx.release(); mCameraEx = null; } }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}