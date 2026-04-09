package com.example.airsofttrackerapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SessionActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://rpi.mapairsofttracker.org";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private static final String PREFS_NAME = "airsoft_prefs";
    private static final String PREF_PLAYER_ID = "player_id";

    private EditText etName;
    private EditText etSession;
    private MaterialButton btnHost;
    private MaterialButton btnJoin;

    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session);

        etName = findViewById(R.id.etName);
        etSession = findViewById(R.id.etSession);
        btnHost = findViewById(R.id.btnHost);
        btnJoin = findViewById(R.id.btnJoin);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();

        btnHost.setOnClickListener(v -> onHostClicked());
        btnJoin.setOnClickListener(v -> onJoinClicked());
    }

    private void onHostClicked() {
        String playerName = etName.getText() != null
                ? etName.getText().toString().trim()
                : "";

        String sessionId = etSession.getText() != null
                ? etSession.getText().toString().trim()
                : "";

        if (playerName.isEmpty()) {
            Toast.makeText(this, "Enter player name", Toast.LENGTH_SHORT).show();
            return;
        }

        if (sessionId.isEmpty()) {
            sessionId = generateSessionId();
            etSession.setText(sessionId);
        }

        String finalSessionId = sessionId;
        checkSessionExists(finalSessionId, exists -> {
            if (exists) {
                runOnUiThread(() ->
                        Toast.makeText(SessionActivity.this, "Session already exists", Toast.LENGTH_SHORT).show()
                );
            } else {
                createSessionAndOpenMap(finalSessionId, playerName);
            }
        });
    }

    private void onJoinClicked() {
        String playerName = etName.getText() != null
                ? etName.getText().toString().trim()
                : "";

        String sessionId = etSession.getText() != null
                ? etSession.getText().toString().trim()
                : "";

        if (playerName.isEmpty()) {
            Toast.makeText(this, "Enter player name", Toast.LENGTH_SHORT).show();
            return;
        }

        if (sessionId.isEmpty()) {
            Toast.makeText(this, "Enter session ID", Toast.LENGTH_SHORT).show();
            return;
        }

        checkSessionExists(sessionId, exists -> {
            if (!exists) {
                runOnUiThread(() ->
                        Toast.makeText(SessionActivity.this, "Session does not exist", Toast.LENGTH_SHORT).show()
                );
            } else {
                openMap(sessionId, playerName);
            }
        });
    }

    private interface SessionExistsCallback {
        void onResult(boolean exists);
    }

    private void checkSessionExists(String sessionId, SessionExistsCallback callback) {
        try {
            String url = BASE_URL + "/api/session/exists?session_id="
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.toString());

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(SessionActivity.this, "Waiting for server…", Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        runOnUiThread(() ->
                                Toast.makeText(SessionActivity.this, "Server error", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    try {
                        JSONObject json = new JSONObject(body);
                        boolean exists = json.optBoolean("exists", false);
                        callback.onResult(exists);
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() ->
                                Toast.makeText(SessionActivity.this, "Invalid server response", Toast.LENGTH_SHORT).show()
                        );
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Server error", Toast.LENGTH_SHORT).show();
        }
    }

    private void createSessionAndOpenMap(String sessionId, String playerName) {
        try {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("player_id", getOrCreatePlayerId());
            json.put("player_name", playerName);

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/session/create")
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(SessionActivity.this, "Waiting for server…", Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    int code = response.code();
                    if (response.body() != null) {
                        response.body().string();
                    }
                    response.close();

                    if (code == 409) {
                        runOnUiThread(() ->
                                Toast.makeText(SessionActivity.this, "Session already exists", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    if (code < 200 || code >= 300) {
                        runOnUiThread(() ->
                                Toast.makeText(SessionActivity.this, "Could not create session", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }

                    openMap(sessionId, playerName);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Could not create session", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMap(String sessionId, String playerName) {
        runOnUiThread(() -> {
            Intent intent = new Intent(SessionActivity.this, MapActivity.class);
            intent.putExtra("session", sessionId);
            intent.putExtra("name", playerName);
            startActivity(intent);
        });
    }

    private String generateSessionId() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }

        return sb.toString();
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
}