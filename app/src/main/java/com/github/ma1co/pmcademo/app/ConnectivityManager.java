package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;

// --- NEW IMPORTS FOR HARDWARE CONTROL ---
import com.sony.scalar.sysutil.NetworkManager;
import com.github.ma1co.openmemories.framework.network.WifiDirectManager;

/**
 * JPEG.CAM Manager: Connectivity & Networking
 * Manages Wi-Fi, Hotspot, and the JPEG.CAM Dashboard server safely via Sony RTOS APIs.
 */
public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    
    // --- SONY HARDWARE MANAGERS ---
    private NetworkManager scalarNetManager;
    private WifiDirectManager hotspotManager;
    
    private HttpServer server;

    private Handler wifiPollHandler;
    private Runnable wifiPollRunnable;

    private boolean isHomeWifiRunning = false;
    private boolean isHotspotRunning = false;

    private String connStatusHotspot = "Press ENTER to Start";
    private String connStatusWifi = "Press ENTER to Start";

    public interface StatusUpdateListener {
        void onStatusUpdate(String target, String status);
    }

    private StatusUpdateListener listener;

    public ConnectivityManager(Context context, StatusUpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.connManager = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        // Initialize Sony Hardware wrappers
        this.scalarNetManager = new NetworkManager();
        this.hotspotManager = new WifiDirectManager(context.getApplicationContext());
        
        this.server = new HttpServer(context);
    }

    public String getConnStatusHotspot() { return connStatusHotspot; }
    public String getConnStatusWifi() { return connStatusWifi; }
    public boolean isHomeWifiRunning() { return isHomeWifiRunning; }
    public boolean isHotspotRunning() { return isHotspotRunning; }

    private void setAutoPowerOffMode(boolean enable) {
        String mode = enable ? "APO/NORMAL" : "APO/NO";
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        context.sendBroadcast(intent);
    }

    public void startHomeWifi() {
        stopNetworking(); 
        isHomeWifiRunning = true;
        updateStatus("WIFI", "Waking up hardware...");

        // 1. WAKE UP SONY HARDWARE FIRST to prevent the 15-second kernel panic
        try {
            scalarNetManager.enableWifi();
        } catch (Exception e) {
            // Ignore, move to android fallback if unavailable
        }

        // Give the chip 1 second to boot before hammering it with Android scans
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isHomeWifiRunning) return;
                
                updateStatus("WIFI", "Connecting to Router...");
                
                // 2. NOW USE STANDARD ANDROID WIFI
                if (!wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(true);
                } else {
                    wifiManager.reconnect();
                }

                wifiPollHandler = new Handler();
                wifiPollRunnable = new Runnable() {
                    int attempts = 0;
                    @Override
                    public void run() {
                        if (!isHomeWifiRunning) return;

                        NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                        if (info != null && info.isConnected()) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            int ip = wifiInfo.getIpAddress();
                            if (ip != 0) {
                                String ipAddress = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                                updateStatus("WIFI", "http://" + ipAddress + ":" + HttpServer.PORT);
                                startServer();
                                setAutoPowerOffMode(false); 
                                return; // Successfully connected, stop polling
                            }
                        }

                        attempts++;
                        if (attempts > 15) { // 30 seconds total
                            updateStatus("WIFI", "Timed out.");
                            stopNetworking();
                        } else {
                            updateStatus("WIFI", "Searching... (" + attempts + "/15)");
                            wifiPollHandler.postDelayed(this, 2000); // Poll again in 2 seconds
                        }
                    }
                };

                // Start the polling loop
                wifiPollHandler.postDelayed(wifiPollRunnable, 2000);
            }
        }, 1000);
    }

    public void startHotspot() {
        stopNetworking(); 
        isHotspotRunning = true;
        updateStatus("HOTSPOT", "Starting Hotspot...");

        try {
            // 1. WAKE UP SONY HARDWARE FIRST
            try {
                scalarNetManager.enableWifi();
            } catch (Exception e) {}

            // 2. USE OPENMEMORIES WRAPPER (Handles Gen 2 and Gen 3 natively)
            hotspotManager.enable();

            // Give the antenna 2 seconds to physically broadcast and assign the password
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isHotspotRunning) return;
                    
                    String pwd = hotspotManager.getPassword();
                    if (pwd == null || pwd.isEmpty()) {
                        updateStatus("HOTSPOT", "Hardware Error. Retry.");
                        stopNetworking();
                        return;
                    }
                    
                    // The standard IP for Sony Direct connections is 192.168.122.1
                    updateStatus("HOTSPOT", "PW: " + pwd + " (192.168.122.1)");
                    startServer();
                    setAutoPowerOffMode(false); 
                }
            }, 2000);

        } catch (Exception e) {
            updateStatus("HOTSPOT", "Error: " + e.getMessage());
            isHotspotRunning = false;
        }
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        
        // Stop the Wi-Fi polling handler if it's running
        if (wifiPollHandler != null && wifiPollRunnable != null) {
            wifiPollHandler.removeCallbacks(wifiPollRunnable);
            wifiPollHandler = null;
            wifiPollRunnable = null;
        }

        if (isHomeWifiRunning) {
            try { wifiManager.disconnect(); } catch (Exception e) {}
            isHomeWifiRunning = false;
        }
        
        if (isHotspotRunning) {
            try { hotspotManager.disable(); } catch (Exception e) {}
            isHotspotRunning = false;
        }
        
        // --- BATTERY SAVER: POWER OFF PHYSICAL HARDWARE ---
        try {
            scalarNetManager.disableWifi();
        } catch (Exception e) {}

        updateStatus("WIFI", "Press ENTER to Start");
        updateStatus("HOTSPOT", "Press ENTER to Start");
        setAutoPowerOffMode(true); 
    }

    private void startServer() {
        try { if (!server.isAlive()) server.start(); } catch (Exception e) {}
    }

    private void updateStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) connStatusHotspot = status;
        else connStatusWifi = status;
        if (listener != null) listener.onStatusUpdate(target, status);
    }
}