package com.example.drishtijava.face;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-GCM encrypted storage for names and face embeddings. */
public final class FaceProfileStore {
    public static final String FILE_NAME = "face-profiles.enc";
    private static final String KEY_ALIAS = "drishti_face_profiles_v1";
    private static final int IV_LENGTH = 12;
    private final AtomicFile file;
    private final Object lock = new Object();

    public FaceProfileStore(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getFilesDir(), FILE_NAME));
    }

    public List<FaceProfile> load() throws Exception {
        synchronized (lock) {
            if (!file.getBaseFile().exists()) return new ArrayList<>();
            byte[] packed = readAll(file);
            if (packed.length <= IV_LENGTH) throw new IllegalStateException("Encrypted profiles are incomplete");
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[packed.length - IV_LENGTH];
            System.arraycopy(packed, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
            byte[] clear = cipher.doFinal(encrypted);
            return decode(new String(clear, StandardCharsets.UTF_8));
        }
    }

    public FaceProfile save(String name, List<float[]> references) throws Exception {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 40) throw new IllegalArgumentException("Enter a name up to 40 characters");
        if (references == null || references.size() != 3) throw new IllegalArgumentException("Three pose embeddings are required");
        List<float[]> normalized = new ArrayList<>();
        for (float[] reference : references) {
            float[] value = FaceMath.normalized(reference);
            if (value.length == 0) throw new IllegalArgumentException("A face embedding was invalid");
            normalized.add(value);
        }
        FaceProfile profile = new FaceProfile(UUID.randomUUID().toString(), cleanName, normalized);
        synchronized (lock) {
            List<FaceProfile> profiles = loadUnlocked();
            for (FaceProfile saved : profiles) {
                if (saved.name.trim().toLowerCase(Locale.ROOT).equals(cleanName.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("A person with that name is already saved");
                }
            }
            profiles.add(profile);
            writeEncrypted(encode(profiles));
        }
        return profile;
    }

    public boolean delete(String profileId) throws Exception {
        synchronized (lock) {
            List<FaceProfile> profiles = loadUnlocked();
            boolean removed = profiles.removeIf(profile -> profile.id.equals(profileId));
            if (removed) writeEncrypted(encode(profiles));
            return removed;
        }
    }

    private List<FaceProfile> loadUnlocked() throws Exception {
        if (!file.getBaseFile().exists()) return new ArrayList<>();
        byte[] packed = readAll(file);
        if (packed.length <= IV_LENGTH) throw new IllegalStateException("Encrypted profiles are incomplete");
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
        byte[] encrypted = new byte[packed.length - IV_LENGTH];
        System.arraycopy(packed, IV_LENGTH, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
        return decode(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
    }

    private void writeEncrypted(String clearText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(clearText.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream packed = new ByteArrayOutputStream(iv.length + encrypted.length);
        packed.write(iv);
        packed.write(encrypted);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(packed.toByteArray());
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) throw new java.security.GeneralSecurityException("Face profile encryption key is unavailable");
        Key saved = keyStore.getKey(KEY_ALIAS, null);
        if (saved instanceof SecretKey) return (SecretKey) saved;
        throw new java.security.GeneralSecurityException("Face profile encryption key is invalid");
    }

    private static SecretKey getOrCreateKey() throws Exception {
        try {
            return getExistingKey();
        } catch (Exception missingKey) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) throw missingKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encode(List<FaceProfile> profiles) throws Exception {
        JSONArray array = new JSONArray();
        for (FaceProfile profile : profiles) {
            JSONObject item = new JSONObject();
            item.put("id", profile.id);
            item.put("name", profile.name);
            JSONArray embeddings = new JSONArray();
            for (float[] embedding : profile.getReferences()) {
                JSONArray values = new JSONArray();
                for (float value : embedding) values.put((double) value);
                embeddings.put(values);
            }
            item.put("embeddings", embeddings);
            array.put(item);
        }
        return array.toString();
    }

    private static List<FaceProfile> decode(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<FaceProfile> profiles = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            JSONArray embeddings = item.getJSONArray("embeddings");
            List<float[]> references = new ArrayList<>();
            for (int j = 0; j < embeddings.length(); j++) {
                JSONArray values = embeddings.getJSONArray(j);
                float[] embedding = new float[values.length()];
                for (int k = 0; k < values.length(); k++) embedding[k] = (float) values.getDouble(k);
                references.add(embedding);
            }
            profiles.add(new FaceProfile(item.getString("id"), item.getString("name"), references));
        }
        return profiles;
    }

    private static byte[] readAll(AtomicFile atomicFile) throws Exception {
        try (FileInputStream input = atomicFile.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
