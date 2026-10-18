package com.example.drishtijava;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline detector quality harness. Put an annotated dataset under the target app's
 * external files directory at DrishtiEval, or pass instrumentation argument
 * drishtiEvalPath=/absolute/path. Expected folders:
 * objects/images + objects/labels, potholes/images + potholes/labels, and
 * negative_walk/*.mp4 (videos independently reviewed to contain no target hazards).
 * Label files use YOLO normalized class/x-center/y-center/width/height format.
 */
@RunWith(AndroidJUnit4.class)
public final class DetectorQualityHarnessTest {
    private static final String TAG = "DrishtiEval";
    private static final float MATCH_IOU = 0.50f;
    private static final long VIDEO_SAMPLE_INTERVAL_US = 1_000_000L;

    @Test public void evaluateBothOnDeviceModelsWhenDatasetIsProvided() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        String suppliedPath = arguments.getString("drishtiEvalPath");
        File root = suppliedPath == null || suppliedPath.trim().isEmpty()
                ? new File(context.getExternalFilesDir(null), "DrishtiEval")
                : new File(suppliedPath);
        Assume.assumeTrue("No DrishtiEval dataset found at " + root, root.isDirectory());

        evaluateImageSplit(context, new File(root, "objects"),
                "yolo26n.tflite", "labels.txt", "objects");
        evaluateImageSplit(context, new File(root, "potholes"),
                "pothole.tflite", "pothole_labels.txt", "potholes");
        evaluateNegativeWalkingVideos(context, new File(root, "negative_walk"));
    }

    private static void evaluateImageSplit(Context context, File split, String modelAsset,
                                           String labelsAsset, String splitName) throws Exception {
        File imagesDir = new File(split, "images");
        File labelsDir = new File(split, "labels");
        File[] images = imagesDir.listFiles(file -> isImage(file.getName()));
        if (images == null || images.length == 0) {
            Log.w(TAG, splitName + ": no image files; expected " + imagesDir);
            return;
        }
        List<String> labels = readAssetLines(context, labelsAsset);
        Map<String, ClassStats> stats = new TreeMap<>();
        int evaluated = 0;
        try (YoloDetector detector = new YoloDetector(context, modelAsset, labelsAsset)) {
            for (File imageFile : images) {
                File annotation = new File(labelsDir, stem(imageFile.getName()) + ".txt");
                if (!annotation.isFile()) {
                    Log.w(TAG, splitName + ": skipping image without annotation: " + imageFile.getName());
                    continue;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
                if (bitmap == null) {
                    Log.w(TAG, splitName + ": could not decode " + imageFile.getName());
                    continue;
                }
                try {
                    List<Detection> predictions = detector.detect(toRgba(bitmap),
                            bitmap.getWidth() * 4, 4, bitmap.getWidth(), bitmap.getHeight(), 0);
                    List<Truth> truths = readYoloLabels(annotation, labels,
                            bitmap.getWidth(), bitmap.getHeight());
                    accumulate(stats, predictions, truths);
                    evaluated++;
                } finally {
                    bitmap.recycle();
                }
            }
        }
        Log.i(TAG, splitName + ": evaluated " + evaluated + "/" + images.length
                + " annotated images; class metrics use IoU >= " + MATCH_IOU);
        logMetrics(splitName, stats);
    }

    private static void evaluateNegativeWalkingVideos(Context context, File videoDir) throws Exception {
        File[] videos = videoDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (videos == null || videos.length == 0) {
            Log.i(TAG, "negative_walk: no MP4 videos; expected " + videoDir);
            return;
        }
        evaluateVideosWithModel(context, videos, "yolo26n.tflite", "labels.txt", "objects");
        evaluateVideosWithModel(context, videos, "pothole.tflite", "pothole_labels.txt", "potholes");
    }

    private static void evaluateVideosWithModel(Context context, File[] videos, String modelAsset,
                                                 String labelsAsset, String modelName) throws Exception {
        try (YoloDetector detector = new YoloDetector(context, modelAsset, labelsAsset)) {
            for (File video : videos) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                long durationMs = 0;
                long sampledFrames = 0;
                long positiveFrames = 0;
                long totalBoxes = 0;
                try {
                    retriever.setDataSource(video.getAbsolutePath());
                    String duration = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (duration != null) durationMs = Long.parseLong(duration);
                    if (durationMs <= 0) {
                        Log.w(TAG, modelName + ": could not read video duration for " + video.getName());
                        continue;
                    }
                    for (long atUs = 0; atUs < durationMs * 1000L;
                         atUs += VIDEO_SAMPLE_INTERVAL_US) {
                        Bitmap frame = retriever.getFrameAtTime(atUs,
                                MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame == null) continue;
                        try {
                            List<Detection> detections = detector.detect(toRgba(frame),
                                    frame.getWidth() * 4, 4, frame.getWidth(), frame.getHeight(), 0);
                            sampledFrames++;
                            if (!detections.isEmpty()) positiveFrames++;
                            totalBoxes += detections.size();
                        } finally {
                            frame.recycle();
                        }
                    }
                } finally {
                    retriever.release();
                }
                double minutes = durationMs / 60_000.0;
                double candidateFramesPerMinute = minutes == 0 ? 0 : positiveFrames / minutes;
                double boxesPerMinute = minutes == 0 ? 0 : totalBoxes / minutes;
                Log.i(TAG, String.format(Locale.US,
                        "%s negative-walk %s: sampled=%d, detectionFrames/min=%.2f, boxes/min=%.2f. "
                                + "Only interpret these as false candidates if the clip was independently "
                                + "verified to contain none of the model's labeled targets.",
                        modelName, video.getName(), sampledFrames,
                        candidateFramesPerMinute, boxesPerMinute));
            }
        }
    }

    private static void accumulate(Map<String, ClassStats> stats,
                                   List<Detection> predictions, List<Truth> truths) {
        for (Truth truth : truths) stats.computeIfAbsent(truth.label, ignored -> new ClassStats()).truths++;
        List<Detection> sorted = new ArrayList<>(predictions);
        sorted.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        boolean[] usedTruth = new boolean[truths.size()];
        for (Detection prediction : sorted) {
            ClassStats classStats = stats.computeIfAbsent(prediction.label,
                    ignored -> new ClassStats());
            int bestIndex = -1;
            float bestIou = MATCH_IOU;
            for (int i = 0; i < truths.size(); i++) {
                Truth truth = truths.get(i);
                if (usedTruth[i] || !truth.label.equals(prediction.label)) continue;
                float iou = intersectionOverUnion(prediction.box, truth.box);
                if (iou >= bestIou) {
                    bestIou = iou;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) {
                usedTruth[bestIndex] = true;
                classStats.truePositives++;
            } else {
                classStats.falsePositives++;
            }
        }
    }

    private static void logMetrics(String split, Map<String, ClassStats> stats) {
        if (stats.isEmpty()) {
            Log.w(TAG, split + ": no valid ground truth or predictions");
            return;
        }
        for (Map.Entry<String, ClassStats> entry : stats.entrySet()) {
            ClassStats value = entry.getValue();
            double precision = value.truePositives + value.falsePositives == 0 ? 0
                    : value.truePositives / (double) (value.truePositives + value.falsePositives);
            double recall = value.truths == 0 ? 0 : value.truePositives / (double) value.truths;
            Log.i(TAG, String.format(Locale.US,
                    "%s class=%s TP=%d FP=%d GT=%d precision=%.4f recall=%.4f",
                    split, entry.getKey(), value.truePositives, value.falsePositives,
                    value.truths, precision, recall));
        }
    }

    private static List<Truth> readYoloLabels(File file, List<String> labels,
                                               int width, int height) throws Exception {
        List<Truth> truths = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                String[] fields = value.split("\\s+");
                if (fields.length < 5) throw new IllegalArgumentException("Malformed YOLO label in " + file);
                int classId = Integer.parseInt(fields[0]);
                if (classId < 0 || classId >= labels.size()) {
                    throw new IllegalArgumentException("Class id outside labels.txt in " + file);
                }
                float centerX = Float.parseFloat(fields[1]) * width;
                float centerY = Float.parseFloat(fields[2]) * height;
                float boxWidth = Float.parseFloat(fields[3]) * width;
                float boxHeight = Float.parseFloat(fields[4]) * height;
                truths.add(new Truth(labels.get(classId), new RectF(centerX - boxWidth / 2f,
                        centerY - boxHeight / 2f, centerX + boxWidth / 2f,
                        centerY + boxHeight / 2f)));
            }
        }
        return truths;
    }

    private static List<String> readAssetLines(Context context, String asset) throws Exception {
        List<String> values = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) values.add(line.trim());
            }
        }
        return values;
    }

    private static ByteBuffer toRgba(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            rgba.put((byte) ((pixel >> 16) & 0xff));
            rgba.put((byte) ((pixel >> 8) & 0xff));
            rgba.put((byte) (pixel & 0xff));
            rgba.put((byte) ((pixel >>> 24) & 0xff));
        }
        rgba.rewind();
        return rgba;
    }

    private static float intersectionOverUnion(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private static String stem(String name) {
        int extension = name.lastIndexOf('.');
        return extension < 0 ? name : name.substring(0, extension);
    }

    private static final class Truth {
        final String label;
        final RectF box;
        Truth(String label, RectF box) { this.label = label; this.box = box; }
    }

    private static final class ClassStats {
        int truePositives;
        int falsePositives;
        int truths;
    }
}
