package com.example.drishtijava.face;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TalkBack-friendly list of locally saved face profiles and their delete controls. */
public final class KnownPeopleActivity extends AppCompatActivity {
    private TextView status;
    private LinearLayout list;
    private EditText nameInput;
    private Button addButton;
    private ActivityResultLauncher<Intent> enrollmentLauncher;
    private FaceProfileStore profileStore;
    private ExecutorService ioExecutor;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.example.drishtijava.R.layout.activity_known_people);
        status = findViewById(com.example.drishtijava.R.id.knownPeopleStatus);
        list = findViewById(com.example.drishtijava.R.id.knownPeopleList);
        nameInput = findViewById(com.example.drishtijava.R.id.personNameInput);
        profileStore = new FaceProfileStore(getApplicationContext());
        ioExecutor = Executors.newSingleThreadExecutor();
        enrollmentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        loadProfiles();
                    }
                });
        addButton = findViewById(com.example.drishtijava.R.id.addKnownPersonButton);
        addButton.setOnClickListener(view -> confirmConsentAndEnroll());
        findViewById(com.example.drishtijava.R.id.knownPeopleBackButton).setOnClickListener(view -> finish());
        loadProfiles();
    }

    @Override protected void onResume() {
        super.onResume();
        if (profileStore != null) loadProfiles();
    }

    private void confirmConsentAndEnroll() {
        String name = nameInput.getText().toString().trim();
        nameInput.setError(null);
        if (name.isEmpty()) {
            nameInput.setError(getString(com.example.drishtijava.R.string.enter_name_first));
            nameInput.requestFocus();
            return;
        }
        if (name.length() > 40) {
            nameInput.setError(getString(com.example.drishtijava.R.string.name_too_long));
            nameInput.requestFocus();
            return;
        }
        addButton.setEnabled(false);
        status.setText(com.example.drishtijava.R.string.checking_saved_people);
        ioExecutor.execute(() -> {
            try {
                List<FaceProfile> profiles = profileStore.load();
                boolean alreadySaved = profiles.stream().anyMatch(profile ->
                        profile.name.trim().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    addButton.setEnabled(true);
                    showProfiles(profiles);
                    if (alreadySaved) {
                        nameInput.setError(getString(com.example.drishtijava.R.string.duplicate_saved_name));
                        nameInput.requestFocus();
                        status.setText(com.example.drishtijava.R.string.duplicate_known_person);
                        return;
                    }
                    showConsentAndEnroll(name);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    addButton.setEnabled(true);
                    status.setText(com.example.drishtijava.R.string.saved_people_check_failed);
                });
            }
        });
    }

    private void showConsentAndEnroll(String name) {
        new AlertDialog.Builder(this)
                .setTitle(com.example.drishtijava.R.string.face_consent_title)
                .setMessage(com.example.drishtijava.R.string.face_consent_message)
                .setNegativeButton(com.example.drishtijava.R.string.cancel, null)
                .setPositiveButton(com.example.drishtijava.R.string.i_have_consent, (dialog, which) -> {
                    Intent intent = new Intent(this, FaceEnrollmentActivity.class);
                    intent.putExtra("person_name", name);
                    enrollmentLauncher.launch(intent);
                })
                .show();
    }

    private void loadProfiles() {
        status.setText(com.example.drishtijava.R.string.loading_saved_people);
        ioExecutor.execute(() -> {
            try {
                List<FaceProfile> profiles = profileStore.load();
                runOnUiThread(() -> showProfiles(profiles));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(com.example.drishtijava.R.string.saved_people_unavailable));
            }
        });
    }

    private void showProfiles(List<FaceProfile> profiles) {
        list.removeAllViews();
        if (profiles.isEmpty()) {
            status.setText(com.example.drishtijava.R.string.no_known_people);
            return;
        }
        status.setText(profiles.size() == 1
                ? getString(com.example.drishtijava.R.string.known_person_saved_one)
                : getString(com.example.drishtijava.R.string.known_people_saved_many, profiles.size()));
        for (FaceProfile profile : profiles) {
            LinearLayout profileCard = new LinearLayout(this);
            profileCard.setOrientation(LinearLayout.VERTICAL);
            profileCard.setPadding(dp(16), dp(12), dp(16), dp(12));
            profileCard.setBackgroundResource(com.example.drishtijava.R.drawable.duo_card_background);

            TextView name = new TextView(this);
            name.setText(profile.name);
            name.setTextSize(22f);
            name.setTypeface(ResourcesCompat.getFont(this,
                    com.example.drishtijava.R.font.baloo2_bold));
            name.setPadding(0, dp(4), 0, dp(8));
            name.setContentDescription(getString(
                    com.example.drishtijava.R.string.known_person_accessibility, profile.name));
            ViewCompat.setAccessibilityHeading(name, true);
            profileCard.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            MaterialButton delete = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            delete.setText(getString(com.example.drishtijava.R.string.delete_person, profile.name));
            delete.setContentDescription(getString(
                    com.example.drishtijava.R.string.delete_person_description, profile.name));
            delete.setMinHeight(dp(56));
            delete.setAllCaps(false);
            delete.setOnClickListener(view -> confirmDelete(profile));
            profileCard.addView(delete, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(12);
            list.addView(profileCard, cardParams);
        }
    }

    private void confirmDelete(FaceProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle(getString(com.example.drishtijava.R.string.delete_person_title, profile.name))
                .setMessage(com.example.drishtijava.R.string.delete_profile_message)
                .setNegativeButton(com.example.drishtijava.R.string.cancel, null)
                .setPositiveButton(com.example.drishtijava.R.string.delete, (dialog, which) -> ioExecutor.execute(() -> {
                    try {
                        profileStore.delete(profile.id);
                        runOnUiThread(this::loadProfiles);
                    } catch (Exception e) {
                        runOnUiThread(() -> status.setText(com.example.drishtijava.R.string.delete_person_failed));
                    }
                }))
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        if (ioExecutor != null) ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
