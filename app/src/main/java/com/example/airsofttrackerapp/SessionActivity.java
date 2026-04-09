package com.example.airsofttrackerapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.UUID;

public class SessionActivity extends AppCompatActivity {

    EditText etName, etSession;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_session);

        etName = findViewById(R.id.etName);
        etSession = findViewById(R.id.etSession);

        findViewById(R.id.btnHost).setOnClickListener(v -> {
            String sessionId = UUID.randomUUID().toString().substring(0, 8);
            openMap(sessionId);
        });

        findViewById(R.id.btnJoin).setOnClickListener(v -> {
            openMap(etSession.getText().toString());
        });

        enableFullscreen();

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

    void openMap(String sessionId) {
        Intent i = new Intent(this, MapActivity.class);
        i.putExtra("name", etName.getText().toString());
        i.putExtra("session", sessionId);
        startActivity(i);
    }

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
}