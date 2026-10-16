package com.example.drishtijava.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Reusable CPU embedder for the bundled float MobileFaceNet model. */
public final class FaceEmbedder implements AutoCloseable {
    public static final String MODEL_ASSET = "mobilefacenet.tflite";
    private static final String TAG = "FaceEmbedder";
    private final Interpreter interpreter;
    private final int embeddingSize;
    private final float[][][][] input = new float[1][FaceAligner.INPUT_SIZE][FaceAligner.INPUT_SIZE][3];
    private final float[][] output;

    public FaceEmbedder(Context context) throws Exception {
        interpreter = new Interpreter(mapAsset(context, MODEL_ASSET), new Interpreter.Options().setNumThreads(2));
        Tensor inputTensor = interpreter.getInputTensor(0);
        Tensor outputTensor = interpreter.getOutputTensor(0);
        int[] inputShape = inputTensor.shape();
        int[] outputShape = outputTensor.shape();
        if (inputTensor.dataType() != DataType.FLOAT32 || inputShape.length != 4
                || inputShape[0] != 1 || inputShape[1] != 112 || inputShape[2] != 112 || inputShape[3] != 3) {
            interpreter.close();
            throw new IllegalArgumentException("Expected face model input [1,112,112,3] float32; got "
                    + java.util.Arrays.toString(inputShape) + " " + inputTensor.dataType());
        }
        if (outputTensor.dataType() != DataType.FLOAT32 || outputShape.length != 2 || outputShape[0] != 1
                || outputShape[1] < 32) {
            interpreter.close();
            throw new IllegalArgumentException("Expected a float face embedding output; got "
                    + java.util.Arrays.toString(outputShape) + " " + outputTensor.dataType());
        }
        embeddingSize = outputShape[1];
        output = new float[1][embeddingSize];
        Log.i(TAG, "Loaded face embedding model with " + embeddingSize + " output values");
    }

    public synchronized float[] embed(Bitmap alignedFace) {
        if (alignedFace == null || alignedFace.getWidth() != 112 || alignedFace.getHeight() != 112) {
            throw new IllegalArgumentException("Face input must be 112 by 112 pixels");
        }
        int[] pixels = new int[112 * 112];
        alignedFace.getPixels(pixels, 0, 112, 0, 0, 112, 112);
        int index = 0;
        for (int y = 0; y < 112; y++) {
            for (int x = 0; x < 112; x++) {
                int pixel = pixels[index++];
                input[0][y][x][0] = ((pixel >> 16) & 0xff) / 128.0f - 1.0f;
                input[0][y][x][1] = ((pixel >> 8) & 0xff) / 128.0f - 1.0f;
                input[0][y][x][2] = (pixel & 0xff) / 128.0f - 1.0f;
            }
        }
        interpreter.run(input, output);
        return FaceMath.normalized(output[0]);
    }

    public int getEmbeddingSize() { return embeddingSize; }

    private static MappedByteBuffer mapAsset(Context context, String asset) throws Exception {
        try (android.content.res.AssetFileDescriptor descriptor = context.getAssets().openFd(asset);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getDeclaredLength());
        }
    }

    @Override public void close() { interpreter.close(); }
}
