package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.util.Log;
import lombok.Getter;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Utility class for OpenCV operations.
 * Provides methods for initializing OpenCV, applying perspective transformations,
 * and detecting document corners in images.
 */
public class OpenCVUtils {
    private static final String TAG = "OpenCVUtils";

    @Getter
    private static boolean isInitialized = false;

    private static boolean USE_SAFE_MODE = true;
    private static boolean USE_ADAPTIVE_THRESHOLD = false;

    private static final boolean USE_DEBUG_IMAGES = false;

    /**
     * Initializes OpenCV by loading the native library.
     * This method should be called before using any OpenCV functionality.
     *
     * @param context The application context.
     * @return true if OpenCV was initialized successfully, false otherwise.
     */
    public static boolean init(Context context) {
        if (isInitialized) return true;

        try {
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV loaded manually via System.loadLibrary");
            isInitialized = true;
            configureSafeMode();

        } catch (Throwable t) {
            Log.e(TAG, "OpenCV init error", t);
        }

        return isInitialized;
    }

    /**
     * Configures the safe mode based on device specifications.
     * Determines whether to use safe mode and adaptive thresholding based on device capabilities.
     */
    private static void configureSafeMode() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String device = Build.DEVICE.toLowerCase();
        int sdk = Build.VERSION.SDK_INT;

        boolean isHighEnd = sdk >= 29 && !manufacturer.contains("mediatek") && !manufacturer.contains("spreadtrum") && !device.contains("generic") && !model.contains("emulator") && !device.contains("x86") && !device.contains("x86_64") && (manufacturer.contains("google") || manufacturer.contains("samsung") || manufacturer.contains("xiaomi"));

        USE_SAFE_MODE = !isHighEnd;
        USE_ADAPTIVE_THRESHOLD = isHighEnd;

