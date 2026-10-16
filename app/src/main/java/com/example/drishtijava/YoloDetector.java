package com.example.drishtijava;

import android.content.Context;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class YoloDetector implements AutoCloseable {
    public static final float CONFIDENCE_THRESHOLD = 0.20f;
    /** Lower cutoff to retain small, distant, angled, and partly occluded people. */
    public static final float PERSON_CONFIDENCE_THRESHOLD = 0.10f;
    public static final int MAX_DETECTIONS = 30;
    private static final String TAG = "DrishtiDetector";
    private static final int MODEL_SIZE = 640;
    private static final float NMS_IOU_THRESHOLD = 0.45f;
    private static final float PERSON_NMS_IOU_THRESHOLD = 0.55f;
    private final Interpreter interpreter;
    private final GpuDelegate gpuDelegate;
    private final List<String> labels;
    private final Tensor inputTensor;
    private final Tensor outputTensor;
    private final int[] inputShape;
    private final int[] outputShape;
    private final boolean inputNchw;
    private final boolean outputRowsLast;
    private final boolean endToEnd;
    private boolean normalizedCoordinates;
    private boolean coordinateFormatChecked;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final FloatBuffer outputFloats;
    private final float[] classScoreScratch;
    private final int[] classIdScratch;
    private final int inputChannels;
    private final int inputHeight;
    private final int inputWidth;
    private final int modelClassCount;
    private final int outputElements;
    private final float inputScale;
    private final int inputZeroPoint;
    private final float outputScale;
    private final int outputZeroPoint;
    private boolean loggedRows;
    private long preprocessMs;
    private long inferenceMs;
    private long postprocessMs;

    public YoloDetector(Context context) throws Exception {
        this(context, "yolo26n.tflite", "labels.txt");
    }

    public YoloDetector(Context context, String modelAssetName, String labelsAssetName) throws Exception {
        labels = readLabels(context, labelsAssetName);
        MappedByteBuffer model = mapAsset(context, modelAssetName);
        Interpreter createdInterpreter;
        GpuDelegate createdDelegate = null;
        try {
            CompatibilityList compatibility = new CompatibilityList();
            if (compatibility.isDelegateSupportedOnThisDevice()) {
                GpuDelegate.Options gpuOptions = compatibility.getBestOptionsForThisDevice();
                GpuDelegate candidate = new GpuDelegate(gpuOptions);
                try {
                    Interpreter.Options interpreterOptions = new Interpreter.Options()
                            .setNumThreads(4)
                            .addDelegate(candidate);
                    createdInterpreter = new Interpreter(model, interpreterOptions);
                    createdDelegate = candidate;
                    Log.d(TAG, "Execution delegate: GPU");
                } catch (RuntimeException | LinkageError gpuError) {
                    candidate.close();
                    Log.w(TAG, "GPU delegate initialization failed; using CPU", gpuError);
                    createdInterpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(4));
                }
            } else {
                Log.d(TAG, "GPU delegate unsupported; using CPU");
                createdInterpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(4));
            }
        } catch (RuntimeException | LinkageError gpuError) {
            Log.w(TAG, "GPU delegate unavailable; using CPU", gpuError);
            createdInterpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(4));
        }
        interpreter = createdInterpreter;
        gpuDelegate = createdDelegate;
        inputTensor = interpreter.getInputTensor(0);
        outputTensor = interpreter.getOutputTensor(0);
        inputShape = inputTensor.shape();
        outputShape = outputTensor.shape();
        Log.d(TAG, "Input tensor shape=" + java.util.Arrays.toString(inputShape)
                + " type=" + inputTensor.dataType());
        Log.d(TAG, "Output tensor shape=" + java.util.Arrays.toString(outputShape)
                + " type=" + outputTensor.dataType());
        if (inputShape.length != 4 || inputShape[0] != 1) {
            throw new IllegalArgumentException("Expected a batch-one 4D image input");
        }
        inputNchw = inputShape[1] == 3;
        inputChannels = inputNchw ? inputShape[1] : inputShape[3];
        inputHeight = inputNchw ? inputShape[2] : inputShape[1];
        inputWidth = inputNchw ? inputShape[3] : inputShape[2];
        if (inputChannels != 3 || inputHeight != MODEL_SIZE || inputWidth != MODEL_SIZE) {
            throw new IllegalArgumentException("Expected RGB 640x640 input; received "
                    + java.util.Arrays.toString(inputShape));
        }
        if (outputShape.length != 3 || outputShape[0] != 1) {
            throw new IllegalArgumentException("Expected a batch-one 3D output tensor");
        }
        boolean parsedEndToEnd;
        boolean parsedRowsLast;
        int parsedClassCount;
        if (outputShape[2] == 6) {
            parsedEndToEnd = true;
            parsedRowsLast = true;
            parsedClassCount = labels.size();
        } else if (outputShape[1] == 6) {
            parsedEndToEnd = true;
            parsedRowsLast = false;
            parsedClassCount = labels.size();
        } else if (outputShape[2] > 4 && outputShape[2] < outputShape[1]) {
            parsedEndToEnd = false;
            parsedRowsLast = true;
            parsedClassCount = outputShape[2] - 4;
        } else if (outputShape[1] > 4 && outputShape[1] < outputShape[2]) {
            parsedEndToEnd = false;
            parsedRowsLast = false;
            parsedClassCount = outputShape[1] - 4;
        } else {
            throw new IllegalArgumentException("Unsupported detection output "
                    + java.util.Arrays.toString(outputShape));
        }
        endToEnd = parsedEndToEnd;
        outputRowsLast = parsedRowsLast;
        modelClassCount = parsedClassCount;
        if (!endToEnd && modelClassCount != labels.size()) {
            throw new IllegalArgumentException("Model outputs " + modelClassCount
                    + " classes but labels.txt contains " + labels.size()
                    + ". Install the matching model and labels together.");
        }
        outputElements = outputShape[1] * outputShape[2];
        inputScale = inputTensor.quantizationParams().getScale();
        inputZeroPoint = inputTensor.quantizationParams().getZeroPoint();
        outputScale = outputTensor.quantizationParams().getScale();
        outputZeroPoint = outputTensor.quantizationParams().getZeroPoint();
        inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder());
        outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder());
        outputFloats = outputTensor.dataType() == DataType.FLOAT32 ? outputBuffer.asFloatBuffer() : null;
        if (!endToEnd) {
            int rows = outputRowsLast ? outputShape[1] : outputShape[2];
            classScoreScratch = new float[rows];
            classIdScratch = new int[rows];
        } else {
            classScoreScratch = null;
            classIdScratch = null;
        }
        interpreter.allocateTensors();
        // Output buffers contain zeros until the first interpreter.run(). Default to pixel
        // coordinates and identify normalized exports from the first confident prediction.
        normalizedCoordinates = false;
        coordinateFormatChecked = false;
        Log.d(TAG, "Output coordinate format will be checked on first confident prediction; parser="
                + (endToEnd ? "end-to-end" : "raw-" + (modelClassCount + 4) + "+NMS")
                + ", classes=" + modelClassCount);
    }

    public List<Detection> detect(ByteBuffer rgba, int rowStride, int pixelStride,
                                  int sourceWidth, int sourceHeight, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        int rotatedWidth = (rotation == 90 || rotation == 270) ? sourceHeight : sourceWidth;
        int rotatedHeight = (rotation == 90 || rotation == 270) ? sourceWidth : sourceHeight;
        float scale = Math.min((float) MODEL_SIZE / rotatedWidth, (float) MODEL_SIZE / rotatedHeight);
        float padX = (MODEL_SIZE - rotatedWidth * scale) * 0.5f;
        float padY = (MODEL_SIZE - rotatedHeight * scale) * 0.5f;
        long stageStart = System.nanoTime();
        preprocess(rgba, rowStride, pixelStride, sourceWidth, sourceHeight,
                rotatedWidth, rotatedHeight, rotation, scale, padX, padY);
        long afterPreprocess = System.nanoTime();
        outputBuffer.rewind();
        interpreter.run(inputBuffer, outputBuffer);
        long afterInference = System.nanoTime();
        detectCoordinateFormatFromOutput();
        List<Detection> detections = endToEnd
                ? parseEndToEnd(rotatedWidth, rotatedHeight, scale, padX, padY)
                : parseRaw(rotatedWidth, rotatedHeight, scale, padX, padY);
        long afterPostprocess = System.nanoTime();
        preprocessMs = TimeUnit.NANOSECONDS.toMillis(afterPreprocess - stageStart);
        inferenceMs = TimeUnit.NANOSECONDS.toMillis(afterInference - afterPreprocess);
        postprocessMs = TimeUnit.NANOSECONDS.toMillis(afterPostprocess - afterInference);
        return detections;
    }

    public long getPreprocessMs() { return preprocessMs; }
    public long getInferenceMs() { return inferenceMs; }
    public long getPostprocessMs() { return postprocessMs; }

    private void preprocess(ByteBuffer rgba, int rowStride, int pixelStride, int sourceWidth,
                            int sourceHeight, int rotatedWidth, int rotatedHeight, int rotation,
                            float scale, float padX, float padY) {
        inputBuffer.clear();
        int inputBytesPerElement = inputTensor.dataType() == DataType.FLOAT32 ? 4 : 1;
        for (int y = 0; y < MODEL_SIZE; y++) {
            for (int x = 0; x < MODEL_SIZE; x++) {
                float rx = (x - padX) / scale;
                float ry = (y - padY) / scale;
                boolean inside = rx >= 0 && ry >= 0 && rx < rotatedWidth && ry < rotatedHeight;
                int red = 114;
                int green = 114;
                int blue = 114;
                if (inside) {
                    int ix = Math.min(rotatedWidth - 1, Math.max(0, Math.round(rx)));
                    int iy = Math.min(rotatedHeight - 1, Math.max(0, Math.round(ry)));
                    int sourceX;
                    int sourceY;
                    if (rotation == 90) {
                        sourceX = iy;
                        sourceY = sourceHeight - 1 - ix;
                    } else if (rotation == 180) {
                        sourceX = sourceWidth - 1 - ix;
                        sourceY = sourceHeight - 1 - iy;
                    } else if (rotation == 270) {
                        sourceX = sourceWidth - 1 - iy;
                        sourceY = ix;
                    } else {
                        sourceX = ix;
                        sourceY = iy;
                    }
                    int sourceOffset = sourceY * rowStride + sourceX * pixelStride;
                    red = rgba.get(sourceOffset) & 0xff;
                    green = rgba.get(sourceOffset + 1) & 0xff;
                    blue = rgba.get(sourceOffset + 2) & 0xff;
                }
                putInputValue(0, y, x, red / 255f, inputBytesPerElement);
                putInputValue(1, y, x, green / 255f, inputBytesPerElement);
                putInputValue(2, y, x, blue / 255f, inputBytesPerElement);
            }
        }
        inputBuffer.rewind();
    }

    private void putInputValue(int channel, int y, int x, float value, int bytesPerElement) {
        int index = inputNchw
                ? channel * inputHeight * inputWidth + y * inputWidth + x
                : (y * inputWidth + x) * inputChannels + channel;
        int byteIndex = index * bytesPerElement;
        if (inputTensor.dataType() == DataType.FLOAT32) {
            inputBuffer.putFloat(byteIndex, value);
        } else if (inputTensor.dataType() == DataType.UINT8) {
            int quantized = Math.max(0, Math.min(255,
                    Math.round(value / inputScale + inputZeroPoint)));
            inputBuffer.put(byteIndex, (byte) quantized);
        } else if (inputTensor.dataType() == DataType.INT8) {
            int quantized = Math.max(-128, Math.min(127,
                    Math.round(value / inputScale + inputZeroPoint)));
            inputBuffer.put(byteIndex, (byte) quantized);
        } else {
            throw new IllegalArgumentException("Unsupported input type " + inputTensor.dataType());
        }
    }

    private void detectCoordinateFormatFromOutput() {
        if (coordinateFormatChecked) return;
        int rows = outputRowsLast ? outputShape[1] : outputShape[2];
        for (int row = 0; row < rows; row++) {
            float score = 0f;
            int classId = -1;
            if (endToEnd) {
                score = valueAt(row, 4, outputRowsLast);
                classId = Math.round(valueAt(row, 5, outputRowsLast));
            } else {
                for (int c = 0; c < modelClassCount; c++) {
                    float candidate = valueAt(row, 4 + c, outputRowsLast);
                    if (candidate > score) {
                        score = candidate;
                        classId = c;
                    }
                }
            }
            if (classId < 0 || classId >= labels.size()
                    || score < confidenceThresholdForClass(classId)) continue;

            float maxCoordinate = 0f;
            for (int coordinate = 0; coordinate < 4; coordinate++) {
                maxCoordinate = Math.max(maxCoordinate,
                        Math.abs(valueAt(row, coordinate, outputRowsLast)));
            }
            normalizedCoordinates = maxCoordinate <= 1.5f;
            coordinateFormatChecked = true;
            Log.d(TAG, "Output boxes use " + (normalizedCoordinates ? "normalized" : "640-pixel")
                    + " coordinates (sample max=" + maxCoordinate + ")");
            return;
        }
    }

    private List<Detection> parseEndToEnd(int width, int height, float scale, float padX, float padY) {
        List<Detection> result = new ArrayList<>();
        int rows = outputRowsLast ? outputShape[1] : outputShape[2];
        for (int row = 0; row < rows && result.size() < MAX_DETECTIONS; row++) {
            logRowOnce(row, 6);
            float score = valueAt(row, 4, outputRowsLast);
            int classId = Math.round(valueAt(row, 5, outputRowsLast));
            if (classId < 0 || classId >= labels.size()
                    || score < confidenceThresholdForClass(classId)) continue;
            float x1 = coordinate(valueAt(row, 0, outputRowsLast)) * (normalizedCoordinates ? MODEL_SIZE : 1f);
            float y1 = coordinate(valueAt(row, 1, outputRowsLast)) * (normalizedCoordinates ? MODEL_SIZE : 1f);
            float x2 = coordinate(valueAt(row, 2, outputRowsLast)) * (normalizedCoordinates ? MODEL_SIZE : 1f);
            float y2 = coordinate(valueAt(row, 3, outputRowsLast)) * (normalizedCoordinates ? MODEL_SIZE : 1f);
            addDetection(result, x1, y1, x2, y2, score, classId, width, height, scale, padX, padY);
        }
        return result;
    }

    private List<Detection> parseRaw(int width, int height, float scale, float padX, float padY) {
        List<Candidate> candidates = new ArrayList<>();
        int rows = outputRowsLast ? outputShape[1] : outputShape[2];
        if (outputRowsLast) {
            for (int row = 0; row < rows; row++) {
                logRowOnce(row, 5);
                float score = 0f;
                int classId = -1;
                for (int c = 0; c < modelClassCount; c++) {
                    float candidate = valueAt(row, 4 + c, true);
                    if (candidate > score) { score = candidate; classId = c; }
                }
                addRawCandidate(candidates, row, classId, score, width, height, scale, padX, padY);
            }
        } else {
            java.util.Arrays.fill(classScoreScratch, 0f);
            java.util.Arrays.fill(classIdScratch, -1);
            for (int c = 0; c < modelClassCount; c++) {
                int baseIndex = (4 + c) * rows;
                for (int row = 0; row < rows; row++) {
                    float candidate = outputTensor.dataType() == DataType.FLOAT32
                            ? outputFloats.get(baseIndex + row)
                            : valueAt(row, 4 + c, false);
                    if (candidate > classScoreScratch[row]) {
                        classScoreScratch[row] = candidate;
                        classIdScratch[row] = c;
                    }
                }
            }
            for (int row = 0; row < rows; row++) {
                logRowOnce(row, 5);
                addRawCandidate(candidates, row, classIdScratch[row], classScoreScratch[row],
                        width, height, scale, padX, padY);
            }
        }
        Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
        List<Detection> result = new ArrayList<>();
        boolean[] removed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size() && result.size() < MAX_DETECTIONS; i++) {
            if (removed[i]) continue;
            Candidate a = candidates.get(i);
            addDetection(result, a.x1, a.y1, a.x2, a.y2, a.score, a.classId,
                    width, height, scale, padX, padY);
            float nmsThreshold = "person".equalsIgnoreCase(labels.get(a.classId))
                    ? PERSON_NMS_IOU_THRESHOLD : NMS_IOU_THRESHOLD;
            for (int j = i + 1; j < candidates.size(); j++) {
                Candidate b = candidates.get(j);
                if (!removed[j] && a.classId == b.classId && iou(a, b) > nmsThreshold) removed[j] = true;
            }
        }
        return result;
    }

    private void addRawCandidate(List<Candidate> candidates, int row, int classId, float score,
                                 int width, int height, float scale, float padX, float padY) {
        if (classId < 0 || classId >= labels.size()
                || score < confidenceThresholdForClass(classId)) return;
        float coordinateScale = normalizedCoordinates ? MODEL_SIZE : 1f;
        float cx = valueAt(row, 0, outputRowsLast) * coordinateScale;
        float cy = valueAt(row, 1, outputRowsLast) * coordinateScale;
        float w = valueAt(row, 2, outputRowsLast) * coordinateScale;
        float h = valueAt(row, 3, outputRowsLast) * coordinateScale;
        candidates.add(new Candidate(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score, classId));
    }

    private void addDetection(List<Detection> result, float x1, float y1, float x2, float y2,
                              float score, int classId, int width, int height,
                              float scale, float padX, float padY) {
        float left = clamp((x1 - padX) / scale, 0, width);
        float top = clamp((y1 - padY) / scale, 0, height);
        float right = clamp((x2 - padX) / scale, 0, width);
        float bottom = clamp((y2 - padY) / scale, 0, height);
        if (right > left && bottom > top) {
            result.add(new Detection(new RectF(left, top, right, bottom), classId, labels.get(classId), score));
        }
    }

    private float valueAt(int row, int column, boolean rowsLast) {
        int index = rowsLast ? row * outputShape[2] + column : column * outputShape[2] + row;
        if (outputTensor.dataType() == DataType.FLOAT32) return outputFloats.get(index);
        if (outputTensor.dataType() == DataType.UINT8) return ((outputBuffer.get(index) & 0xff) - outputZeroPoint) * outputScale;
        if (outputTensor.dataType() == DataType.INT8) return (outputBuffer.get(index) - outputZeroPoint) * outputScale;
        throw new IllegalArgumentException("Unsupported output type " + outputTensor.dataType());
    }

    private float confidenceThresholdForClass(int classId) {
        return "person".equalsIgnoreCase(labels.get(classId))
                ? PERSON_CONFIDENCE_THRESHOLD : CONFIDENCE_THRESHOLD;
    }

    private void logRowOnce(int row, int fields) {
        if (loggedRows || row >= 3) return;
        StringBuilder line = new StringBuilder("Output row ").append(row).append(":");
        for (int c = 0; c < fields; c++) line.append(' ').append(valueAt(row, c, outputRowsLast));
        Log.d(TAG, line.toString());
        if (row == 2 || row == Math.min(2, (outputRowsLast ? outputShape[1] : outputShape[2]) - 1)) loggedRows = true;
    }

    private float coordinate(float value) { return value; }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static float iou(Candidate a, Candidate b) {
        float w = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1));
        float h = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
        float intersection = w * h;
        float union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    private static List<String> readLabels(Context context, String labelsAssetName) throws Exception {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(labelsAssetName)))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) result.add(line.trim());
        }
        if (result.isEmpty()) throw new IllegalArgumentException("labels.txt must contain at least one class");
        return result;
    }

    private static MappedByteBuffer mapAsset(Context context, String name) throws Exception {
        try (android.content.res.AssetFileDescriptor descriptor = context.getAssets().openFd(name);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getDeclaredLength());
        }
    }

    @Override public void close() {
        interpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }

    private static final class Candidate {
        final float x1, y1, x2, y2, score; final int classId;
        Candidate(float x1, float y1, float x2, float y2, float score, int classId) {
            this.x1=x1; this.y1=y1; this.x2=x2; this.y2=y2; this.score=score; this.classId=classId;
        }
    }
}
