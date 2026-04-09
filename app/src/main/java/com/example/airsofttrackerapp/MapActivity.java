package com.example.airsofttrackerapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQ_LOCATION = 1001;
    private static final int PORT = 4445;

    // Map & GPS
    private GoogleMap map;
    private Marker pendingDeleteMarker = null;
    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private Location lastLocation;

    private Marker myMarker;
    private final Map<String, Marker> otherPlayers = new HashMap<>();
    private final List<Marker> customPins = new ArrayList<>();

    // Networking
    private DatagramSocket socket;
    private String sessionId;
    private String playerId;
    private String playerName;

    // UI
    private View pinPickerCard;
    private View overlayUi;
    private int selectedPinColor = Color.RED; // default
    private View sessionCard;

    private ImageButton btnEye, btnMenu, btnPlusPins, btnCenterMap;
    private ImageButton pinHouse, pinFlag, pinStar, pinSkull, pinTarget, pinPlus;

    private TextView txtSessionId;
    private View btnLeave;

    // State
    private boolean uiVisible = true;
    private boolean placingPin = false;
    private int selectedPinIconRes = 0;
    private boolean sessionMenuExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        sessionId = getIntent().getStringExtra("session");
        playerName = getIntent().getStringExtra("name");
        playerId = UUID.randomUUID().toString();

        bindViews();
        setupUI();
        enableFullscreen();

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);

        startNetworking();
        checkLocationPermission();

        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),
                    v.getPaddingRight(),
                    bottom
            );
            return insets;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (locationClient != null && locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void bindViews() {
        pinPickerCard = findViewById(R.id.pinPickerCard);
        sessionCard = findViewById(R.id.sessionCard);
        overlayUi = findViewById(R.id.overlayUi);

        btnEye = findViewById(R.id.btnEye);
        btnMenu = findViewById(R.id.btnMenu);
        btnPlusPins = findViewById(R.id.btnPlusPins);
        btnCenterMap = findViewById(R.id.btnCenterMap);

        pinHouse = findViewById(R.id.pinHouse);
        pinFlag = findViewById(R.id.pinFlag);
        pinStar = findViewById(R.id.pinStar);
        pinSkull = findViewById(R.id.pinSkull);
        pinTarget = findViewById(R.id.pinTarget);
        pinPlus = findViewById(R.id.pinPlus);

        txtSessionId = findViewById(R.id.txtSessionId);
        btnLeave = findViewById(R.id.btnLeave);

        if (sessionId == null) sessionId = "Unknown";
        txtSessionId.setText("Session ID: " + sessionId);
    }

    // =========================
    // MAP
    // =========================
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.setMapType(GoogleMap.MAP_TYPE_SATELLITE);

        map.setOnMapClickListener(latLng -> {
            if (!placingPin) return;

            placingPin = false;
            hidePinPicker();
            showPinNameDialog(latLng);
        });

        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setIndoorLevelPickerEnabled(false);

        map.setOnMarkerClickListener(marker -> {
            // Only allow delete flow for custom pins (not you / not other players)
            if (customPins.contains(marker)) {
                marker.showInfoWindow(); // user will long-press the bubble to delete
                pendingDeleteMarker = marker;
                return true; // consume click
            }
            return false; // default behavior for other markers
        });

        map.setOnInfoWindowLongClickListener(marker -> {
            if (!customPins.contains(marker)) return;

            new AlertDialog.Builder(this, R.style.GreenDialogTheme)
                    .setTitle("Delete pin?")
                    .setMessage("Remove \"" + (marker.getTitle() != null ? marker.getTitle() : "Pin") + "\"")
                    .setPositiveButton("Delete", (d, w) -> deleteCustomPin(marker))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void deleteCustomPin(Marker marker) {
        if (marker == null) return;

        // Remove from map
        marker.remove();

        // Remove from list
        customPins.remove(marker);

        if (pendingDeleteMarker == marker) pendingDeleteMarker = null;
    }

    // =========================
    // UI
    // =========================
    private void enableFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+
            getWindow().setDecorFitsSystemWindows(false);

            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            // Android 10 and below
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    private void setupUI() {

        btnEye.setOnClickListener(v -> {
            uiVisible = !uiVisible;
            applyUiVisibility(uiVisible);
            applyMarkersVisibility(uiVisible);

            if (!uiVisible) {
                placingPin = false;
                hidePinPicker();
                sessionMenuExpanded = false;
                applySessionMenuState(false);
            }
        });

        btnMenu.setOnClickListener(v -> {
            if (!uiVisible) return;
            toggleSessionMenu();
        });

        btnLeave.setOnClickListener(v -> finish());

        btnPlusPins.setOnClickListener(v -> {
            if (!uiVisible) return;
            togglePinPicker();
        });

        btnCenterMap.setOnClickListener(v -> {
            if (!uiVisible) return;
            centerMapOnMe();
        });

        pinHouse.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_house));
        pinFlag.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_flag));
        pinStar.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_star));
        pinSkull.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_skull));
        pinTarget.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_target));
        pinPlus.setOnClickListener(v -> startPlacingPin(R.drawable.ic_pin_plus));

        hidePinPicker();
        sessionMenuExpanded = false;
        applySessionMenuState(false);
    }

    private void toggleSessionMenu() {
        sessionMenuExpanded = !sessionMenuExpanded;
        applySessionMenuState(sessionMenuExpanded);
    }

    private void applySessionMenuState(boolean expanded) {
        sessionCard.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void startPlacingPin(int iconRes) {
        if (!uiVisible) return;
        selectedPinIconRes = iconRes;
        placingPin = true;
    }

    private void togglePinPicker() {
        if (pinPickerCard.getVisibility() == View.VISIBLE) hidePinPicker();
        else showPinPicker();
    }

    private void showPinPicker() {
        pinPickerCard.setVisibility(View.VISIBLE);
    }

    private void hidePinPicker() {
        pinPickerCard.setVisibility(View.GONE);
    }

    private void applyUiVisibility(boolean visible) {

        // Hide/show ALL green UI except the eye button
        if (overlayUi != null) {
            overlayUi.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        // When hiding UI, also clean up state
        if (!visible) {
            hidePinPicker();
            sessionMenuExpanded = false;
            applySessionMenuState(false);
            placingPin = false;
        }
    }

    private void applyMarkersVisibility(boolean visible) {
        if (myMarker != null) myMarker.setVisible(visible);
        for (Marker m : otherPlayers.values()) if (m != null) m.setVisible(visible);
        for (Marker m : customPins) if (m != null) m.setVisible(visible);
    }

    // =========================
    // PIN DIALOG
    // =========================
    private void showPinNameDialog(LatLng pos) {
        if (map == null) return;
        if (selectedPinIconRes == 0) return;

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        // Scroll container (safe on small screens)
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // Name input
        EditText etName = new EditText(this);
        etName.setHint("Pin name");
        root.addView(etName);

        // Spacer
        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(12 * density)));
        root.addView(spacer1);

        // Label
        TextView label = new TextView(this);
        label.setText("Pick a color");
        root.addView(label);

        // Color row container
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = Math.round(10 * density);
        row.setLayoutParams(rowLp);
        root.addView(row);

        // Colors you want (add more if you like)
        final int[] colors = new int[] {
                Color.RED,
                Color.BLUE,
                Color.GREEN,
                Color.YELLOW,
                Color.MAGENTA, // purple-ish
                Color.CYAN,
                Color.WHITE,
                Color.BLACK
        };

        // We'll store the chosen color here
        final int[] chosenColor = new int[] { selectedPinColor };

        int circleSize = Math.round(34 * density);
        int circleMargin = Math.round(8 * density);

        for (int c : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(circleSize, circleSize);
            lp.rightMargin = circleMargin;
            swatch.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(c);

            // Default border (unselected)
            bg.setStroke(Math.round(1.5f * density), Color.parseColor("#66000000"));

            swatch.setBackground(bg);

            // Selection ring
            swatch.setOnClickListener(v -> {
                chosenColor[0] = c;

                // reset all borders, then highlight selected
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    Drawable d = child.getBackground();
                    if (d instanceof GradientDrawable) {
                        ((GradientDrawable) d).setStroke(Math.round(1.5f * density), Color.parseColor("#66000000"));
                    }
                }
                ((GradientDrawable) swatch.getBackground()).setStroke(Math.round(3f * density), Color.WHITE);
            });

            row.addView(swatch);

            // Preselect previously chosen color
            if (c == selectedPinColor) {
                bg.setStroke(Math.round(3f * density), Color.WHITE);
            }
        }

        new AlertDialog.Builder(this, R.style.GreenDialogTheme)
                .setTitle("Add pin")
                .setView(scroll)
                .setPositiveButton("Add", (d, w) -> {
                    String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                    if (name.isEmpty()) name = "Pin";

                    selectedPinColor = chosenColor[0];

                    int pinSizeDp = 34; // keep it small; tweak 24–32
                    Marker m = map.addMarker(new MarkerOptions()
                            .position(pos)
                            .title(name)
                            .icon(tintedPinIcon(selectedPinIconRes, selectedPinColor, pinSizeDp))
                            .anchor(0.5f, 1.0f)
                            .flat(false) // billboard is OK
                    );

                    if (m != null) {
                        m.setVisible(uiVisible);
                        customPins.add(m);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private BitmapDescriptor getScaledDescriptorKeepAspect(int drawableRes, int targetDp) {
        Drawable d = AppCompatResources.getDrawable(this, drawableRes);
        if (d == null) return BitmapDescriptorFactory.defaultMarker();

        float density = getResources().getDisplayMetrics().density;
        int targetPx = Math.round(targetDp * density);

        int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : targetPx;
        int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : targetPx;

        float scale = Math.min((float) targetPx / iw, (float) targetPx / ih);
        int w = Math.round(iw * scale);
        int h = Math.round(ih * scale);

        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);

        return BitmapDescriptorFactory.fromBitmap(b);
    }

    private BitmapDescriptor tintedPinIcon(int drawableRes, int color, int sizeDp) {
        Drawable d = AppCompatResources.getDrawable(this, drawableRes);
        if (d == null) return BitmapDescriptorFactory.defaultMarker();

        // Wrap so tint works on all drawable types
        d = DrawableCompat.wrap(d).mutate();
        DrawableCompat.setTint(d, color);

        float density = getResources().getDisplayMetrics().density;
        int targetPx = Math.round(sizeDp * density);

        int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : targetPx;
        int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : targetPx;

        float scale = Math.min((float) targetPx / iw, (float) targetPx / ih);
        int w = Math.max(1, Math.round(iw * scale));
        int h = Math.max(1, Math.round(ih * scale));

        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);

        return BitmapDescriptorFactory.fromBitmap(b);
    }

    // =========================
    // PERMISSIONS
    // =========================
    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION);
        } else {
            startLiveTracking();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLiveTracking();
        }
    }

    // =========================
    // LIVE GPS
    // =========================
    private void startLiveTracking() {
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest request = LocationRequest.create()
                .setInterval(1000)
                .setFastestInterval(500)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null || map == null) return;

                lastLocation = loc;
                LatLng pos = new LatLng(loc.getLatitude(), loc.getLongitude());

                if (myMarker == null) {
                    myMarker = map.addMarker(new MarkerOptions()
                            .position(pos)
                            .title("Me")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f));
                } else {
                    myMarker.setPosition(pos);
                }

                if (myMarker != null) myMarker.setVisible(uiVisible);
                sendLocation(loc.getLatitude(), loc.getLongitude());
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    private void centerMapOnMe() {
        if (map == null) return;

        if (lastLocation != null) {
            LatLng me = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(me, 17f));
        } else if (myMarker != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(myMarker.getPosition(), 17f));
        } else {
            Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================
    // NETWORKING
    // =========================
    private void startNetworking() {
        new Thread(() -> {
            try {
                socket = new DatagramSocket(PORT);
                socket.setBroadcast(true);

                while (true) {
                    byte[] buffer = new byte[256];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String msg = new String(packet.getData()).trim();
                    String[] p = msg.split(",");

                    if (p.length < 5) continue;
                    if (!p[0].equals(sessionId)) continue;
                    if (p[1].equals(playerId)) continue;

                    String otherName = p[2];
                    double lat = Double.parseDouble(p[3]);
                    double lng = Double.parseDouble(p[4]);

                    runOnUiThread(() -> updateOtherPlayer(p[1], otherName, lat, lng));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void updateOtherPlayer(String id, String name, double lat, double lng) {
        if (map == null || id.equals(playerId)) return;

        LatLng pos = new LatLng(lat, lng);

        if (!otherPlayers.containsKey(id)) {
            Marker m = map.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            if (m != null) m.setVisible(uiVisible);
            otherPlayers.put(id, m);
        } else {
            Marker m = otherPlayers.get(id);
            if (m != null) {
                m.setPosition(pos);
                m.setTitle(name);
                m.setVisible(uiVisible);
            }
        }
    }

    private void sendLocation(double lat, double lng) {
        new Thread(() -> {
            try {
                String msg = sessionId + "," + playerId + "," + playerName + "," + lat + "," + lng;
                byte[] data = msg.getBytes();

                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName("255.255.255.255"),
                        PORT
                );

                if (socket != null) socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}