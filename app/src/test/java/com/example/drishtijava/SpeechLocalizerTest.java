package com.example.drishtijava;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SpeechLocalizerTest {
    @Test public void englishAlertKeepsExistingPhraseAndPosition() {
        SpeechLocalizer localizer = new SpeechLocalizer(SpeechLanguage.ENGLISH);

        assertEquals("Possible car, on the left side of the view.", localizer.objectAlert(
                "car", 1, true, SpeechLocalizer.Location.LEFT));
    }

    @Test public void hindiLocalizesObjectPotholeAndPosition() {
        SpeechLocalizer localizer = new SpeechLocalizer(SpeechLanguage.HINDI);

        assertEquals("संभावित कार, दृश्य के बाईं ओर।", localizer.objectAlert(
                "car", 1, true, SpeechLocalizer.Location.LEFT));
        assertEquals("रस्त्यातील खड्डा", new SpeechLocalizer(SpeechLanguage.MARATHI)
                .localizeLabel("pothole"));
        assertNotEquals("car", localizer.localizeLabel("car"));
    }

    @Test public void marathiPersonAndKnownNamesKeepNativeNames() {
        SpeechLocalizer localizer = new SpeechLocalizer(SpeechLanguage.MARATHI);

        assertEquals("संभाव्य व्यक्ती, दृश्याच्या डाव्या बाजूला.", localizer.personAlert(
                1, true, SpeechLocalizer.Location.LEFT));
        assertEquals("Amit, दृश्याच्या उजव्या बाजूला.", localizer.knownPersonAlerts(
                Collections.singletonList(new SpeechLocalizer.NamedPerson(
                        "Amit", SpeechLocalizer.Location.RIGHT))));
    }

    @Test public void multipleObjectsAndNamesAreFormattedWithoutEnglishConnectors() {
        SpeechLocalizer localizer = new SpeechLocalizer(SpeechLanguage.HINDI);

        assertEquals("कैमरे के दृश्य में 2 कार हैं, मुख्य रूप से दाईं ओर।",
                localizer.objectAlert("car", 2, false, SpeechLocalizer.Location.RIGHT));
        assertEquals("Amit, दृश्य के बाईं ओर। Neha, दृश्य के बीच में।",
                localizer.knownPersonAlerts(Arrays.asList(
                        new SpeechLocalizer.NamedPerson("Amit", SpeechLocalizer.Location.LEFT),
                        new SpeechLocalizer.NamedPerson("Neha", SpeechLocalizer.Location.CENTER))));
    }

    @Test public void invalidLanguagePreferenceFallsBackToEnglish() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.fromPreference("invalid"));
        assertEquals(SpeechLanguage.HINDI, SpeechLanguage.fromPreference("hi"));
        assertEquals(SpeechLanguage.MARATHI, SpeechLanguage.fromPreference("mr"));
    }
}
