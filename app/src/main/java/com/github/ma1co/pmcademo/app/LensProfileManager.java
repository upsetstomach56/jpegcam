package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * filmOS UI: Lens Profile Manager
 * Handles piecewise linear interpolation for cinema focus mapping.
 */
public class LensProfileManager {
    private static final String PREF_NAME = "filmOS_LensProfiles";
    private SharedPreferences prefs;
    public float currentFocalLength = 50.0f;
    public float getCurrentFocalLength() { return currentFocalLength; }
    
    // A simple data class to hold our mapped points
    public static class CalPoint {
        public float ratio;
        public float distance;
        public CalPoint(float r, float d) {
            this.ratio = r;
            this.distance = d;
        }
    }

    private String currentLensName = "Unknown Lens";
    private List<CalPoint> currentPoints = new ArrayList<CalPoint>();

    public LensProfileManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<String> getSavedLensNames() {
        List<String> names = new ArrayList<String>();
        Map<String, ?> allEntries = prefs.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            names.add(entry.getKey());
        }
        return names;
    }

    public boolean loadProfile(String lensName) {
        currentPoints.clear();
        currentLensName = lensName;
        
        String data = prefs.getString(lensName, null);
        if (data == null) return false;
        
        // Read the focal length back into memory
        currentFocalLength = prefs.getFloat(lensName + "_focal", 50.0f);

        String[] points = data.split(";");
        for (String p : points) {
            String[] parts = p.split(",");
            if (parts.length == 2) {
                try {
                    currentPoints.add(new CalPoint(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
                } catch (Exception e) {}
            }
        }
        sortPoints();
        return !currentPoints.isEmpty();
    }

    public void saveProfile(String lensName, float focalLength, List<CalPoint> points) {
        currentFocalLength = focalLength;
        StringBuilder sb = new StringBuilder();
        for (CalPoint p : points) {
            sb.append(p.ratio).append(",").append(p.distance).append(";");
        }
        prefs.edit().putString(lensName, sb.toString()).apply();
        // Save the focal length right next to the array data!
        prefs.edit().putFloat(lensName + "_focal", focalLength).apply();
    }

    private void sortPoints() {
        Collections.sort(currentPoints, new Comparator<CalPoint>() {
            @Override
            public int compare(CalPoint p1, CalPoint p2) {
                return Float.compare(p1.ratio, p2.ratio);
            }
        });
    }

    public String getCurrentLensName() {
        return currentLensName;
    }

    public List<CalPoint> getCurrentPoints() {
        return currentPoints;
    }

    public boolean hasActiveProfile() {
        return !currentPoints.isEmpty();
    }

    /**
     * The Magic Math: Piecewise Linear Interpolation.
     * Takes the physical lens ring ratio (0.0 to 1.0) and calculates the exact distance.
     */
    public float getDistanceForRatio(float ratio) {
        if (currentPoints.isEmpty()) return -1f; // No profile loaded
        if (currentPoints.size() == 1) return currentPoints.get(0).distance;
        
        // Clamp to min/max if we exceed the calibrated bounds
        if (ratio <= currentPoints.get(0).ratio) return currentPoints.get(0).distance;
        if (ratio >= currentPoints.get(currentPoints.size() - 1).ratio) return currentPoints.get(currentPoints.size() - 1).distance;

        // Find which two points we are currently between
        for (int i = 0; i < currentPoints.size() - 1; i++) {
            CalPoint p1 = currentPoints.get(i);
            CalPoint p2 = currentPoints.get(i + 1);

            if (ratio >= p1.ratio && ratio <= p2.ratio) {
                float percentageBetween = (ratio - p1.ratio) / (p2.ratio - p1.ratio);
                return p1.distance + (percentageBetween * (p2.distance - p1.distance));
            }
        }
        return -1f;
    }
}