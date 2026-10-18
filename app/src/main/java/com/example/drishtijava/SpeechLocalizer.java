package com.example.drishtijava;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Builds short spoken alerts in the selected language without requiring network access. */
public final class SpeechLocalizer {
    public enum Location { LEFT, CENTER, RIGHT, VIEW }

    public static final class NamedPerson {
        public final String name;
        public final Location location;

        public NamedPerson(String name, Location location) {
            this.name = name == null ? "" : name.trim();
            this.location = location == null ? Location.VIEW : location;
        }
    }

    private static final String[] COCO_LABELS = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli",
            "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant",
            "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    };

    private static final Map<String, String> HINDI_LABELS = translations(
            "व्यक्ति", "साइकिल", "कार", "मोटरसाइकिल", "हवाई जहाज़", "बस", "रेलगाड़ी", "ट्रक",
            "नाव", "ट्रैफिक लाइट", "अग्निशामक नल", "स्टॉप साइन", "पार्किंग मीटर", "बेंच",
            "पक्षी", "बिल्ली", "कुत्ता", "घोड़ा", "भेड़", "गाय", "हाथी", "भालू", "ज़ेब्रा",
            "जिराफ़", "बैग", "छाता", "हैंडबैग", "टाई", "सूटकेस", "फ्रिस्बी", "स्की", "स्नोबोर्ड",
            "गेंद", "पतंग", "बेसबॉल बैट", "बेसबॉल दस्ताना", "स्केटबोर्ड", "सर्फ़बोर्ड",
            "टेनिस रैकेट", "बोतल", "गिलास", "कप", "कांटा", "चाकू", "चम्मच", "कटोरा", "केला",
            "सेब", "सैंडविच", "संतरा", "ब्रोकोली", "गाजर", "हॉट डॉग", "पिज़्ज़ा", "डोनट", "केक",
            "कुर्सी", "सोफ़ा", "गमले का पौधा", "बिस्तर", "खाने की मेज़", "शौचालय", "टीवी",
            "लैपटॉप", "माउस", "रिमोट", "कीबोर्ड", "मोबाइल फ़ोन", "माइक्रोवेव", "ओवन", "टोस्टर",
            "सिंक", "फ्रिज", "किताब", "घड़ी", "फूलदान", "कैंची", "टेडी बियर", "हेयर ड्रायर",
            "टूथब्रश");

    private static final Map<String, String> MARATHI_LABELS = translations(
            "व्यक्ती", "सायकल", "कार", "मोटारसायकल", "विमान", "बस", "आगगाडी", "ट्रक",
            "नाव", "वाहतूक दिवा", "अग्निशामक नळ", "थांबा चिन्ह", "पार्किंग मीटर", "बाक",
            "पक्षी", "मांजर", "कुत्रा", "घोडा", "मेंढी", "गाय", "हत्ती", "अस्वल", "झेब्रा",
            "जिराफ", "पिशवी", "छत्री", "हँडबॅग", "टाय", "सूटकेस", "फ्रिस्बी", "स्की", "स्नोबोर्ड",
            "चेंडू", "पतंग", "बेसबॉल बॅट", "बेसबॉल ग्लोव्ह", "स्केटबोर्ड", "सर्फबोर्ड",
            "टेनिस रॅकेट", "बाटली", "ग्लास", "कप", "काटा", "सुरी", "चमचा", "वाडगा", "केळे",
            "सफरचंद", "सँडविच", "संत्रे", "ब्रोकोली", "गाजर", "हॉट डॉग", "पिझ्झा", "डोनट", "केक",
            "खुर्ची", "सोफा", "कुंडीतील रोप", "अंथरूण", "जेवणाचे टेबल", "शौचालय", "टीव्ही",
            "लॅपटॉप", "माऊस", "रिमोट", "कीबोर्ड", "मोबाइल फोन", "मायक्रोवेव्ह", "ओव्हन", "टोस्टर",
            "सिंक", "फ्रिज", "पुस्तक", "घड्याळ", "फुलदाणी", "कात्री", "टेडी बेअर", "हेअर ड्रायर",
            "टूथब्रश");

    private final SpeechLanguage language;

    public SpeechLocalizer(SpeechLanguage language) {
        this.language = language == null ? SpeechLanguage.ENGLISH : language;
    }

    public String objectAlert(String label, int count, boolean possible, Location location) {
        String safeLabel = label == null ? "object" : label.trim();
        Location safeLocation = location == null ? Location.VIEW : location;
        String localizedLabel = localizeLabel(safeLabel);
        int safeCount = Math.max(1, count);
        if (language == SpeechLanguage.ENGLISH) {
            StringBuilder message = new StringBuilder();
            if (possible) message.append("Possible ");
            if (safeCount == 1) {
                message.append(safeLabel).append(", ").append(englishLocation(safeLocation));
            } else {
                message.append(safeCount).append(' ').append(pluralize(safeLabel, safeCount))
                        .append(" in the camera view");
                if (safeLocation != Location.VIEW) {
                    message.append(", mostly ").append(shortEnglishLocation(safeLocation));
                }
            }
            return message.append('.').toString();
        }

        if (language == SpeechLanguage.HINDI) {
            if (safeCount == 1) {
                return (possible ? "संभावित " : "") + localizedLabel + ", "
                        + hindiLocation(safeLocation) + "।";
            }
            return (possible ? "संभावित " : "") + "कैमरे के दृश्य में " + safeCount + " "
                    + localizedLabel + " हैं" + localizedQualifier(safeLocation, true) + "।";
        }

        if (safeCount == 1) {
            return (possible ? "संभाव्य " : "") + localizedLabel + ", "
                    + marathiLocation(safeLocation) + ".";
        }
        return (possible ? "संभाव्य " : "") + "कॅमेरा दृश्यात " + safeCount + " "
                + localizedLabel + " आहेत" + localizedQualifier(safeLocation, false) + ".";
    }