        Log.i(TAG, "Safe mode = " + USE_SAFE_MODE + ", AdaptiveThreshold = " + USE_ADAPTIVE_THRESHOLD);
    }

    /**
     * Applies a perspective warp to the input image using the specified source points.
     * This method is a safe version that handles errors and invalid inputs gracefully.
     *
     * @param input      The input image as a Mat object.
     * @param srcPoints  The source points defining the quadrilateral to warp.
     * @param targetSize The size of the output image.
     * @return The warped image as a Mat object, or the original input if an error occurs.
     */
    public static Mat warpPerspectiveSafe(Mat input, Point[] srcPoints, Size targetSize) {
        if (input == null || input.empty() || srcPoints == null || srcPoints.length != 4) {
            Log.e(TAG, "Invalid input or source points");
            return input;
        }

        try {
            Point[] dstPoints = new Point[]{new Point(0, 0), new Point(targetSize.width - 1, 0), new Point(targetSize.width - 1, targetSize.height - 1), new Point(0, targetSize.height - 1)};

            Mat srcMat = new Mat(4, 1, CvType.CV_32FC2);
            Mat dstMat = new Mat(4, 1, CvType.CV_32FC2);
            for (int i = 0; i < 4; i++) {
                srcMat.put(i, 0, srcPoints[i].x, srcPoints[i].y);
                dstMat.put(i, 0, dstPoints[i].x, dstPoints[i].y);
            }

            Mat transform = Imgproc.getPerspectiveTransform(srcMat, dstMat);
            Mat output = new Mat();
            Imgproc.warpPerspective(input, output, transform, targetSize);

            srcMat.release();
            dstMat.release();
            transform.release();

            return output;
        } catch (Throwable t) {
            Log.e(TAG, "warpPerspective failed", t);
            return input;
        }
    }

    /**
     * Applies a perspective warp to the input bitmap using the specified corners.
     * This method uses Android's Matrix class for the transformation.
     *
     * @param srcBitmap The source bitmap to be warped.
     * @param corners   The corners of the quadrilateral to warp.
     * @return A new bitmap with the perspective warp applied.
     */
    public static Bitmap warpPerspectiveWithMatrix(Bitmap srcBitmap, Point[] corners) {
        if (corners == null || corners.length != 4) return srcBitmap;

        int width = srcBitmap.getWidth();
        int height = srcBitmap.getHeight();

        float[] src = new float[]{(float) corners[0].x, (float) corners[0].y, (float) corners[1].x, (float) corners[1].y, (float) corners[2].x, (float) corners[2].y, (float) corners[3].x, (float) corners[3].y};

        float[] dst = new float[]{0, 0, width, 0, width, height, 0, height};

        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(src, 0, dst, 0, 4);

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(srcBitmap, matrix, paint);
        return output;
    }

    /**
     * Applies perspective correction to the input bitmap using the specified corners.
     * If safe mode is enabled, it falls back to using Android's Matrix for the warp.
     *
     * @param originalBitmap The original bitmap to be corrected.
     * @param corners        The corners of the quadrilateral to warp.
     * @return A new bitmap with the perspective correction applied.
     */
    public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
        Mat mat = new Mat();
        Utils.bitmapToMat(originalBitmap, mat);
        if (!isSafeMode()) {
            Log.d(TAG, "Using OpenCV warpPerspective");
            Size targetSize = new Size(originalBitmap.getWidth(), originalBitmap.getHeight());
            Mat warped = warpPerspectiveSafe(mat, corners, targetSize);
            Bitmap output = Bitmap.createBitmap((int) targetSize.width, (int) targetSize.height, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(warped, output);
            warped.release();
            mat.release();
            return output;
        } else {
            Log.d(TAG, "Using Android Matrix warp fallback");
            mat.release();
            return warpPerspectiveWithMatrix(originalBitmap, corners);
        }
    }

    /**
     * Checks if the utility is running in safe mode.
     * Safe mode is enabled for devices that are not considered high-end.
     *
     * @return true if safe mode is enabled, false otherwise.
     */
    public static boolean isSafeMode() {
        return USE_SAFE_MODE;
    }

    /**
     * Checks if adaptive thresholding is being used.
     * Adaptive thresholding is enabled for high-end devices.
     *
     * @return true if adaptive thresholding is enabled, false otherwise.
     */
    public static boolean isUsingAdaptiveThreshold() {
        return USE_ADAPTIVE_THRESHOLD;
    }

    /**
     * Detects the corners of a document in the given bitmap.
     * This method processes the image to find contours and returns the best matching quadrilateral.
     *
     * @param context The application context for saving debug images.
     * @param bitmap  The input bitmap image.
     * @return An array of Points representing the corners of the detected document, or a fallback rectangle if no suitable contour is found.
     */
    public static Point[] detectDocumentCorners(Context context, Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCorners()");

        Mat rgba = new Mat();
        Utils.bitmapToMat(bitmap, rgba);
        Log.d(TAG, "Converted bitmap to Mat: " + rgba.size());

        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

        Mat threshold = new Mat();
        Imgproc.threshold(gray, threshold, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

        saveDebugImage(context, threshold, "debug_threshold.png");

        Mat morph = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15));
        Imgproc.morphologyEx(threshold, morph, Imgproc.MORPH_CLOSE, kernel);

        saveDebugImage(context, morph, "debug_morph.png");

        Mat edges = new Mat();
        Imgproc.Canny(morph, edges, 50, 150);
        saveDebugImage(context, edges, "debug_edges.png");

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Mat debug = Mat.zeros(edges.size(), CvType.CV_8UC3);
        for (int i = 0; i < contours.size(); i++) {
            Imgproc.drawContours(debug, contours, i, new Scalar(0, 255, 0), 2);
        }
        saveDebugImage(context, debug, "debug_contours.png");

        double imgArea = rgba.width() * rgba.height();
        double maxArea = 0;
        Point[] bestQuad = null;

        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area < imgArea * 0.20) continue; // Mindestens 20% der Fläche

            MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.02, true);

            boolean isConvex = Imgproc.isContourConvex(new MatOfPoint(approx.toArray()));
            if (approx.total() == 4) {
                Point[] quad = approx.toArray();

                double w1 = Math.hypot(quad[0].x - quad[1].x, quad[0].y - quad[1].y);
                double w2 = Math.hypot(quad[2].x - quad[3].x, quad[2].y - quad[3].y);
                double h1 = Math.hypot(quad[1].x - quad[2].x, quad[1].y - quad[2].y);
                double h2 = Math.hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y);
                double avgWidth = (w1 + w2) / 2.0;
                double avgHeight = (h1 + h2) / 2.0;
                double aspectRatio = avgHeight / avgWidth;
                Log.i(TAG, "Contour: area=" + area + ", aspectRatio=" + String.format("%.2f", aspectRatio) + ", convex=" + isConvex + ", approxPoints=" + approx.total());

                if (aspectRatio > 0.5 && aspectRatio < 2.5 && area > maxArea) {
                    maxArea = area;
                    bestQuad = sortPointsClockwise(quad);
                }
            }
        }

        if (bestQuad != null) {
            Log.i(TAG, "Document contour found");
            return bestQuad;
        }

        Log.w(TAG, "No suitable document contour found, returning fallback rectangle");
        return getFallbackRectangle(bitmap.getWidth(), bitmap.getHeight());
    }

    /**
     * Sorts the points in clockwise order starting from the top-left corner.
     * This is used to ensure the points are in a consistent order for perspective transformations.
     *
     * @param src The source points to be sorted.
     * @return An array of points sorted in clockwise order.
     */
    private static Point[] sortPointsClockwise(Point[] src) {
        // Sort the points to find the top-left, top-right, bottom-right, and bottom-left corners
        List<Point> sorted = Arrays.asList(src);
        sorted.sort(Comparator.comparingDouble(p -> p.x + p.y));
        Point topLeft = sorted.get(0);
        Point bottomRight = sorted.get(3);
        sorted.sort(Comparator.comparingDouble(p -> p.y - p.x));
        Point topRight = sorted.get(0);
        Point bottomLeft = sorted.get(3);
        return new Point[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    /**
     * Provides a fallback rectangle in case no suitable document contour is found.
     * This rectangle is positioned with a margin of 100 pixels from the edges of the image.
     *
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return An array of points representing the corners of the fallback rectangle.
     */
    private static Point[] getFallbackRectangle(int width, int height) {
        return new Point[]{new Point(100, 100), new Point(width - 100, 100), new Point(width - 100, height - 100), new Point(100, height - 100)};
    }

    /**
     * Saves a debug image to the device's external files directory.
     * This is useful for debugging purposes to visualize intermediate steps in the image processing pipeline.
     *
     * @param context  The application context for accessing the external files directory.
     * @param mat      The Mat object containing the image to be saved.
     * @param filename The name of the file to save the image as.
     */
    private static void saveDebugImage(Context context, Mat mat, String filename) {
        if (!USE_DEBUG_IMAGES) return;
        Bitmap debugBmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, debugBmp);
        File file = new File(context.getExternalFilesDir(null), filename);
        try (FileOutputStream out = new FileOutputStream(file)) {
            debugBmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            Log.i(TAG, "Saved debug image: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to save debug image", e);
        }
    }
}