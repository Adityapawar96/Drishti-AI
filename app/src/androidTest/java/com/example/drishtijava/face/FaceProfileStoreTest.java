package com.example.drishtijava.face;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class FaceProfileStoreTest {
    @Test public void profileIsEncryptedReadableAndDeletable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FaceProfileStore store = new FaceProfileStore(context);
        String name = "Private-" + UUID.randomUUID();
        List<float[]> references = new ArrayList<>();
        references.add(new float[]{1f, 0f, 0f});
        references.add(new float[]{0f, 1f, 0f});
        references.add(new float[]{0f, 0f, 1f});

        FaceProfile saved = store.save(name, references);
        File encryptedFile = new File(context.getFilesDir(), FaceProfileStore.FILE_NAME);
        String rawFile = new String(Files.readAllBytes(encryptedFile.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(rawFile.contains(name));

        List<FaceProfile> loaded = store.load();
        FaceProfile roundTrip = null;
        for (FaceProfile profile : loaded) if (profile.id.equals(saved.id)) roundTrip = profile;
        assertTrue(roundTrip != null);
        assertEquals(name, roundTrip.name);
        assertEquals(3, roundTrip.getReferences().size());

        assertTrue(store.delete(saved.id));
        for (FaceProfile profile : store.load()) assertFalse(profile.id.equals(saved.id));
    }
}