    public String personAlert(int count, boolean possible, Location location) {
        int safeCount = Math.max(1, count);
        if (language == SpeechLanguage.ENGLISH) {
            if (safeCount > 1) return safeCount + " people in the camera view.";
            return (possible ? "Possible person" : "Person") + ", "
                    + englishLocation(location == null ? Location.VIEW : location) + ".";
        }
        if (language == SpeechLanguage.HINDI) {
            if (safeCount > 1) return "कैमरे के दृश्य में " + safeCount + " लोग।";
            return (possible ? "संभावित व्यक्ति" : "व्यक्ति") + ", "
                    + hindiLocation(location == null ? Location.VIEW : location) + "।";
        }
        if (safeCount > 1) return "कॅमेरा दृश्यात " + safeCount + " लोक आहेत.";
        return (possible ? "संभाव्य व्यक्ती" : "व्यक्ती") + ", "
                + marathiLocation(location == null ? Location.VIEW : location) + ".";
    }

    public String knownPersonAlerts(Iterable<NamedPerson> people) {
        StringBuilder result = new StringBuilder();
        if (people == null) return "";
        for (NamedPerson person : people) {
            if (person == null || person.name.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(person.name).append(", ");
            if (language == SpeechLanguage.ENGLISH) {
                result.append(englishLocation(person.location)).append('.');
            } else if (language == SpeechLanguage.HINDI) {
                result.append(hindiLocation(person.location)).append('।');
            } else {
                result.append(marathiLocation(person.location)).append('.');
            }
        }
        return result.toString();
    }

    public String localizeLabel(String label) {
        if (label == null) return "";
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        if ("pothole".equals(normalized)) {
            if (language == SpeechLanguage.HINDI) return "सड़क का गड्ढा";
            if (language == SpeechLanguage.MARATHI) return "रस्त्यातील खड्डा";
            return label;
        }
        if (language == SpeechLanguage.HINDI) return HINDI_LABELS.getOrDefault(normalized, label);
        if (language == SpeechLanguage.MARATHI) return MARATHI_LABELS.getOrDefault(normalized, label);
        return label;
    }

    private String localizedQualifier(Location location, boolean hindi) {
        if (location == Location.VIEW) return "";
        String direction = hindi ? hindiShortLocation(location) : marathiShortLocation(location);
        return hindi ? ", मुख्य रूप से " + direction : ", प्रामुख्याने " + direction;
    }

    private static String englishLocation(Location location) {
        switch (location) {
            case LEFT: return "on the left side of the view";
            case CENTER: return "in the center of the view";
            case RIGHT: return "on the right side of the view";
            default: return "in the camera view";
        }
    }

    private static String shortEnglishLocation(Location location) {
        switch (location) {
            case LEFT: return "on the left";
            case CENTER: return "in the center";
            case RIGHT: return "on the right";
            default: return "in the camera view";
        }
    }

    private static String hindiLocation(Location location) {
        switch (location) {
            case LEFT: return "दृश्य के बाईं ओर";
            case CENTER: return "दृश्य के बीच में";
            case RIGHT: return "दृश्य के दाईं ओर";
            default: return "कैमरे के दृश्य में";
        }
    }

    private static String marathiLocation(Location location) {
        switch (location) {
            case LEFT: return "दृश्याच्या डाव्या बाजूला";
            case CENTER: return "दृश्याच्या मध्यभागी";
            case RIGHT: return "दृश्याच्या उजव्या बाजूला";
            default: return "कॅमेरा दृश्यात";
        }
    }

    private static String hindiShortLocation(Location location) {
        switch (location) {
            case LEFT: return "बाईं ओर";
            case CENTER: return "बीच में";
            case RIGHT: return "दाईं ओर";
            default: return "कैमरे के दृश्य में";
        }
    }

    private static String marathiShortLocation(Location location) {
        switch (location) {
            case LEFT: return "डाव्या बाजूला";
            case CENTER: return "मध्यभागी";
            case RIGHT: return "उजव्या बाजूला";
            default: return "कॅमेरा दृश्यात";
        }
    }

    private static String pluralize(String label, int count) {
        if (count == 1) return label;
        switch (label.toLowerCase(Locale.ROOT)) {
            case "person": return "people";
            case "mouse": return "mice";
            case "knife": return "knives";
            case "sheep":
            case "scissors": return label;
            case "tv": return "TVs";
            default:
                String lower = label.toLowerCase(Locale.ROOT);
                if (lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("s")
                        || lower.endsWith("x") || lower.endsWith("z")) return label + "es";
                if (lower.endsWith("y") && lower.length() > 1
                        && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
                    return label.substring(0, label.length() - 1) + "ies";
                }
                return label + "s";
        }
    }

    private static Map<String, String> translations(String... labels) {
        if (labels.length != COCO_LABELS.length) {
            throw new IllegalStateException("Every COCO class needs a translation");
        }
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < COCO_LABELS.length; i++) result.put(COCO_LABELS[i], labels[i]);
        return Collections.unmodifiableMap(result);
    }
}
