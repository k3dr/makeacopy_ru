package de.schliweb.makeacopy.utils;

import ai.onnxruntime.*;
import android.content.Context;
import android.content.res.AssetManager;
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
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

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

    // ONNX model settings
    private static final String MODEL_ASSET_PATH = "docaligner/fastvit_sa24_h_e_bifpn_256_fp32.onnx";
    private static volatile OrtEnvironment ortEnv;
    private static volatile OrtSession ortSession;

    private OpenCVUtils() {
        // Utility class, no instances allowed
    }

    private static boolean isSafeMode() {
        return USE_SAFE_MODE;
    }

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
            configureSafeMode();
            initOnnxRuntime(context);
            isInitialized = true;
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV init error", t);
        }

        return isInitialized;
    }


    /**
     * Initializes the ONNX runtime for inference.
     * This method loads the ONNX model from the assets directory and creates an inference session.
     * @param context The application context.
     */
    private static void initOnnxRuntime(Context context) {
        if(ortSession != null) return;
        Log.i(TAG, "Initializing ONNX runtime");

        try {
            File modelFile = copyAssetToCache(context, MODEL_ASSET_PATH);
            if(ortEnv == null) {
                synchronized (OpenCVUtils.class) {
                    if(ortEnv == null) {
                        ortEnv = OrtEnvironment.getEnvironment();
                    }
                }
            }
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

                ortSession = ortEnv.createSession(modelFile.getAbsolutePath(), opts);
            }
            Log.i(TAG, "ONNX model loaded from " + modelFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ONNX model", e);
        }
    }

    /**
     * Copies an asset file to the application's cache directory.
     * This method is used to copy the ONNX model to the application's cache directory.
     *
     * @param context The application context.
     * @param assetPath The path of the asset file to be copied.
     * @return The file object representing the copied asset file.
     * @throws IOException if an error occurs while copying the asset file.
     */
    private static File copyAssetToCache(Context context, String assetPath) throws IOException {
        Log.i(TAG, "Copying asset " + assetPath + " to cache");
        AssetManager am = context.getAssets();
        File outFile = new File(context.getCacheDir(), new File(assetPath).getName());
        if (!outFile.exists()) {
            Log.i(TAG, "Asset " + assetPath + " not found in cache, copying...");
            try (InputStream is = am.open(assetPath);
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
        } else {
            Log.i(TAG, "Asset " + assetPath + " already exists in cache");
        }
        return outFile;
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
        boolean isEmulator = device.contains("emu") || model.contains("sdk") || model.contains("emulator") || model.contains("virtual") || manufacturer.contains("genymotion") || model.contains("generator");

        USE_SAFE_MODE = !isHighEnd || isEmulator;
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
    private static Mat warpPerspectiveSafe(Mat input, Point[] srcPoints, Size targetSize) {
        if (input == null || input.empty() || srcPoints == null || srcPoints.length != 4) {
            Log.e(TAG, "Invalid input or source points");
            return input;
        }

        Mat srcMat = new Mat(4, 1, CvType.CV_32FC2);
        Mat dstMat = new Mat(4, 1, CvType.CV_32FC2);
        Mat transform = new Mat();
        Mat output = new Mat();
        try {
            Point[] dstPoints = new Point[]{
                    new Point(0, 0),
                    new Point(targetSize.width - 1, 0),
                    new Point(targetSize.width - 1, targetSize.height - 1),
                    new Point(0, targetSize.height - 1)
            };

            for (int i = 0; i < 4; i++) {
                srcMat.put(i, 0, srcPoints[i].x, srcPoints[i].y);
                dstMat.put(i, 0, dstPoints[i].x, dstPoints[i].y);
            }

            transform = Imgproc.getPerspectiveTransform(srcMat, dstMat);
            Imgproc.warpPerspective(input, output, transform, targetSize);
            return output;
        } catch (Throwable t) {
            Log.e(TAG, "warpPerspective failed", t);
            release(output);
            return input;
        } finally {
            release(srcMat, dstMat, transform);
        }
    }

    /**
     * Applies perspective correction (with cleanup).
     */
    public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(originalBitmap, mat);
            if (!isSafeMode()) {
                Log.d(TAG, "Using OpenCV warpPerspective");
                Size targetSize = new Size(originalBitmap.getWidth(), originalBitmap.getHeight());
                Mat warped = warpPerspectiveSafe(mat, corners, targetSize);
                try {
                    Bitmap output = Bitmap.createBitmap((int) targetSize.width, (int) targetSize.height, Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(warped, output);
                    return output;
                } finally {
                    release(warped);
                }
            } else {
                Log.d(TAG, "Using Android Matrix warp fallback");
                return warpPerspectiveWithMatrix(originalBitmap, corners);
            }
        } finally {
            release(mat);
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
    private static Bitmap warpPerspectiveWithMatrix(Bitmap srcBitmap, Point[] corners) {
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
     * Preprocess: Bitmap RGBA → BGR Mat → NCHW float [0..1].
     */
    public static float[] fromBitmapBGR(Bitmap bitmap) {
        if (bitmap == null) throw new IllegalArgumentException("bitmap is null");
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(bitmap, mat);                // RGBA
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR);
            return toNCHW01_BGR(mat, 256, 256);
        } finally {
            release(mat);
        }
    }

    private static float[] toNCHW01_BGR(Mat bgr, int targetW, int targetH) {
        if (bgr.empty()) throw new IllegalArgumentException("input Mat is empty");

        Mat resized = new Mat();
        Mat floatImage = new Mat();
        List<Mat> channels = new ArrayList<>(3);
        try {
            Imgproc.resize(bgr, resized, new Size(targetW, targetH));
            resized.convertTo(floatImage, CvType.CV_32FC3, 1.0 / 255.0);

            Core.split(floatImage, channels); // B, G, R als CV_32F

            int H = targetH, W = targetW, C = 3;
            int HW = H * W;
            float[] nchw = new float[1 * C * H * W];

            for (int c = 0; c < C; c++) {
                float[] buf = new float[HW];
                channels.get(c).get(0, 0, buf);
                System.arraycopy(buf, 0, nchw, c * HW, HW);
            }
            return nchw;
        } finally {
            release(resized, floatImage);
            releaseAll(channels);
        }
    }

    /**
     * Run ONNX model, return raw output.
     */
    private static float[] runInferenceBgrNchw(float[] inputTensor) throws OrtException {
        if (ortEnv == null || ortSession == null) {
            Log.e(TAG, "ONNX Runtime not initialized. Call initOnnxRuntime(context) first.");
            throw new IllegalStateException("ONNX Runtime not initialized. Call initOnnxRuntime(context) first.");
        }

        String inputName = ortSession.getInputNames().iterator().next();
        long[] shape = new long[]{1, 3, 256, 256};

        long start = System.nanoTime();
        try (OnnxTensor input = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputTensor), shape);
             OrtSession.Result result = ortSession.run(Collections.singletonMap(inputName, input))) {

            long elapsedNs = System.nanoTime() - start;
            double elapsedMs = elapsedNs / 1_000_000.0;
            Log.i(TAG, String.format("Elapsed: %.3f ms", elapsedMs));

            OnnxValue out0 = result.get(0);
            if (!(out0 instanceof OnnxTensor)) {
                throw new RuntimeException("Unexpected output type: " + out0.getClass());
            }
            OnnxTensor ot = (OnnxTensor) out0;
            long[] outShape = ot.getInfo().getShape();
            Log.i(TAG, "ONNX output shape=" + Arrays.toString(outShape));

            FloatBuffer fb = ot.getFloatBuffer();
            float[] pred = new float[fb.remaining()];
            fb.get(pred);

            // Debug: show a few values
            if (pred.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(8, pred.length); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(pred[i]);
                }
                Log.i(TAG, "ONNX raw pred[0..7]=" + sb);
            }
            return pred;
        }
    }

    private static float[] detectModel(Bitmap bitmap) throws OrtException {
        float[] inputTensor = fromBitmapBGR(bitmap);
        return runInferenceBgrNchw(inputTensor);
    }

    private static Point[] detectDocumentCornersWithOnnx(Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCornersWithOnnx()");
        try {
            float[] pred = detectModel(bitmap);
            Point[] pts = predictionToPoints(pred, bitmap.getWidth(), bitmap.getHeight());
            if (pts != null) {
                Log.i(TAG, "ONNX corners OK: area=" + quadArea(pts) + ", corners=" + Arrays.toString(pts));
            } else {
                Log.w(TAG, "ONNX corners invalid → null");
            }
            return pts;
        } catch (Exception e) {
            Log.e(TAG, "ONNX inference failed", e);
            return null;
        }
    }

    private static boolean isFallback(Point[] p, int w, int h) {
        if (p == null || p.length != 4) return false;
        return (Math.round(p[0].x) == 100 && Math.round(p[0].y) == 100) &&
                (Math.round(p[1].x) == w - 100 && Math.round(p[1].y) == 100) &&
                (Math.round(p[2].x) == w - 100 && Math.round(p[2].y) == h - 100) &&
                (Math.round(p[3].x) == 100 && Math.round(p[3].y) == h - 100);
    }

    private static Point[] predictionToPoints(float[] pred, int outW, int outH) {
        if (pred == null || pred.length != 1 * 4 * 128 * 128) {
            Log.w(TAG, "predictionToPoints: unexpected pred length " + (pred == null ? -1 : pred.length));
            return null;
        }
        final int C = 4, H = 128, W = 128;
        // idx = c*H*W + y*W + x
        Point[] pts = new Point[C];

        for (int c = 0; c < C; c++) {
            int base = c * H * W;

            int maxIdx = base;
            float maxVal = -Float.MAX_VALUE;
            for (int i = 0; i < H * W; i++) {
                float v = pred[base + i];
                if (v > maxVal) {
                    maxVal = v;
                    maxIdx = base + i;
                }
            }

            final float MIN_PEAK = 1e-4f;
            if (maxVal < MIN_PEAK) {
                Log.w(TAG, "Heatmap peak too low for corner " + c + " → rejecting ONNX");
                return null;
            }

            int peak = maxIdx - base;
            int py = peak / W;
            int px = peak % W;

            int x0 = Math.max(0, px - 1), x1 = Math.min(W - 1, px + 1);
            int y0 = Math.max(0, py - 1), y1 = Math.min(H - 1, py + 1);
            double sumW = 0, sumX = 0, sumY = 0;
            for (int yy = y0; yy <= y1; yy++) {
                for (int xx = x0; xx <= x1; xx++) {
                    float wv = pred[base + yy * W + xx];
                    double w = Math.max(0.0, wv);
                    sumW += w;
                    sumX += w * xx;
                    sumY += w * yy;
                }
            }
            double fx = (sumW > 0) ? (sumX / sumW) : px;
            double fy = (sumW > 0) ? (sumY / sumW) : py;

            double scaleX = (double) outW / W;
            double scaleY = (double) outH / H;
            double bx = fx * scaleX;
            double by = fy * scaleY;

            bx = Math.max(0, Math.min(bx, outW - 1));
            by = Math.max(0, Math.min(by, outH - 1));

            pts[c] = new Point(bx, by);
        }

        pts = sortPointsClockwise(pts);
        double area = quadArea(pts);
        double imgArea = (double) outW * outH;
        if (area < 0.05 * imgArea) {
            Log.w(TAG, String.format("predictionToPoints: area too small (%.2f%%).", 100.0 * area / imgArea));
            return null;
        }

        final double minSide = 0.02 * Math.min(outW, outH);
        for (int i = 0; i < 4; i++) {
            Point a = pts[i], b = pts[(i + 1) % 4];
            if (Math.hypot(a.x - b.x, a.y - b.y) < minSide) {
                Log.w(TAG, "predictionToPoints: side too small.");
                return null;
            }
        }
        return pts;
    }

    private static double quadArea(Point[] q) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            Point a = q[i], b = q[(i + 1) % 4];
            area += (a.x * b.y - b.x * a.y);
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * Detect corners with OpenCV; everything cleaned up via try/finally.
     */
    private static Point[] detectDocumentCornersWithOpenCV(Context context, Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCornersWithOpenCV()");

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat threshold = new Mat();
        Mat morph = new Mat();
        Mat kernel = new Mat();
        Mat edges = new Mat();
        Mat edgesCopy = new Mat();
        Mat hierarchy = new Mat();
        Mat debug = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

            Imgproc.threshold(gray, threshold, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            saveDebugImage(context, threshold, "debug_threshold.png");

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15));
            Imgproc.morphologyEx(threshold, morph, Imgproc.MORPH_CLOSE, kernel);
            saveDebugImage(context, morph, "debug_morph.png");

            Imgproc.Canny(morph, edges, 50, 150);
            saveDebugImage(context, edges, "debug_edges.png");

            edgesCopy = edges.clone();
            Imgproc.findContours(edgesCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            debug = Mat.zeros(edges.size(), CvType.CV_8UC3);
            for (int i = 0; i < contours.size(); i++) {
                Imgproc.drawContours(debug, contours, i, new Scalar(0, 255, 0), 2);
            }
            saveDebugImage(context, debug, "debug_contours.png");

            double imgArea = rgba.width() * rgba.height();
            double maxArea = 0;
            Point[] bestQuad = null;

            for (MatOfPoint contour : contours) {
                try {
                    double area = Imgproc.contourArea(contour);
                    if (area < imgArea * 0.20) continue;

                    MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
                    MatOfPoint2f approx = new MatOfPoint2f();
                    MatOfPoint approxAsPoints = null;
                    try {
                        Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.02, true);
                        approxAsPoints = new MatOfPoint(approx.toArray());
                        boolean isConvex = Imgproc.isContourConvex(approxAsPoints);

                        if (approx.total() == 4 && isConvex) {
                            Point[] quad = approx.toArray();

                            double w1 = Math.hypot(quad[0].x - quad[1].x, quad[0].y - quad[1].y);
                            double w2 = Math.hypot(quad[2].x - quad[3].x, quad[2].y - quad[3].y);
                            double h1 = Math.hypot(quad[1].x - quad[2].x, quad[1].y - quad[2].y);
                            double h2 = Math.hypot(quad[3].x - quad[0].x, quad[3].y - quad[0].y);
                            double avgWidth = (w1 + w2) / 2.0;
                            double avgHeight = (h1 + h2) / 2.0;
                            double aspectRatio = avgHeight / avgWidth;

                            if (aspectRatio > 0.5 && aspectRatio < 2.5 && area > maxArea) {
                                maxArea = area;
                                bestQuad = sortPointsClockwise(quad);
                            }
                        }
                    } finally {
                        release(approxAsPoints);
                        release(curve, approx);
                    }
                } finally {
                    release(contour);
                }
            }

            if (bestQuad != null) {
                Log.i(TAG, "Document contour found");
                return bestQuad;
            }

            Log.w(TAG, "No suitable document contour found, returning fallback rectangle");
            return getFallbackRectangle(bitmap.getWidth(), bitmap.getHeight());
        } finally {
            release(rgba, gray, threshold, morph, kernel, edges, edgesCopy, hierarchy, debug);
        }
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
        Point[] onnx = detectDocumentCornersWithOnnx(bitmap);
        Point[] cv   = detectDocumentCornersWithOpenCV(context, bitmap);
        return getBestCorners(onnx, cv, bitmap.getWidth(), bitmap.getHeight());
    }

    private static Point[] getBestCorners(Point[] cornersOnnx, Point[] cornersOpenCV, int w, int h) {
        Log.i(TAG, "getBestCorners()");
        if (cornersOnnx == null || cornersOnnx.length != 4) return cornersOpenCV;
        if (cornersOpenCV == null || cornersOpenCV.length != 4) return cornersOnnx;

        // Fallback? → ONNX bevorzugen, wenn valide
        if (isFallback(cornersOpenCV, w, h)) {
            Log.i(TAG, "OpenCV returned fallback → choosing ONNX");
            return sortPointsClockwise(cornersOnnx);
        }

        cornersOnnx   = sortPointsClockwise(cornersOnnx);
        cornersOpenCV = sortPointsClockwise(cornersOpenCV);

        double aOnnx = quadArea(cornersOnnx);
        double aCv   = quadArea(cornersOpenCV);

        if (aOnnx < 0.10 * aCv) return cornersOpenCV;

        if (aOnnx / aCv > 1.15) return cornersOnnx;
        return cornersOpenCV;
    }

    /**
     * Sorts the points in clockwise order starting from the top-left corner.
     * This is used to ensure the points are in a consistent order for perspective transformations.
     *
     * @param src The source points to be sorted.
     * @return An array of points sorted in clockwise order.
     */
    private static Point[] sortPointsClockwise(Point[] src) {
        List<Point> pts = new ArrayList<>(Arrays.asList(src));
        pts.sort(Comparator.comparingDouble(p -> p.x + p.y));
        Point topLeft = pts.get(0);
        Point bottomRight = pts.get(pts.size() - 1);
        pts.sort(Comparator.comparingDouble(p -> p.y - p.x));
        Point topRight = pts.get(0);
        Point bottomLeft = pts.get(pts.size() - 1);
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
        try {
            Utils.matToBitmap(mat, debugBmp);
            File file = new File(context.getExternalFilesDir(null), filename);
            try (FileOutputStream out = new FileOutputStream(file)) {
                debugBmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                Log.i(TAG, "Saved debug image: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save debug image", e);
        }
    }

    // -------------------------
    // Release helpers (no-ops for null)
    // -------------------------
    private static void release(Mat... mats) {
        if (mats == null) return;
        for (Mat m : mats) {
            if (m != null) {
                try { m.release(); } catch (Throwable ignore) {}
            }
        }
    }

    private static void releaseAll(List<Mat> mats) {
        if (mats == null) return;
        for (Mat m : mats) {
            if (m != null) {
                try { m.release(); } catch (Throwable ignore) {}
            }
        }
        mats.clear();
    }
}
