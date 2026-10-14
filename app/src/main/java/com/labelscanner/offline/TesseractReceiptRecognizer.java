package com.labelscanner.offline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.SystemClock;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Dedicated off-line OCR for an already cropped single warehouse-address line. */
public final class TesseractReceiptRecognizer implements AutoCloseable {
    public static final class Recognition {
        public final String text;
        public final int confidence; // Estimated confidence (0..100), NOT proof of correctness.
        public final long millis;

        public Recognition(String text, int confidence, long millis) {
            this.text = text;
            this.confidence = confidence;
            this.millis = millis;
        }
    }

    private final TessBaseAPI tess;

    public TesseractReceiptRecognizer(Context context) throws IOException {
        File folder = new File(context.getFilesDir(), "tesseract/tessdata");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Не удалось создать каталог OCR: " + folder);
        }
        copyAsset(context, folder, "rus.traineddata");
        copyAsset(context, folder, "eng.traineddata");
        TessBaseAPI instance = new TessBaseAPI();
        try {
            File parent = folder.getParentFile();
            if (parent == null || !instance.init(parent.getAbsolutePath(), "rus+eng")) {
                throw new IOException("Не удалось инициализировать Tesseract: проверьте rus и eng модели");
            }
            instance.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
        } catch (IOException failure) {
            instance.recycle();
            throw failure;
        } catch (RuntimeException | Error failure) {
            instance.recycle();
            throw failure;
        }
        tess = instance;
    }

    private static void copyAsset(Context context, File folder, String fileName) throws IOException {
        File dst = new File(folder, fileName);
        if (dst.isFile() && dst.length() > 100_000) return;
        File tmp = new File(folder, fileName + ".tmp");
        try (InputStream in = context.getAssets().open("tessdata/" + fileName);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (tmp.length() < 100_000) throw new IOException("OCR модель не загружена: " + fileName);
        if (dst.exists() && !dst.delete()) throw new IOException("Не удалось заменить " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("Не удалось сохранить " + dst);
    }

    /** The old LAB v0.1 baseline: grayscale + one line. */
    public Recognition recognize(Bitmap frame) {
        return recognizeLine(frame);
    }

    /** Baseline Tesseract LAB v0.1 settings (PSM_SINGLE_LINE + grayscale). */
    public Recognition recognizeLine(Bitmap frame) {
        return run(frame, false);
    }

    /** Same principal image-processing choices as ScanSklad Web v1.0:
     *  SINGLE_BLOCK segmentation + no forced grayscale + upscaling to ~1100 px.
     *  The native engine, input camera and postprocessing are not identical to the web app. */
    public Recognition recognizeWebStyle(Bitmap frame) {
        return run(frame, true);
    }

    /** Must be called from the SAME dedicated background thread that initialized Tesseract. */
    private Recognition run(Bitmap frame, boolean webStyle) {
        long start = SystemClock.elapsedRealtime();
        Bitmap processed = webStyle ? preprocessWeb(frame) : preprocess(frame);
        tess.setPageSegMode(webStyle
                ? TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                : TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
        try {
            tess.setImage(processed);
            String text = tess.getUTF8Text();
            int confidence = tess.meanConfidence();
            return new Recognition(text == null ? "" : text.replace('\n', ' ').trim(),
                    Math.max(0, Math.min(100, confidence)), SystemClock.elapsedRealtime() - start);
        } finally {
            tess.clear();
            processed.recycle();
        }
    }

    /** Upscale small type and make a grayscale copy; no dictionary or address guessing. */
    private static Bitmap preprocess(Bitmap bitmap) {
        int width = bitmap.getWidth();
        // Keep memory bounded; small labels benefit from 1.5–2x interpolation.
        float scale = width < 900 ? Math.min(2f, 1100f / Math.max(1, width)) : 1f;
        int targetW = Math.max(1, Math.round(width * scale));
        int targetH = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(grayscale));
        canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, targetW, targetH), paint);
        return output;
    }

    /** Preserve colour as the web app does; do not apply guessed substitutions. */
    private static Bitmap preprocessWeb(Bitmap bitmap) {
        int w = Math.max(1, bitmap.getWidth());
        int h = Math.max(1, bitmap.getHeight());
        int targetW = Math.min(1550, Math.max(w, 1100));
        int targetH = Math.max(1, Math.round((float) targetW * h / w));
        // The web app uses an ordinary canvas; Android bitmap bilinear filtering is the
        // closest practical mapping without turning this into a browser runtime.
        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, targetW, targetH), paint);
        return out;
    }

    @Override public void close() {
        tess.recycle();
    }
}
