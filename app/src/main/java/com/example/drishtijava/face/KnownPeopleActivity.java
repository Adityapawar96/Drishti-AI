package com.example.drishtijava.face;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

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
            nameInput.setError("Enter a name first");
            nameInput.requestFocus();
            return;
        }
        if (name.length() > 40) {
            nameInput.setError("Use a name up to 40 characters");
            nameInput.requestFocus();
            return;
        }
        addButton.setEnabled(false);
        status.setText("Checking saved people…");
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
                        nameInput.setError("A person with that name is already saved");
                        nameInput.requestFocus();
                        status.setText("That name is already in known people.");
                        return;
                    }
                    showConsentAndEnroll(name);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    addButton.setEnabled(true);
                    status.setText("Saved people could not be checked. Please try again.");
                });
            }
        });
    }

    private void showConsentAndEnroll(String name) {
        new AlertDialog.Builder(this)
                .setTitle("Consent to face recognition")
                .setMessage("Only enroll this person if they understand and agree. Their face profile is encrypted on this device, their photos are not saved, and you can delete the profile at any time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("I have their consent", (dialog, which) -> {
                    Intent intent = new Intent(this, FaceEnrollmentActivity.class);
                    intent.putExtra("person_name", name);
                    enrollmentLauncher.launch(intent);
                })
                .show();
    }

    private void loadProfiles() {
        status.setText("Loading saved people…");
        ioExecutor.execute(() -> {
            try {
                List<FaceProfile> profiles = profileStore.load();
                runOnUiThread(() -> showProfiles(profiles));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Saved people could not be opened. Reinstalling or restoring the app may have removed the encryption key."));
            }
        });
    }

    private void showProfiles(List<FaceProfile> profiles) {
        list.removeAllViews();
        if (profiles.isEmpty()) {
            status.setText("No known people saved yet.");
            return;
        }
        status.setText(profiles.size() + (profiles.size() == 1 ? " known person saved." : " known people saved."));
        for (FaceProfile profile : profiles) {
            LinearLayout profileCard = new LinearLayout(this);
            profileCard.setOrientation(LinearLayout.VERTICAL);
            profileCard.setPadding(dp(16), dp(12), dp(16), dp(12));
            profileCard.setBackgroundResource(com.example.drishtijava.R.drawable.duo_card_background);

            TextView name = new TextView(this);
            name.setText(profile.name);
            name.setTextSize(21f);
            name.setTextColor(ContextCompat.getColor(this, com.example.drishtijava.R.color.duo_ink));
            name.setTypeface(Typeface.create("sans-serif-rounded", Typeface.BOLD));
            name.setPadding(0, dp(4), 0, dp(8));
            name.setContentDescription("Known person: " + profile.name);
            ViewCompat.setAccessibilityHeading(name, true);
            profileCard.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            Button delete = new Button(this);
            delete.setText("Delete " + profile.name);
            delete.setContentDescription("Delete the saved face profile for " + profile.name);
            delete.setMinHeight(dp(56));
            delete.setAllCaps(false);
            delete.setTextColor(ContextCompat.getColor(this, com.example.drishtijava.R.color.duo_button_text));
            delete.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, com.example.drishtijava.R.color.duo_pale_green)));
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
                .setTitle("Delete " + profile.name + "?")
                .setMessage("This removes the encrypted face profile from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> ioExecutor.execute(() -> {
                    try {
                        profileStore.delete(profile.id);
                        runOnUiThread(this::loadProfiles);
                    } catch (Exception e) {
                        runOnUiThread(() -> status.setText("Could not delete that face profile."));
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
