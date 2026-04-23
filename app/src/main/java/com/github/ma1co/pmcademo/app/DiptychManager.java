package com.github.ma1co.pmcademo.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

public class DiptychManager {
    private MainActivity activity;
    private DiptychOverlayView overlayView;
    private TextView tvTopStatus;

    private int state = 0; // 0: Need Shot 1, 1: Need Shot 2
    private String leftFilename = null;
    private String rightFilename = null;
    private boolean isEnabled = false;

    public DiptychManager(MainActivity activity, FrameLayout container, TextView tvTopStatus) {
        this.activity = activity;
        this.tvTopStatus = tvTopStatus;
        this.overlayView = new DiptychOverlayView(activity);
        this.overlayView.setVisibility(View.GONE);
        // ADD AT INDEX 0 to be behind HUD elements
        container.addView(this.overlayView, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) reset();
        setVisibility(enabled);
    }

    public boolean isEnabled() { return isEnabled; }

    public void setVisibility(boolean visible) {
        if (overlayView != null) overlayView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void reset() {
        state = 0;
        leftFilename = null;
        rightFilename = null;
        if (overlayView != null) overlayView.setState(0);
    }

    public int getState() { return state; }
    public void setThumbOnLeft(boolean left) { if (overlayView != null) overlayView.setThumbOnLeft(left); }
    public boolean isThumbOnLeft() { return overlayView != null && overlayView.isThumbOnLeft(); }
    public String getLeftFilename() { return leftFilename; }
    public String getRightFilename() { return rightFilename; }

    public boolean interceptNewFile(String filename, final String originalPath) {
        if (!isEnabled) return false;
        if (state == 0) {
            leftFilename = filename;
            state = 1;
            // INSTANT PREVIEW: Decode from original un-graded photo immediately
            new Thread(new Runnable() {
                public void run() {
                    final Bitmap thumb = getDiptychThumbnail(originalPath);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            if (overlayView != null) {
                                overlayView.setThumbnail(thumb);
                                overlayView.setState(1);
                            }
                            activity.updateMainHUD();
                        }
                    });
                }
            }).start();
            return true;
        } else if (state == 1) {
            rightFilename = filename;
            state = 2; // Stitching
            return true;
        }
        return false;
    }

    public void processFirstShot(final String gradedPath) {
        // Unlock shutter after grading is done
        activity.runOnUiThread(new Runnable() {
            public void run() {
                activity.setProcessing(false);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("SHOT 1 SAVED. [L/R] TO SWAP SIDE.");
                    tvTopStatus.setTextColor(Color.GREEN);
                }
                activity.updateMainHUD();
            }
        });
    }

    public void processSecondShot(final String gradedLeftPath, final String gradedRightPath) {
        state = 0;
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (overlayView != null) overlayView.setState(0);
                if (tvTopStatus != null) {
                    tvTopStatus.setText("STITCHING DIPTYCH...");
                    tvTopStatus.setTextColor(Color.YELLOW);
                }
            }
        });

        final boolean firstShotLeft = isThumbOnLeft();
        new Thread(new Runnable() {
            public void run() {
                performDiptychStitch(gradedLeftPath, gradedRightPath, firstShotLeft);
            }
        }).start();
    }

    private Bitmap getDiptychThumbnail(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) { return null; }
    }

    private void performDiptychStitch(String leftPath, String rightPath, boolean firstShotLeft) {
        try {
            System.gc();
            // 1/4 SCALE STITCH (6MP): High quality, fits safely in 24MB RAM
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 4; 

            BitmapRegionDecoder decoder1 = BitmapRegionDecoder.newInstance(leftPath, false);
            BitmapRegionDecoder decoder2 = BitmapRegionDecoder.newInstance(rightPath, false);
            if (decoder1 == null || decoder2 == null) throw new Exception("Decoders failed");

            int w1 = decoder1.getWidth(); int h1 = decoder1.getHeight();
            int w2 = decoder2.getWidth(); int h2 = decoder2.getHeight();

            int sw1 = w1 / 4; int sh1 = h1 / 4;
            int sw2 = w2 / 4; int sh2 = h2 / 4;
            int mid1 = sw1 / 2;

            int finalW = mid1 + (sw2 - (sw2 / 2));
            int finalH = Math.min(sh1, sh2);

            Bitmap composite = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(composite);

            // Left Crop of 1st shot
            Rect s1 = new Rect(0, 0, w1 / 2, h1);
            Bitmap b1 = decoder1.decodeRegion(s1, opts);
            
            // Right Crop of 2nd shot
            Rect s2 = new Rect(w2 / 2, 0, w2, h2);
            Bitmap b2 = decoder2.decodeRegion(s2, opts);

            if (b1 != null && b2 != null) {
                if (firstShotLeft) {
                    canvas.drawBitmap(b1, 0, 0, null);
                    canvas.drawBitmap(b2, mid1, 0, null);
                } else {
                    canvas.drawBitmap(b2, 0, 0, null);
                    canvas.drawBitmap(b1, mid1, 0, null);
                }
            }

            if (b1 != null) b1.recycle();
            if (b2 != null) b2.recycle();
            decoder1.recycle(); decoder2.recycle();

            Paint div = new Paint(); div.setColor(Color.BLACK); div.setStrokeWidth(4);
            canvas.drawLine(mid1, 0, mid1, finalH, div);

            File fOut = new File(Filepaths.getGradedDir(), "DIPTYCH_" + new File(rightPath).getName());
            FileOutputStream out = new FileOutputStream(fOut);
            composite.compress(Bitmap.CompressFormat.JPEG, activity.getPrefJpegQuality(), out);
            out.close();
            composite.recycle();

            new File(leftPath).delete();
            new File(rightPath).delete();

            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH SAVED");
                        tvTopStatus.setTextColor(Color.WHITE);
                    }
                    activity.updateMainHUD();
                }
            });
        } catch (Throwable e) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.setProcessing(false);
                    if (tvTopStatus != null) {
                        tvTopStatus.setText("DIPTYCH FAILED");
                        tvTopStatus.setTextColor(Color.RED);
                    }
                    activity.updateMainHUD();
                }
            });
        }
    }
}
