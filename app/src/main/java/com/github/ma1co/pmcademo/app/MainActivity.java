package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener, CameraEx.ShutterSpeedChangeListener {
    private CameraEx mCameraEx;
    private SurfaceHolder mSurfaceHolder;
    private TextView tvShutter, tvAperture, tvISO, tvExposure;
    private int curIso;
    private List<Integer> supportedIsos;
    
    enum DialMode { shutter, aperture, iso, exposure }
    private DialMode mDialMode = DialMode.shutter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        
        tvShutter = (TextView) findViewById(R.id.tvShutter);
        tvAperture = (TextView) findViewById(R.id.tvAperture);
        tvISO = (TextView) findViewById(R.id.tvISO);
        tvExposure = (TextView) findViewById(R.id.tvExposure);
        
        setDialMode(DialMode.shutter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCameraEx = CameraEx.open(0, null);
        mCameraEx.setShutterListener(this);
        mCameraEx.setShutterSpeedChangeListener(this);
        mCameraEx.startDirectShutter();
        
        // Settings Sync
        CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(mCameraEx.getNormalCamera().getParameters());
        supportedIsos = (List<Integer>) pm.getSupportedISOSensitivities();
        curIso = pm.getISOSensitivity();
        
        sendSonyBroadcast(true);
        updateUI();
    }

    private void sendSonyBroadcast(boolean active) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("resume_key", active ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scanCode = event.getScanCode();
        if (scanCode == ScalarInput.ISV_KEY_DELETE) { finish(); return true; }
        
        // Cycle through Shutter -> Aperture -> ISO using the DOWN key
        if (scanCode == ScalarInput.ISV_KEY_DOWN) {
            if (mDialMode == DialMode.shutter) setDialMode(DialMode.aperture);
            else if (mDialMode == DialMode.aperture) setDialMode(DialMode.iso);
            else setDialMode(DialMode.shutter);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // BetterManual logic for the camera control dial
    protected boolean onUpperDialChanged(int value) {
        if (mDialMode == DialMode.shutter) {
            if (value > 0) mCameraEx.incrementShutterSpeed(); else mCameraEx.decrementShutterSpeed();
        } else if (mDialMode == DialMode.aperture) {
            if (value > 0) mCameraEx.incrementAperture(); else mCameraEx.decrementAperture();
        }
        return true;
    }

    private void setDialMode(DialMode mode) {
        mDialMode = mode;
        tvShutter.setTextColor(mode == DialMode.shutter ? Color.GREEN : Color.WHITE);
        tvAperture.setTextColor(mode == DialMode.aperture ? Color.GREEN : Color.WHITE);
        tvISO.setTextColor(mode == DialMode.iso ? Color.GREEN : Color.WHITE);
    }

    private void updateUI() {
        CameraEx.ParametersModifier pm = mCameraEx.createParametersModifier(mCameraEx.getNormalCamera().getParameters());
        Pair<Integer, Integer> speed = pm.getShutterSpeed();
        tvShutter.setText(speed.first + "/" + speed.second);
        tvISO.setText("ISO " + curIso);
    }

    @Override
    public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx camera) {
        tvShutter.setText(info.currentShutterSpeed_n + "/" + info.currentShutterSpeed_d);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        try { mCameraEx.getNormalCamera().setPreviewDisplay(h); mCameraEx.getNormalCamera().startPreview(); } catch (Exception e) {}
    }
    
    @Override public void onShutter(int i, CameraEx c) {}
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}