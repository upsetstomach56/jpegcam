package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.*;
import java.util.ArrayList;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private TextView tvShutter, tvAperture, tvISO, tvExposure, tvRecipe;
    
    private ArrayList<String> recipeList = new ArrayList<String>();
    private int recipeIndex = 0;
    private boolean isBaking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        tvRecipe = (TextView) findViewById(R.id.tvRecipe);
        
        scanRecipes();
    }

    private class PathDetectiveTask extends AsyncTask<Void, String, String> {
        @Override protected void onPreExecute() { 
            isBaking = true; 
            tvRecipe.setText("DETECTING PATHS...");
            tvRecipe.setTextColor(Color.YELLOW);
        }

        @Override protected String doInBackground(Void... voids) {
            StringBuilder sb = new StringBuilder();
            try {
                // Try to find where the SD card is actually mounted
                File root = new File("/sdcard/DCIM");
                if (!root.exists()) root = new File("/storage/sdcard0/DCIM");
                if (!root.exists()) root = Environment.getExternalStorageDirectory();

                sb.append("ROOT: ").append(root.getAbsolutePath()).append("\n");
                
                File[] list = root.listFiles();
                if (list != null) {
                    sb.append("FOUND: ");
                    int count = 0;
                    for (File f : list) {
                        if (f.isDirectory() && count < 2) {
                            sb.append(f.getName()).append(", ");
                            count++;
                        }
                    }
                } else {
                    sb.append("LIST FILES RETURNED NULL");
                }
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
            return sb.toString();
        }

        @Override protected void onPostExecute(String result) {
            isBaking = false;
            tvRecipe.setText(result);
            tvRecipe.setTextColor(Color.CYAN);
            tvRecipe.setTextSize(12); // Shrink text to fit the long path info
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        
        // PRESS UP TO DETECT REAL PATHS
        if (scanCode == ScalarInput.ISV_KEY_UP) { 
            new PathDetectiveTask().execute(); 
            return true; 
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- STANDARD UI METHODS ---
    private void syncUI() {
        if (mCamera == null) return;
        try {
            Camera.Parameters p = mCamera.getParameters();
            CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(p);
            Pair<Integer, Integer> speed = pm.getShutterSpeed();
            tvShutter.setText(speed.first == 1 && speed.second != 1 ? speed.first + "/" + speed.second : speed.first + "\"");
            tvAperture.setText("f/" + (pm.getAperture() / 100.0f));
            int iso = pm.getISOSensitivity();
            tvISO.setText(iso == 0 ? "ISO AUTO" : "ISO " + iso);
            tvExposure.setText(String.format("%.1f", p.getExposureCompensation() * p.getExposureCompensationStep()));
        } catch (Exception e) {}
    }

    private void scanRecipes() {
        recipeList.clear(); recipeList.add("NONE");
        tvRecipe.setText("< " + recipeList.get(recipeIndex) + " >");
    }

    @Override public void surfaceCreated(SurfaceHolder h) { 
        try { 
            mCameraEx = CameraEx.open(0, null);
            mCamera = mCameraEx.getNormalCamera();
            mCameraEx.startDirectShutter();
            mCamera.setPreviewDisplay(h);
            mCamera.startPreview();
            syncUI();
        } catch (Exception e) {} 
    }
    @Override protected void onPause() { super.onPause(); if (mCameraEx != null) mCameraEx.release(); }
    @Override public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo i, CameraEx c) { syncUI(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}