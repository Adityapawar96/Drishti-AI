package com.example.drishtijava;

import android.graphics.Bitmap;
import android.util.Base64;

import androidx.camera.core.ImageProxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Sends a user-requested still image to Gemini and returns a short scene description. */
final class GeminiSceneAnalyzer implements AutoCloseable {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
            + MODEL + ":generateContent";
    private static final String PROMPT = "Describe this camera image in no more than two short sentences. "
            + "Mention prominent visible objects and their approximate positions (left, center, or right), "
            + "and note obvious obstacles or road hazards. Only state what is clearly visible. Do not identify "
            + "people, infer personal traits or emotions, or claim that a route is safe.";
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final String apiKey;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection activeConnection;
    private volatile boolean closed;

    GeminiSceneAnalyzer(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** Copies the camera buffer briefly, then resizes, encodes, and uploads off the camera thread. */
    void analyze(ImageProxy image, Callback callback) {
        final RgbaFrame frame;
        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int rotation = image.getImageInfo().getRotationDegrees();
            int byteCount = Math.multiplyExact(rowStride, height);
            java.nio.ByteBuffer source = plane.getBuffer().duplicate();
            source.clear();
            if (source.remaining() < byteCount) {
                throw new IOException("Camera image buffer is incomplete");
            }
            byte[] rgba = new byte[byteCount];
            source.get(rgba);
            frame = new RgbaFrame(rgba, width, height, rowStride, pixelStride, rotation);
        } catch (Exception e) {
            callback.onFailure("Could not prepare the camera image. Please try again.");
            return;
        }
        try {
            executor.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (closed) return;
                    if (apiKey.isEmpty()) {
                        callback.onFailure("Gemini is not configured. Add geminiApiKey=YOUR_KEY to the ignored local.properties file, then rebuild.");
                        return;
                    }
                    bitmap = frame.toBitmap(1024);
                    callback.onSuccess(send(bitmap));
                } catch (SocketTimeoutException e) {
                    callback.onFailure("Gemini timed out. Check your connection and try again.");
                } catch (ApiException e) {
                    callback.onFailure(e.getMessage());
                } catch (Exception e) {
                    callback.onFailure("Could not connect to Gemini. Check your internet connection and try again.");
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            });
        } catch (RejectedExecutionException e) {
            callback.onFailure("Scene analysis is unavailable right now. Please try again.");
        }
    }

    private static final class RgbaFrame {
        private final byte[] rgba;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int rowStride;
        private final int pixelStride;
        private final int rotation;

        RgbaFrame(byte[] rgba, int sourceWidth, int sourceHeight,
                  int rowStride, int pixelStride, int rotation) {
            this.rgba = rgba;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
            this.rotation = ((rotation % 360) + 360) % 360;
        }

        Bitmap toBitmap(int maxDimension) {
            int uprightWidth = rotation == 90 || rotation == 270 ? sourceHeight : sourceWidth;
            int uprightHeight = rotation == 90 || rotation == 270 ? sourceWidth : sourceHeight;
            float resize = Math.min(1f, maxDimension / (float) Math.max(uprightWidth, uprightHeight));
            int outputWidth = Math.max(1, Math.round(uprightWidth * resize));
            int outputHeight = Math.max(1, Math.round(uprightHeight * resize));
            float scaleX = outputWidth / (float) uprightWidth;
            float scaleY = outputHeight / (float) uprightHeight;
            int[] pixels = new int[outputWidth * outputHeight];
            for (int y = 0; y < outputHeight; y++) {
                int uy = Math.min(uprightHeight - 1, Math.round((y + 0.5f) / scaleY));
                for (int x = 0; x < outputWidth; x++) {
                    int ux = Math.min(uprightWidth - 1, Math.round((x + 0.5f) / scaleX));
                    int sx;
                    int sy;
                    if (rotation == 90) {
                        sx = uy;
                        sy = sourceHeight - 1 - ux;
                    } else if (rotation == 180) {
                        sx = sourceWidth - 1 - ux;
                        sy = sourceHeight - 1 - uy;
                    } else if (rotation == 270) {
                        sx = sourceWidth - 1 - uy;
                        sy = ux;
                    } else {
                        sx = ux;
                        sy = uy;
                    }
                    sx = Math.max(0, Math.min(sourceWidth - 1, sx));
                    sy = Math.max(0, Math.min(sourceHeight - 1, sy));
                    int offset = sy * rowStride + sx * pixelStride;
                    int red = rgba[offset] & 0xff;
                    int green = rgba[offset + 1] & 0xff;
                    int blue = rgba[offset + 2] & 0xff;
                    pixels[y * outputWidth + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
                }
            }
            return Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        }
    }

    private String send(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)) {
            throw new IOException("Could not encode camera image");
        }
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Analysis canceled");
        String imageData = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
        JSONObject imagePart = new JSONObject()
                .put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageData));
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", PROMPT))
                .put(imagePart);
        JSONObject request = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 120));

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        activeConnection = connection;
        try {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Analysis canceled");
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(25_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseText = responseStream == null ? "" : readLimited(responseStream);
            if (responseCode < 200 || responseCode >= 300) {
                throw new ApiException(errorForStatus(responseCode));
            }
            try {
                return extractDescription(responseText);
            } catch (org.json.JSONException e) {
                throw new ApiException("Gemini returned an unreadable response. Please try again.");
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Gemini response was too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String extractDescription(String responseText) throws Exception {
        JSONObject response = new JSONObject(responseText);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new ApiException("Gemini could not describe this image. Try another view.");
        }
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) throw new ApiException("Gemini returned no scene description. Please try again.");
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            String text = parts.getJSONObject(i).optString("text", "").trim();
            if (!text.isEmpty()) {
                if (description.length() > 0) description.append(' ');
                description.append(text);
            }
        }
        String result = description.toString().replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) throw new ApiException("Gemini returned no scene description. Please try again.");
        if (result.length() > 600) result = result.substring(0, 597).trim() + "…";
        return result;
    }

    private static String errorForStatus(int status) {
        if (status == 400 || status == 401 || status == 403) {
            return "Gemini rejected the request or API key. Check the key and Gemini API access.";
        }
        if (status == 429) return "Gemini's request limit was reached. Please try again later.";
        if (status >= 500) return "Gemini is temporarily unavailable. Please try again later.";
        return "Gemini request failed (HTTP " + status + "). Please try again.";
    }

    @Override public void close() {
        closed = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
    }

    interface Callback {
        void onSuccess(String description);
        void onFailure(String message);
    }

    private static final class ApiException extends IOException {
        ApiException(String message) { super(message); }
    }
}
