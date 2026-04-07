package com.github.ma1co.pmcademo.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

import java.util.List;

/**
 * JPEG.CAM Manager: Connectivity & Networking
 * Manages Wi-Fi, Hotspot, and the JPEG.CAM Dashboard server.
 */
public class ConnectivityManager {
    private Context context;
    private WifiManager wifiManager;
    private android.net.ConnectivityManager connManager;
    private DirectManager directManager;
    private HttpServer server;

    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver directStateReceiver;
    private BroadcastReceiver groupCreateSuccessReceiver;

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
        // Standard init
        this.directManager = (DirectManager) context.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
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
        stopNetworking(); // Always clear previous attempts to prevent crashes
        
        // Safety: If no networks are saved in Sony's original settings, Android will hang
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null || configs.isEmpty()) {
            updateStatus("WIFI", "No Saved Networks Found");
            return;
        }

        isHomeWifiRunning = true;
        updateStatus("WIFI", "Connecting...");
        
        wifiReceiver = new BroadcastReceiver() {
            int timeoutCounter = 0;
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isHomeWifiRunning) return;
                String action = intent.getAction();
                
                if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                    NetworkInfo info = connManager.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                    if (info != null && info.isConnected()) {
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        int ip = wifiInfo.getIpAddress();
                        if (ip != 0) {
                            String ipAddr = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                            updateStatus("WIFI", "http://" + ipAddr + ":" + HttpServer.PORT);
                            startServer(); setAutoPowerOffMode(false); 
                        }
                    } else if (++timeoutCounter > 40) { // Safety timeout (~30-40s)
                        updateStatus("WIFI", "Timed out.");
                        stopNetworking();
                    }
                }
            }
        };
        
        context.registerReceiver(wifiReceiver, new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        wifiManager.reconnect();
    }

    public void startHotspot() {
        stopNetworking();

        // Sony-Specific: If the standard manager is null, try the proprietary service string
        if (directManager == null) {
            directManager = (DirectManager) context.getSystemService("sony:wifi:direct");
        }

        if (directManager == null) {
            updateStatus("HOTSPOT", "Hardware Busy: Retry");
            return;
        }

        isHotspotRunning = true;
        updateStatus("HOTSPOT", "Starting...");
        
        directStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN) == DirectManager.DIRECT_STATE_ENABLED) {
                    List<DirectConfiguration> configs = directManager.getConfigurations();
                    if (configs != null && !configs.isEmpty()) {
                        directManager.startGo(configs.get(configs.size() - 1).getNetworkId());
                    }
                }
            }
        };
        
        groupCreateSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                DirectConfiguration config = intent.getParcelableExtra(DirectManager.EXTRA_DIRECT_CONFIG);
                if (config != null) {
                    // Hotspot IP is almost always 192.168.122.1 on these cameras
                    updateStatus("HOTSPOT", "http://192.168.122.1:8080");
                    startServer(); setAutoPowerOffMode(false); 
                }
            }
        };
        
        context.registerReceiver(directStateReceiver, new IntentFilter(DirectManager.DIRECT_STATE_CHANGED_ACTION));
        context.registerReceiver(groupCreateSuccessReceiver, new IntentFilter(DirectManager.GROUP_CREATE_SUCCESS_ACTION));

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            // Give the radio 500ms to physically power up before calling directManager
            try { Thread.sleep(500); } catch (Exception e) {} 
        }
        directManager.setDirectEnabled(true);
    }

    public void stopNetworking() {
        if (server != null && server.isAlive()) server.stop();
        if (isHomeWifiRunning) {
            try { context.unregisterReceiver(wifiReceiver); } catch (Exception e) {}
            wifiManager.disconnect(); 
            isHomeWifiRunning = false;
        }
        if (isHotspotRunning) {
            try { context.unregisterReceiver(directStateReceiver); } catch (Exception e) {}
            try { context.unregisterReceiver(groupCreateSuccessReceiver); } catch (Exception e) {}
            try { if (directManager != null) directManager.setDirectEnabled(false); } catch (Exception e) {}
            isHotspotRunning = false;
        }
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