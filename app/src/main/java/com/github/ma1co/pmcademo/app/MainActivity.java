package com.github.ma1co.pmcademo.app;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.IOException;

public class MainActivity extends Activity implements SurfaceHolder.Callback, CameraEx.ShutterListener {
    private CameraEx mCameraEx;
    private SurfaceHolder mSurfaceHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SurfaceView surfaceView = new SurfaceView(this);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        setContentView(surfaceView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCameraEx = CameraEx.open(0, null);
        mCameraEx.setShutterListener(this);
        mCameraEx.startDirectShutter();
        
        // PERSISTENCE FIX: Tells the camera to return here after Playback mode
        notifyStatus(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        notifyStatus(false);
        if (mCameraEx != null) {
            mCameraEx.getNormalCamera().stopPreview();
            mCameraEx.release();
            mCameraEx = null;
        }
    }

    private void notifyStatus(boolean resume) {
        // This is the "Stay Alive" broadcast found in BetterManual/Demo apps
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("class_name", getClass().getName());
        // If resume is true, we tell the camera to keep us in the loop
        intent.putExtra("resume_key", resume ? new String[]{"on"} : new String[]{});
        sendBroadcast(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // TRASH BUTTON: Standard Exit
        if (keyCode == ScalarInput.ISV_KEY_DELETE) {
            finish();
            return true;
        }

        // MENU or CENTER BUTTON: Open Native Sony Settings
        // This allows you to change Shutter/Aperture/ISO
        if (keyCode == ScalarInput.ISV_KEY_MENU || keyCode == ScalarInput.ISV_KEY_ENTER) {
            try {
                startActivity(new Intent("com.sony.scalar.app.setting.SETTING"));
            } catch (Exception e) {
                // If native settings call fails
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            if (mCameraEx != null) {
                Camera cam = mCameraEx.getNormalCamera();
                cam.setPreviewDisplay(holder);
                cam.startPreview();
            }
        } catch (IOException e) {}
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {}
    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
    @Override
    public void surfaceDestroyed(SurfaceHolder h) {}
}