package com.example.airsofttrackerapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TrackingService extends Service {

    public static final String ACTION_START = "com.example.airsofttrackerapp.action.START_TRACKING";
    public static final String ACTION_STOP = "com.example.airsofttrackerapp.action.STOP_TRACKING";

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PLAYER_NAME = "player_name";

    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final String PREFS_NAME = "airsoft_prefs";
    private static final String PREF_PLAYER_ID = "player_id";

    private static final String BASE_URL = "https://rpi.mapairsofttracker.org";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private OkHttpClient httpClient;

    private String sessionId;
    private String playerName;
    private String playerId;

    private boolean started = false;

    @Override
    public void onCreate() {
        super.onCreate();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();

        locationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            playerName = intent.getStringExtra(EXTRA_PLAYER_NAME);

            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = "test123";
            }

            if (playerName == null || playerName.trim().isEmpty()) {
                playerName = "Player";
            }

            playerId = getOrCreatePlayerId();

            Notification notification = buildNotification(sessionId, playerName);
            startForeground(NOTIFICATION_ID, notification);

            if (!started) {
                started = true;
                joinSessionHttp();
                startLocationTracking();
            }
        }

        return START_STICKY;
    }

    private String getOrCreatePlayerId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(PREF_PLAYER_ID, null);

        if (saved != null && !saved.trim().isEmpty()) {
            return saved;
        }

        String newId = UUID.randomUUID().toString();
        prefs.edit().putString(PREF_PLAYER_ID, newId).apply();
        return newId;
    }

    private void startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest request = LocationRequest.create()
                .setInterval(1000)
                .setFastestInterval(500)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;

                sendLocation(loc.getLatitude(), loc.getLongitude());
            }
        };

        locationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
        );
    }

    private void stopTracking() {
        if (locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        started = false;
    }

    private void joinSessionHttp() {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("player_id", playerId);
            json.put("player_name", playerName);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/session/join")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    response.close();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendLocation(double lat, double lng) {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("player_id", playerId);
            json.put("player_name", playerName);
            json.put("lat", lat);
            json.put("lng", lng);

            RequestBody body = RequestBody.create(json.toString(), JSON);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/location/update")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    response.close();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Notification buildNotification(String sessionId, String playerName) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Airsoft Tracker beží")
                .setContentText("Session: " + sessionId + " | " + playerName)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Live location tracking for Airsoft Tracker");

            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}