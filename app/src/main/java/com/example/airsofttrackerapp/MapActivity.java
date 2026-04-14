package com.example.airsofttrackerapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
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
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int REQ_LOCATION = 1001;
    private static final int REQ_BACKGROUND_LOCATION = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private static final String PREFS_NAME = "airsoft_prefs";
    private static final String PREF_PLAYER_ID = "player_id";

    private static final String BASE_URL = "https://rpi.mapairsofttracker.org";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private static final int COLOR_ELIMINATED = Color.parseColor("#D62828");
    private static final int COLOR_DEFAULT_CHIP = Color.parseColor("#354e46");

    private static final int CUSTOM_PIN_SIZE_DP = 34;

    private GoogleMap map;
    private Marker pendingDeleteMarker = null;
    private FusedLocationProviderClient locationClient;
    private Location lastLocation;

    private Marker myMarker;
    private final Map<String, Marker> otherPlayers = new HashMap<>();

    // server-synced custom pins
    private final Map<String, Marker> customPins = new HashMap<>();
    private final Map<Marker, String> customPinIdsByMarker = new HashMap<>();

    private OkHttpClient httpClient;
    private Handler pollHandler;
    private String sessionId;
    private String playerId;
    private String playerName;

    private View pinPickerCard;
    private View overlayUi;
    private int selectedPinColor = Color.RED;
    private View sessionCard;

    private MaterialCardView eyeChip;
    private MaterialCardView eliminatedChip;

    private ImageButton btnEye, btnMenu, btnPlusPins, btnCenterMap, btnEliminated;
    private ImageButton pinHouse, pinFlag, pinStar, pinSkull, pinTarget, pinPlus;

    private TextView txtSessionId;
    private View btnLeave;

    private boolean uiVisible = true;
    private boolean placingPin = false;
    private int selectedPinIconRes = 0;
    private String selectedPinIconKey = null;
    private boolean sessionMenuExpanded = false;
    private boolean isEliminated = false;
    private boolean serverConnectionLost = false;

    private ImageButton selectedPinButton = null;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            fetchMyPlayerState();
            fetchOtherPlayers();
            fetchCustomPins();

            if (pollHandler != null) {
                pollHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        sessionId = getIntent().getStringExtra("session");
        playerName = getIntent().getStringExtra("name");

        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = "test123";
        }

        if (playerName == null || playerName.trim().isEmpty()) {
            playerName = "Player";
        }

        playerId = getOrCreatePlayerId();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();

        pollHandler = new Handler(getMainLooper());
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        bindViews();
        applyEliminatedButtonState();
        applyEyeButtonState();
        setupUI();
        enableFullscreen();

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        checkPermissionsAndStartTracking();

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
    protected void onResume() {
        super.onResume();
        fetchMyPlayerState();
        enableFullscreen();
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
            pollHandler.post(pollRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    protected void onDestroy() {
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
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

    private void bindViews() {
        pinPickerCard = findViewById(R.id.pinPickerCard);
        sessionCard = findViewById(R.id.sessionCard);
        overlayUi = findViewById(R.id.overlayUi);

        eyeChip = findViewById(R.id.eyeChip);
        eliminatedChip = findViewById(R.id.eliminatedChip);

        btnEye = findViewById(R.id.btnEye);
        btnMenu = findViewById(R.id.btnMenu);
        btnPlusPins = findViewById(R.id.btnPlusPins);
        btnCenterMap = findViewById(R.id.btnCenterMap);
        btnEliminated = findViewById(R.id.btnEliminated);

        pinHouse = findViewById(R.id.pinHouse);
        pinFlag = findViewById(R.id.pinFlag);
        pinStar = findViewById(R.id.pinStar);
        pinSkull = findViewById(R.id.pinSkull);
        pinTarget = findViewById(R.id.pinTarget);
        pinPlus = findViewById(R.id.pinPlus);

        txtSessionId = findViewById(R.id.txtSessionId);
        btnLeave = findViewById(R.id.btnLeave);

        txtSessionId.setText("Session ID: " + sessionId);
    }

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
            if (customPinIdsByMarker.containsKey(marker)) {
                marker.showInfoWindow();
                pendingDeleteMarker = marker;
                return true;
            }
            return false;
        });

        map.setOnInfoWindowLongClickListener(marker -> {
            if (!customPinIdsByMarker.containsKey(marker)) return;

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

        String pinId = customPinIdsByMarker.get(marker);
        if (pinId == null || pinId.trim().isEmpty()) return;

        deleteCustomPinFromServer(pinId);
    }

    private void enableFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);

            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(
                        WindowInsets.Type.statusBars()
                                | WindowInsets.Type.navigationBars()
                );

                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }

        } else {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void setupUI() {
        btnEye.setOnClickListener(v -> {
            uiVisible = !uiVisible;
            applyEyeButtonState();
            applyUiVisibility(uiVisible);
            applyMarkersVisibility(uiVisible);

            if (!uiVisible) {
                hidePinPicker();
                sessionMenuExpanded = false;
                applySessionMenuState(false);
                resetSelectedPinState();
            }
        });

        btnMenu.setOnClickListener(v -> {
            if (!uiVisible) return;
            toggleSessionMenu();
        });

        btnLeave.setOnClickListener(v -> {
            stopTrackingService();
            finish();
        });

        btnPlusPins.setOnClickListener(v -> {
            if (!uiVisible) return;
            togglePinPicker();
        });

        btnCenterMap.setOnClickListener(v -> {
            if (!uiVisible) return;
            centerMapOnMe();
        });

        btnEliminated.setOnClickListener(v -> {
            isEliminated = !isEliminated;
            applyEliminatedButtonState();
            updateMyMarkerAppearance();
            sendEliminatedStateToServer(isEliminated);
        });

        pinHouse.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_house, "house"));
        pinFlag.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_flag, "flag"));
        pinStar.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_star, "star"));
        pinSkull.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_skull, "skull"));
        pinTarget.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_target, "target"));
        pinPlus.setOnClickListener(v -> startPlacingPin((ImageButton) v, R.drawable.ic_pin_plus, "plus"));

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

    private void startPlacingPin(ImageButton button, int iconRes, String iconKey) {
        if (!uiVisible) return;

        selectedPinIconRes = iconRes;
        selectedPinIconKey = iconKey;
        placingPin = true;

        clearPinSelectionHighlight();
        selectedPinButton = button;
        applyPinSelectionHighlight(selectedPinButton);
    }

    private void applyPinSelectionHighlight(ImageButton button) {
        if (button == null) return;

        button.setBackgroundResource(R.drawable.pin_picker_selected_bg);
        button.animate()
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(120)
                .start();
    }

    private void clearPinSelectionHighlight() {
        if (selectedPinButton != null) {
            selectedPinButton.setBackgroundResource(R.drawable.pin_picker_default_bg);
            selectedPinButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start();
            selectedPinButton = null;
        }
    }

    private void resetSelectedPinState() {
        clearPinSelectionHighlight();
        selectedPinIconKey = null;
        selectedPinIconRes = 0;
        placingPin = false;
    }

    private void togglePinPicker() {
        if (pinPickerCard.getVisibility() == View.VISIBLE) {
            hidePinPicker();
        } else {
            showPinPicker();
        }
    }

    private void showPinPicker() {
        pinPickerCard.setVisibility(View.VISIBLE);
    }

    private void hidePinPicker() {
        pinPickerCard.setVisibility(View.GONE);
    }

    private void applyUiVisibility(boolean visible) {
        if (overlayUi != null) {
            overlayUi.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        if (!visible) {
            hidePinPicker();
            sessionMenuExpanded = false;
            applySessionMenuState(false);
            resetSelectedPinState();
        }
    }

    private void applyMarkersVisibility(boolean visible) {
        if (myMarker != null) myMarker.setVisible(visible);

        for (Marker m : otherPlayers.values()) {
            if (m != null) m.setVisible(visible);
        }

        for (Marker m : customPins.values()) {
            if (m != null) m.setVisible(visible);
        }
    }

    private void applyEliminatedButtonState() {
        if (eliminatedChip == null) return;
        eliminatedChip.setCardBackgroundColor(isEliminated ? COLOR_ELIMINATED : COLOR_DEFAULT_CHIP);
    }

    private void applyEyeButtonState() {
        if (eyeChip == null) return;
        eyeChip.setCardBackgroundColor(uiVisible ? COLOR_DEFAULT_CHIP : COLOR_ELIMINATED);
    }

    private BitmapDescriptor getMyMarkerIcon() {
        return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE);
    }

    private BitmapDescriptor getOtherPlayerIcon(boolean eliminated) {
        if (eliminated) {
            return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED);
        }
        return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN);
    }

    private void updateMyMarkerAppearance() {
        if (myMarker != null) {
            myMarker.setIcon(getMyMarkerIcon());
            myMarker.setAlpha(isEliminated ? 0.75f : 1f);
        }
    }

    private void showPinNameDialog(LatLng pos) {
        if (map == null) return;
        if (selectedPinIconRes == 0) return;

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        EditText etName = new EditText(this);
        etName.setHint("Pin name");
        root.addView(etName);

        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(12 * density)
        ));
        root.addView(spacer1);

        TextView label = new TextView(this);
        label.setText("Pick a color");
        root.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = Math.round(10 * density);
        row.setLayoutParams(rowLp);
        root.addView(row);

        final int[] colors = new int[]{
                Color.RED,
                Color.BLUE,
                Color.GREEN,
                Color.YELLOW,
                Color.MAGENTA,
                Color.CYAN,
                Color.WHITE,
                Color.BLACK
        };

        final int[] chosenColor = new int[]{selectedPinColor};

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
            bg.setStroke(Math.round(1.5f * density), Color.parseColor("#66000000"));
            swatch.setBackground(bg);

            swatch.setOnClickListener(v -> {
                chosenColor[0] = c;

                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    Drawable d = child.getBackground();
                    if (d instanceof GradientDrawable) {
                        ((GradientDrawable) d).setStroke(
                                Math.round(1.5f * density),
                                Color.parseColor("#66000000")
                        );
                    }
                }

                ((GradientDrawable) swatch.getBackground()).setStroke(
                        Math.round(3f * density),
                        Color.WHITE
                );
            });

            row.addView(swatch);

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

                    if (selectedPinIconKey == null || selectedPinIconKey.trim().isEmpty()) {
                        selectedPinIconKey = "flag";
                    }

                    addCustomPinToServer(name, pos, selectedPinIconKey, selectedPinColor);
                    resetSelectedPinState();
                })
                .setNegativeButton("Cancel", (d, w) -> resetSelectedPinState())
                .show();
    }

    private BitmapDescriptor tintedPinIcon(int drawableRes, int color, int sizeDp) {
        Drawable d = AppCompatResources.getDrawable(this, drawableRes);
        if (d == null) return BitmapDescriptorFactory.defaultMarker();

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

    private int iconKeyToDrawableRes(String iconKey) {
        if (iconKey == null) return R.drawable.ic_pin_flag;

        switch (iconKey) {
            case "house":
                return R.drawable.ic_pin_house;
            case "flag":
                return R.drawable.ic_pin_flag;
            case "star":
                return R.drawable.ic_pin_star;
            case "skull":
                return R.drawable.ic_pin_skull;
            case "target":
                return R.drawable.ic_pin_target;
            case "plus":
                return R.drawable.ic_pin_plus;
            default:
                return R.drawable.ic_pin_flag;
        }
    }

    private void checkPermissionsAndStartTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION
            );
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS
                );
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        REQ_BACKGROUND_LOCATION
                );
                return;
            }
        }

        startTrackingService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissionsAndStartTracking();
            }
            return;
        }

        if (requestCode == REQ_NOTIFICATIONS) {
            checkPermissionsAndStartTracking();
            return;
        }

        if (requestCode == REQ_BACKGROUND_LOCATION) {
            startTrackingService();
        }
    }

    private void startTrackingService() {
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction(TrackingService.ACTION_START);
        intent.putExtra(TrackingService.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(TrackingService.EXTRA_PLAYER_NAME, playerName);

        ContextCompat.startForegroundService(this, intent);
    }

    private void stopTrackingService() {
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction(TrackingService.ACTION_STOP);
        startService(intent);
    }

    private void centerMapOnMe() {
        if (map == null) return;

        try {
            locationClient.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    lastLocation = loc;
                    LatLng me = new LatLng(loc.getLatitude(), loc.getLongitude());

                    if (myMarker == null) {
                        myMarker = map.addMarker(new MarkerOptions()
                                .position(me)
                                .title("Me")
                                .icon(getMyMarkerIcon()));
                        if (myMarker != null) {
                            myMarker.setAlpha(isEliminated ? 0.75f : 1f);
                        }
                    } else {
                        myMarker.setPosition(me);
                        myMarker.setIcon(getMyMarkerIcon());
                        myMarker.setAlpha(isEliminated ? 0.75f : 1f);
                    }

                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(me, 17f));
                } else if (myMarker != null) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(myMarker.getPosition(), 17f));
                } else {
                    Toast.makeText(this, "Waiting for GPS…", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void fetchOtherPlayers() {
        try {
            String url = BASE_URL
                    + "/api/locations?session_id="
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString())
                    + "&player_id="
                    + URLEncoder.encode(playerId, StandardCharsets.UTF_8.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        showWaitingForServerOnce();
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    try {
                        JSONObject root = new JSONObject(body);
                        JSONArray arr = root.getJSONArray("players");

                        markServerConnected();

                        runOnUiThread(() -> {
                            try {
                                syncPlayers(arr);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        showWaitingForServerOnce();
                    }
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void fetchMyPlayerState() {
        try {
            String url = BASE_URL
                    + "/api/player/state?session_id="
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString())
                    + "&player_id="
                    + URLEncoder.encode(playerId, StandardCharsets.UTF_8.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        showWaitingForServerOnce();
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    try {
                        JSONObject root = new JSONObject(body);
                        JSONObject player = root.getJSONObject("player");
                        boolean eliminated = player.optBoolean("is_eliminated", false);

                        markServerConnected();

                        runOnUiThread(() -> {
                            isEliminated = eliminated;
                            applyEliminatedButtonState();
                            updateMyMarkerAppearance();
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        showWaitingForServerOnce();
                    }
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void fetchCustomPins() {
        try {
            String url = BASE_URL
                    + "/api/pins?session_id="
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        showWaitingForServerOnce();
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    try {
                        JSONObject root = new JSONObject(body);
                        JSONArray arr = root.getJSONArray("pins");

                        markServerConnected();

                        runOnUiThread(() -> {
                            try {
                                syncCustomPins(arr);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        showWaitingForServerOnce();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void syncPlayers(JSONArray arr) throws Exception {
        if (map == null) return;

        Map<String, Boolean> seen = new HashMap<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);

            String id = p.getString("player_id");
            String name = p.getString("player_name");
            double lat = p.getDouble("lat");
            double lng = p.getDouble("lng");
            boolean eliminated = p.optBoolean("is_eliminated", false);

            seen.put(id, true);
            updateOtherPlayer(id, name, lat, lng, eliminated);
        }

        List<String> remove = new ArrayList<>();

        for (String id : otherPlayers.keySet()) {
            if (!seen.containsKey(id)) {
                Marker m = otherPlayers.get(id);
                if (m != null) m.remove();
                remove.add(id);
            }
        }

        for (String id : remove) {
            otherPlayers.remove(id);
        }
    }

    private void syncCustomPins(JSONArray arr) throws Exception {
        if (map == null) return;

        Map<String, Boolean> seen = new HashMap<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject pin = arr.getJSONObject(i);
            String pinId = pin.getString("pin_id");
            seen.put(pinId, true);
            upsertCustomPin(pin);
        }

        List<String> toRemove = new ArrayList<>();
        for (String pinId : customPins.keySet()) {
            if (!seen.containsKey(pinId)) {
                toRemove.add(pinId);
            }
        }

        for (String pinId : toRemove) {
            removeCustomPinLocally(pinId);
        }
    }

    private void updateOtherPlayer(String id, String name, double lat, double lng, boolean eliminated) {
        if (map == null || id.equals(playerId)) return;

        LatLng pos = new LatLng(lat, lng);
        BitmapDescriptor icon = getOtherPlayerIcon(eliminated);

        if (!otherPlayers.containsKey(id)) {
            Marker m = map.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(name)
                    .icon(icon));

            if (m != null) {
                m.setVisible(uiVisible);
                m.setAlpha(eliminated ? 0.8f : 1f);
            }

            otherPlayers.put(id, m);
        } else {
            Marker m = otherPlayers.get(id);
            if (m != null) {
                m.setPosition(pos);
                m.setTitle(name);
                m.setIcon(icon);
                m.setAlpha(eliminated ? 0.8f : 1f);
                m.setVisible(uiVisible);
            }
        }
    }

    private void upsertCustomPin(JSONObject pin) throws Exception {
        if (map == null) return;

        String pinId = pin.getString("pin_id");
        String title = pin.optString("title", "Pin");
        double lat = pin.getDouble("lat");
        double lng = pin.getDouble("lng");
        String iconKey = pin.optString("icon_key", "flag");
        int color = pin.optInt("color", Color.RED);

        int drawableRes = iconKeyToDrawableRes(iconKey);
        LatLng pos = new LatLng(lat, lng);
        BitmapDescriptor icon = tintedPinIcon(drawableRes, color, CUSTOM_PIN_SIZE_DP);

        Marker existing = customPins.get(pinId);

        if (existing == null) {
            Marker m = map.addMarker(new MarkerOptions()
                    .position(pos)
                    .title(title)
                    .icon(icon)
                    .anchor(0.5f, 1.0f)
                    .flat(false));

            if (m != null) {
                m.setVisible(uiVisible);
                customPins.put(pinId, m);
                customPinIdsByMarker.put(m, pinId);
            }
        } else {
            existing.setPosition(pos);
            existing.setTitle(title);
            existing.setIcon(icon);
            existing.setVisible(uiVisible);
        }
    }

    private void addCustomPinToServer(String title, LatLng pos, String iconKey, int color) {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("player_id", playerId);
            json.put("title", title);
            json.put("lat", pos.latitude);
            json.put("lng", pos.longitude);
            json.put("icon_key", iconKey);
            json.put("color", color);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/pin/add")
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        showWaitingForServerOnce();
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    try {
                        JSONObject root = new JSONObject(body);
                        JSONObject pin = root.getJSONObject("pin");

                        markServerConnected();

                        runOnUiThread(() -> {
                            try {
                                upsertCustomPin(pin);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        showWaitingForServerOnce();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void deleteCustomPinFromServer(String pinId) {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("pin_id", pinId);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/pin/delete")
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        showWaitingForServerOnce();
                        return;
                    }

                    response.close();
                    markServerConnected();

                    runOnUiThread(() -> removeCustomPinLocally(pinId));
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void removeCustomPinLocally(String pinId) {
        Marker marker = customPins.remove(pinId);
        if (marker != null) {
            customPinIdsByMarker.remove(marker);
            marker.remove();
        }

        if (pendingDeleteMarker == marker) {
            pendingDeleteMarker = null;
        }
    }

    private void sendEliminatedStateToServer(boolean eliminated) {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("player_id", playerId);
            json.put("is_eliminated", eliminated);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/player/eliminated")
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    showWaitingForServerOnce();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        showWaitingForServerOnce();
                    } else {
                        markServerConnected();
                    }
                    response.close();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            showWaitingForServerOnce();
        }
    }

    private void showWaitingForServerOnce() {
        if (serverConnectionLost) return;

        serverConnectionLost = true;

        runOnUiThread(() ->
                Toast.makeText(MapActivity.this, "Waiting for server…", Toast.LENGTH_SHORT).show()
        );
    }

    private void markServerConnected() {
        if (!serverConnectionLost) return;

        serverConnectionLost = false;

        runOnUiThread(() ->
                Toast.makeText(MapActivity.this, "Connected to server", Toast.LENGTH_SHORT).show()
        );
    }
}