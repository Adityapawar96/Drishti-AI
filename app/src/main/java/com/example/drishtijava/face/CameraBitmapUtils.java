package com.example.drishtijava.face;

import android.graphics.Bitmap;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/** Copies a stride-aware RGBA CameraX frame into a smaller upright bitmap. */
public final class CameraBitmapUtils {
    private CameraBitmapUtils() { }

    public static FrameBitmap fromRgba(ImageProxy image, int maxDimension) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer bytes = plane.getBuffer().duplicate();
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int rotation = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
        int uprightWidth = rotation == 90 || rotation == 270 ? sourceHeight : sourceWidth;
        int uprightHeight = rotation == 90 || rotation == 270 ? sourceWidth : sourceHeight;
        float resize = Math.min(1f, maxDimension / (float) Math.max(uprightWidth, uprightHeight));
        int outWidth = Math.max(1, Math.round(uprightWidth * resize));
        int outHeight = Math.max(1, Math.round(uprightHeight * resize));
        float scaleX = outWidth / (float) uprightWidth;
        float scaleY = outHeight / (float) uprightHeight;
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] pixels = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            int uy = Math.min(uprightHeight - 1, Math.round((y + 0.5f) / scaleY));
            for (int x = 0; x < outWidth; x++) {
                int ux = Math.min(uprightWidth - 1, Math.round((x + 0.5f) / scaleX));
                int sx, sy;
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
                int red = bytes.get(offset) & 0xff;
                int green = bytes.get(offset + 1) & 0xff;
                int blue = bytes.get(offset + 2) & 0xff;
                pixels[y * outWidth + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return new FrameBitmap(Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888), scaleX, scaleY);
    }

    public static final class FrameBitmap {
        public final Bitmap bitmap;
        public final float scaleX;
        public final float scaleY;

        FrameBitmap(Bitmap bitmap, float scaleX, float scaleY) {
            this.bitmap = bitmap;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }
}
