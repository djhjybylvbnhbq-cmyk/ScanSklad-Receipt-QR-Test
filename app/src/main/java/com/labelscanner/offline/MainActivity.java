package com.labelscanner.offline;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.media.Image;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.Surface;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.labelscanner.offline.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = TransformExperimental.class)
public class MainActivity extends AppCompatActivity {

    private enum Screen {
        SCAN_ARRIVAL,
        CONFIRM_ARRIVAL,
        SCAN_CODE,
        CONFIRM_CODE,
        BOXES,
        RESULTS
    }

    private ActivityMainBinding binding;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    // The Russian OCR is deliberately kept off CameraX's frame analyzer thread.
    // It consumes a private cropped Bitmap, NOT the ImageProxy owned by ML Kit.
    private final ExecutorService receiptOcrExecutor = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final BarcodeScanner barcodeScanner = BarcodeScanning.getClient(
            new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
    // An offline Russian OCR fallback only for damaged/old receipt "Приход №" labels.
    private TesseractReceiptRecognizer receiptRecognizer; // owned by receiptOcrExecutor
    private volatile boolean receiptOcrUnavailable = false;
    private final AtomicBoolean receiptOcrBusy = new AtomicBoolean(false);
    private final AtomicLong lastTesseractAttemptMs = new AtomicLong(0);
    private final AtomicLong scanGeneration = new AtomicLong(0);
    private static final long TESSERACT_INTERVAL_MS = 850L;
    private static final long FAST_SCAN_GRACE_MS = 380L;
    private volatile long scanStartedAtMs = 0L;
    private ProcessCameraProvider cameraProvider;
    private AppStorage storage;

    private Screen screen = Screen.SCAN_ARRIVAL;
    private String arrival = "";
    private String code = "";
    private String boxes = "";
    private String quantityUnit = ResultLine.UNIT_PIECES;
    private final List<String> results = new ArrayList<>();

    private volatile boolean analysisBusy = false;
    private volatile boolean appDestroyed = false;
    private String lastCandidate = null;
    private int candidateHits = 0;
    private long lastCandidateSeenAtMs = 0L;
    private static final long CANDIDATE_CONFIRM_WINDOW_MS = 1800L;

    // CameraX coordinate mapping. The OCR result is in ImageAnalysis coordinates,
    // while the red frame is drawn in PreviewView coordinates. Version 1.3 maps
    // the real visible red frame into the OCR image and rejects everything outside it.
    private final ImageProxyTransformFactory imageProxyTransformFactory = new ImageProxyTransformFactory();
    private volatile OutputTransform previewOutputTransform;
    private volatile RectF scanFrameRectInPreview;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    render();
                } else {
                    binding.statusText.setText("Для сканирования нужен доступ к камере. Значения можно будет исправлять вручную после предоставления доступа.");
                    Toast.makeText(this, "Доступ к камере не предоставлен", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // COMPATIBLE mode is required for an exact PreviewView OutputTransform.
        binding.previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        binding.previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        imageProxyTransformFactory.setUsingRotationDegrees(true);
        binding.previewView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> refreshPreviewMapping());
        binding.scanFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> refreshPreviewMapping());

        storage = new AppStorage(this);
        results.addAll(storage.loadResults());
        arrival = storage.loadArrival();
        code = storage.loadCode();
        boxes = storage.loadBoxes();
        quantityUnit = ResultLine.normalizeUnit(storage.loadQuantityUnit());
        screen = safeScreen(storage.loadScreen(Screen.SCAN_ARRIVAL.name()));

        applyBottomSystemInset();
        bindButtons();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!navigateBack()) {
                    finish();
                }
            }
        });
        render();
    }

    private Screen safeScreen(String value) {
        try {
            return Screen.valueOf(value);
        } catch (Exception ignored) {
            return Screen.SCAN_ARRIVAL;
        }
    }

    private void bindButtons() {
        binding.primaryButton.setOnClickListener(v -> onPrimary());
        binding.secondaryButton.setOnClickListener(v -> onSecondary());
        binding.copyButton.setOnClickListener(v -> copyAll());
        binding.sendMaxButton.setOnClickListener(v -> sendToMax());
        binding.newButton.setOnClickListener(v -> confirmNew());
        binding.backResultButton.setOnClickListener(v -> navigateBack());
        binding.nextButton.setOnClickListener(v -> startNextKeepingResults());
        binding.quantityUnitGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || screen != Screen.BOXES) return;
            if (checkedId == R.id.unitBoxesButton) {
                quantityUnit = ResultLine.UNIT_BOXES;
            } else if (checkedId == R.id.unitPackagesButton) {
                quantityUnit = ResultLine.UNIT_PACKAGES;
            } else if (checkedId == R.id.unitPiecesButton) {
                quantityUnit = ResultLine.UNIT_PIECES;
            }
            boxes = binding.valueInput.getText().toString().trim();
            persistProgress();
        });
    }

    private void applyBottomSystemInset() {
        final int initialLeft = binding.bottomPanel.getPaddingLeft();
        final int initialTop = binding.bottomPanel.getPaddingTop();
        final int initialRight = binding.bottomPanel.getPaddingRight();
        final int initialBottom = binding.bottomPanel.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomPanel, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(initialLeft, initialTop, initialRight, initialBottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(binding.bottomPanel);
    }

    private void onPrimary() {
        switch (screen) {
            case SCAN_ARRIVAL:
                arrival = "";
                switchTo(Screen.CONFIRM_ARRIVAL);
                break;

            case SCAN_CODE:
                code = "";
                switchTo(Screen.CONFIRM_CODE);
                break;

            case CONFIRM_ARRIVAL:
                String editedArrival = binding.valueInput.getText().toString().trim();
                if (!LabelTextParser.isValidArrival(editedArrival)) {
                    binding.valueInput.setError("Введите номер прихода: только цифры");
                    return;
                }
                arrival = editedArrival;
                switchTo(Screen.SCAN_CODE);
                break;

            case CONFIRM_CODE:
                String editedCode = LabelTextParser.normalizeCodeInput(binding.valueInput.getText().toString());
                if (!LabelTextParser.isValidCode(editedCode)) {
                    binding.valueInput.setError("Примеры: 136/10/5, 152/1, 142/Л10, 162/шкаф4");
                    return;
                }
                code = editedCode;
                switchTo(Screen.BOXES);
                break;

            case BOXES:
                boxes = binding.valueInput.getText().toString().trim();
                if (!boxes.isEmpty() && !boxes.matches("[1-9]\\d*")) {
                    binding.valueInput.setError("Введите целое число больше нуля или оставьте поле пустым");
                    return;
                }
                appendCurrentResult();
                switchTo(Screen.RESULTS);
                break;

            default:
                break;
        }
    }

    private void onSecondary() {
        navigateBack();
    }

    private boolean navigateBack() {
        hideKeyboard();

        switch (screen) {
            case CONFIRM_ARRIVAL:
                switchTo(Screen.SCAN_ARRIVAL);
                return true;

            case SCAN_CODE:
                switchTo(Screen.CONFIRM_ARRIVAL);
                return true;

            case CONFIRM_CODE:
                switchTo(Screen.SCAN_CODE);
                return true;

            case BOXES:
                boxes = binding.valueInput.getText().toString().trim();
                switchTo(Screen.CONFIRM_CODE);
                return true;

            case RESULTS:
                return editLastResult();

            case SCAN_ARRIVAL:
                if (!results.isEmpty()) {
                    switchTo(Screen.RESULTS);
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    private boolean editLastResult() {
        if (results.isEmpty()) return false;

        ResultLine last = ResultLine.parse(results.get(results.size() - 1));
        if (last == null) {
            Toast.makeText(this, "Не удалось открыть последнюю строку для редактирования", Toast.LENGTH_LONG).show();
            return false;
        }
        arrival = last.arrival;
        boxes = last.quantity;
        quantityUnit = last.unit;
        code = last.code;

        results.remove(results.size() - 1);
        storage.saveResults(results);
        switchTo(Screen.BOXES);
        return true;
    }

    private void appendCurrentResult() {
        // Quantity is optional. Without a quantity there are no brackets or units.
        results.add(ResultLine.format(arrival, boxes, quantityUnit, code));
        storage.saveResults(results);
    }

    private void copyAll() {
        if (results.isEmpty()) {
            Toast.makeText(this, "Список пока пуст", Toast.LENGTH_SHORT).show();
            return;
        }
        String joined = String.join("\n", results);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Результаты сканирования", joined));
        Toast.makeText(this, "Весь список скопирован", Toast.LENGTH_SHORT).show();
    }

    private void sendToMax() {
        if (results.isEmpty()) {
            Toast.makeText(this, "Список пока пуст", Toast.LENGTH_SHORT).show();
            return;
        }

        String joined = String.join("\n", results);
        Uri shareUri = Uri.parse("https://max.ru/:share")
                .buildUpon()
                .appendQueryParameter("text", joined)
                .build();

        Intent intent = new Intent(Intent.ACTION_VIEW, shareUri);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть MAX", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmNew() {
        if (results.isEmpty()) {
            resetEverything();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Начать новый список?")
                .setMessage("Все текущие результаты будут удалены.")
                .setNegativeButton("ОТМЕНА", null)
                .setPositiveButton("ОЧИСТИТЬ", (dialog, which) -> resetEverything())
                .show();
    }

    private void resetEverything() {
        results.clear();
        arrival = "";
        code = "";
        boxes = "";
        quantityUnit = ResultLine.UNIT_PIECES;
        storage.clearAll();
        switchTo(Screen.SCAN_ARRIVAL);
    }

    private void startNextKeepingResults() {
        arrival = "";
        code = "";
        boxes = "";
        quantityUnit = ResultLine.UNIT_PIECES;
        switchTo(Screen.SCAN_ARRIVAL);
    }

    private void switchTo(Screen next) {
        hideKeyboard();
        screen = next;
        persistProgress();
        render();
    }

    private void persistProgress() {
        storage.saveProgress(screen.name(), arrival, code, boxes, quantityUnit);
        storage.saveResults(results);
    }

    private void render() {
        boolean scanning = screen == Screen.SCAN_ARRIVAL || screen == Screen.SCAN_CODE;
        binding.cameraContainer.setVisibility(scanning ? View.VISIBLE : View.GONE);
        binding.contentPanel.setVisibility(scanning ? View.GONE : View.VISIBLE);
        binding.actionRow.setVisibility(View.GONE);
        binding.resultButtons.setVisibility(View.GONE);
        binding.resultsScroll.setVisibility(View.GONE);
        binding.valueInput.setVisibility(View.VISIBLE);
        binding.quantityUnitGroup.setVisibility(View.GONE);
        binding.statusText.setText("");

        if (!scanning) {
            stopCamera();
        }

        switch (screen) {
            case SCAN_ARRIVAL:
                binding.scanHint.setText("Наведите рамку на «Приход №» и число справа");
                binding.statusText.setText("Новый или старый образец: поместите надпись и цифры справа внутрь рамки.");
                binding.actionRow.setVisibility(View.VISIBLE);
                binding.secondaryButton.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
                binding.secondaryButton.setText("НАЗАД К СПИСКУ");
                binding.primaryButton.setVisibility(View.VISIBLE);
                binding.primaryButton.setText("ВВЕСТИ ВРУЧНУЮ");
                setScanFrameHeightDp(175);
                ensureCameraPermissionAndStart();
                break;

            case CONFIRM_ARRIVAL:
                showConfirm("Проверьте номер прихода", "При необходимости исправьте распознанное значение.", arrival, false);
                break;

            case SCAN_CODE:
                binding.scanHint.setText("Наведите рамку на QR-код стеллажа");
                binding.statusText.setText("Адрес считывается из QR-кода внутри красной рамки. Для старых наклеек доступен ручной ввод.");
                binding.actionRow.setVisibility(View.VISIBLE);
                binding.secondaryButton.setVisibility(View.VISIBLE);
                binding.secondaryButton.setText("НАЗАД");
                binding.primaryButton.setVisibility(View.VISIBLE);
                binding.primaryButton.setText("ВВЕСТИ ВРУЧНУЮ");
                setScanFrameHeightDp(260);
                ensureCameraPermissionAndStart();
                break;

            case CONFIRM_CODE:
                showConfirm("Проверьте адрес из QR", "При необходимости исправьте адрес вручную.", code, false);
                break;

            case BOXES:
                binding.contentTitle.setText("Количество");
                binding.contentSubtitle.setText("Введите количество (необязательно). По умолчанию — шт.");
                binding.valueInput.setInputType(InputType.TYPE_CLASS_NUMBER);
                binding.valueInput.setText(boxes);
                binding.valueInput.setHint("Например: 20");
                binding.quantityUnitGroup.setVisibility(View.VISIBLE);
                if (ResultLine.UNIT_BOXES.equals(quantityUnit)) {
                    binding.quantityUnitGroup.check(R.id.unitBoxesButton);
                } else if (ResultLine.UNIT_PACKAGES.equals(quantityUnit)) {
                    binding.quantityUnitGroup.check(R.id.unitPackagesButton);
                } else {
                    binding.quantityUnitGroup.check(R.id.unitPiecesButton);
                }
                binding.actionRow.setVisibility(View.VISIBLE);
                binding.secondaryButton.setVisibility(View.VISIBLE);
                binding.secondaryButton.setText("НАЗАД");
                binding.primaryButton.setVisibility(View.VISIBLE);
                binding.primaryButton.setText("ДАЛЕЕ");
                binding.statusText.setText("Приход: " + arrival + "   •   Код: " + code);
                binding.valueInput.requestFocus();
                showKeyboard();
                break;

            case RESULTS:
                binding.contentTitle.setText("Готово");
                binding.contentSubtitle.setText("ДАЛЕЕ — добавить строку. НАЗАД — исправить последнюю. НОВОЕ — очистить список.");
                binding.valueInput.setVisibility(View.GONE);
                binding.resultsScroll.setVisibility(View.VISIBLE);
                binding.resultsText.setText(String.join("\n", results));
                binding.resultButtons.setVisibility(View.VISIBLE);
                binding.statusText.setText("Строк в списке: " + results.size());
                break;
        }

        persistProgress();
    }

    private void setScanFrameHeightDp(int heightDp) {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) binding.scanFrame.getLayoutParams();
        int pixels = Math.round(heightDp * getResources().getDisplayMetrics().density);
        if (lp.height != pixels) {
            lp.height = pixels;
            binding.scanFrame.setLayoutParams(lp);
            binding.scanFrame.post(this::refreshPreviewMapping);
        }
    }

    private void showConfirm(String title, String subtitle, String value, boolean numeric) {
        binding.contentTitle.setText(title);
        binding.contentSubtitle.setText(subtitle);
        binding.valueInput.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        binding.valueInput.setText(value);
        binding.valueInput.setSelection(binding.valueInput.getText().length());
        binding.actionRow.setVisibility(View.VISIBLE);
        binding.secondaryButton.setVisibility(View.VISIBLE);
        binding.secondaryButton.setText("НАЗАД");
        binding.primaryButton.setVisibility(View.VISIBLE);
        binding.primaryButton.setText("ДАЛЕЕ");
        binding.statusText.setText("Можно исправить значение вручную.");
    }

    private void ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        scanGeneration.incrementAndGet();
        scanStartedAtMs = SystemClock.elapsedRealtime();
        lastTesseractAttemptMs.set(0L);
        resetCandidateDebounce();
        // Prepare Russian models while ML Kit reads the first frames. On the
        // first old-style receipt we don't want to wait for model initialization.
        if (screen == Screen.SCAN_ARRIVAL && !receiptOcrUnavailable) {
            receiptOcrExecutor.execute(() -> {
                if (receiptRecognizer != null || receiptOcrUnavailable) return;
                try { receiptRecognizer = new TesseractReceiptRecognizer(this); }
                catch (Exception | LinkageError ex) { receiptOcrUnavailable = true; }
            });
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть камеру", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        int targetRotation = Surface.ROTATION_0;
        if (binding.previewView.getDisplay() != null) {
            targetRotation = binding.previewView.getDisplay().getRotation();
        }

        Preview preview = new Preview.Builder()
                .setTargetRotation(targetRotation)
                .build();
        preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        try {
            // A shared ViewPort keeps Preview and ImageAnalysis on the same sensor crop,
            // so the red frame corresponds to the same physical area OCR receives.
            ViewPort viewPort = binding.previewView.getViewPort(targetRotation);
            UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(analysis);
            if (viewPort != null) {
                group.setViewPort(viewPort);
            }
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build());

            binding.previewView.postDelayed(this::refreshPreviewMapping, 120);
            binding.previewView.postDelayed(this::refreshPreviewMapping, 450);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshPreviewMapping() {
        if (binding == null) return;
        binding.previewView.post(() -> {
            if (binding == null) return;

            OutputTransform transform = binding.previewView.getOutputTransform();
            if (transform != null) {
                previewOutputTransform = transform;
            }

            if (binding.scanFrame.getWidth() > 0 && binding.scanFrame.getHeight() > 0
                    && binding.previewView.getWidth() > 0 && binding.previewView.getHeight() > 0) {
                // scanFrame and previewView have the same parent (cameraContainer).
                float left = binding.scanFrame.getLeft() - binding.previewView.getLeft();
                float top = binding.scanFrame.getTop() - binding.previewView.getTop();
                float right = left + binding.scanFrame.getWidth();
                float bottom = top + binding.scanFrame.getHeight();
                scanFrameRectInPreview = new RectF(left, top, right, bottom);
            }
        });
    }

    private RectF mapScanFrameToAnalysis(@NonNull ImageProxy imageProxy) {
        OutputTransform target = previewOutputTransform;
        RectF frame = scanFrameRectInPreview;
        if (target == null || frame == null || frame.isEmpty()) {
            binding.previewView.post(this::refreshPreviewMapping);
            return null;
        }

        try {
            OutputTransform source = imageProxyTransformFactory.getOutputTransform(imageProxy);
            // We have a rectangle in PreviewView coordinates and need the corresponding
            // rectangle in ML Kit/ImageAnalysis coordinates.
            CoordinateTransform previewToAnalysis = new CoordinateTransform(target, source);
            RectF mapped = new RectF(frame);
            previewToAnalysis.mapRect(mapped);
            return mapped;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (analysisBusy || !(screen == Screen.SCAN_ARRIVAL || screen == Screen.SCAN_CODE)) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) { imageProxy.close(); return; }
        RectF scanRoi = mapScanFrameToAnalysis(imageProxy);
        if (scanRoi == null || scanRoi.isEmpty()) { imageProxy.close(); return; }

        final Screen scanScreen = screen;
        analysisBusy = true;
        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        if (scanScreen == Screen.SCAN_CODE) {
            barcodeScanner.process(image).addOnCompleteListener(task -> {
                try {
                    if (task.isSuccessful() && screen == Screen.SCAN_CODE) {
                        String found = findQrAddress(task.getResult(), scanRoi);
                        if (found != null) acceptCandidate(found, Screen.SCAN_CODE);
                    }
                } finally { closeFrame(imageProxy); }
            });
        } else {
            final long epoch = scanGeneration.get();
            recognizer.process(image).addOnCompleteListener(task -> {
                String found = null;
                if (task.isSuccessful() && screen == Screen.SCAN_ARRIVAL
                        && scanGeneration.get() == epoch) {
                    // Same inexpensive parser as production v2.0 is the FIRST path.
                    // Old table labels also work when the label and number occupy
                    // separate ML Kit bounding boxes (geometry fallback).
                    found = LabelTextParser.findArrival(task.getResult(), scanRoi);
                    if (found == null) found = findArrivalByPosition(task.getResult(), scanRoi);
                }
                if (found != null) {
                    acceptCandidate(found, Screen.SCAN_ARRIVAL);
                    closeFrame(imageProxy);
                    return;
                }
                // A failure in one ML Kit frame must not stop new frames while
                // slower Russian OCR runs. Limit to one independent OCR job.
                long now = SystemClock.elapsedRealtime();
                if (appDestroyed || screen != Screen.SCAN_ARRIVAL || scanGeneration.get() != epoch
                        || receiptOcrUnavailable || now - scanStartedAtMs < FAST_SCAN_GRACE_MS
                        || now - lastTesseractAttemptMs.get() < TESSERACT_INTERVAL_MS
                        || !receiptOcrBusy.compareAndSet(false, true)) {
                    closeFrame(imageProxy);
                    return;
                }
                lastTesseractAttemptMs.set(now);
                // Camera analyzer owns the image while it is being copied.
                // It is ALWAYS released before Tesseract starts processing.
                cameraExecutor.execute(() -> {
                    Bitmap cropped = null;
                    try {
                        if (scanGeneration.get() == epoch && screen == Screen.SCAN_ARRIVAL) {
                            cropped = cropReceiptBitmap(imageProxy, scanRoi);
                        }
                    } finally {
                        closeFrame(imageProxy);
                    }
                    if (cropped == null) {
                        receiptOcrBusy.set(false);
                        return;
                    }
                    final Bitmap toRecognize = cropped;
                    receiptOcrExecutor.execute(() -> {
                        try {
                            if (scanGeneration.get() != epoch || screen != Screen.SCAN_ARRIVAL) return;
                            String fallback = tryRussianOcr(toRecognize);
                            if (fallback != null) runOnUiThread(() -> {
                                if (scanGeneration.get() == epoch && screen == Screen.SCAN_ARRIVAL)
                                    acceptCandidate(fallback, Screen.SCAN_ARRIVAL);
                            });
                        } finally {
                            toRecognize.recycle();
                            receiptOcrBusy.set(false);
                        }
                    });
                });
            });
        }
    }

    private String findArrivalByPosition(Text text, RectF frame) {
        List<ArrivalGeometry.Word> words = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect b = element.getBoundingBox();
                    if (b != null && withinRedFrame(b, frame)) {
                        words.add(new ArrivalGeometry.Word(element.getText(), b.left, b.top, b.right, b.bottom));
                    }
                }
            }
        }
        return ArrivalGeometry.find(words);
    }

    private static boolean withinRedFrame(Rect box, RectF frame) {
        float cx = box.exactCenterX(), cy = box.exactCenterY();
        float area = Math.max(1f, box.width() * box.height());
        float x = Math.max(0, Math.min(box.right, frame.right) - Math.max(box.left, frame.left));
        float y = Math.max(0, Math.min(box.bottom, frame.bottom) - Math.max(box.top, frame.top));
        return frame.contains(cx, cy) && (x * y / area) >= 0.65f;
    }

    private static String findQrAddress(List<Barcode> barcodes, RectF frame) {
        for (Barcode b : barcodes) {
            Rect r = b.getBoundingBox();
            if (r == null || !withinRedFrame(r, frame)) continue;
            String raw = b.getRawValue();
            if (raw == null || raw.length() > 96) continue;
            // No links or miscellaneous barcodes: QR must hold a plain storage address.
            String normalized = LabelTextParser.normalizeCodeInput(raw.trim());
            if (LabelTextParser.isValidCode(normalized)) return normalized;
        }
        return null;
    }

    /** Create an independent image for Russian OCR; called from the camera analyzer.
     * The red-frame crop is preserved exactly as in the original QR TEST.
     */
    private static Bitmap cropReceiptBitmap(ImageProxy proxy, RectF roi) {
        Bitmap raw = null, upright = null;
        try {
            raw = proxy.toBitmap();
            int rotation = proxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
            } else upright = raw;
            int l = Math.max(0, (int) Math.floor(roi.left));
            int t = Math.max(0, (int) Math.floor(roi.top));
            int r = Math.min(upright.getWidth(), (int) Math.ceil(roi.right));
            int b = Math.min(upright.getHeight(), (int) Math.ceil(roi.bottom));
            if (r - l < 70 || b - t < 35) return null;
            // Use an independent buffer; a whole-frame crop may otherwise return
            // the source bitmap, which is recycled when the camera frame closes.
            Bitmap view = Bitmap.createBitmap(upright, l, t, r - l, b - t);
            Bitmap copy = view.copy(Bitmap.Config.ARGB_8888, false);
            if (view != upright && view != raw) view.recycle();
            return copy;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        } finally {
            if (upright != null && upright != raw) upright.recycle();
            if (raw != null) raw.recycle();
        }
    }

    /** Runs on the dedicated receiptOcrExecutor, without holding any camera frame. */
    private String tryRussianOcr(Bitmap cropped) {
        try {
            if (receiptRecognizer == null) {
                try { receiptRecognizer = new TesseractReceiptRecognizer(this); }
                catch (Exception | LinkageError ex) { receiptOcrUnavailable = true; return null; }
            }
            String first = receiptRecognizer.recognizeWebStyle(cropped).text;
            String number = ArrivalGeometry.fromRussianOcr(first);
            if (number != null) return number;
            // Keep the secondary layout mode for old labels with table columns.
            return ArrivalGeometry.fromRussianOcr(receiptRecognizer.recognizeLine(cropped).text);
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    private void acceptCandidate(String candidate, Screen scanScreen) {
        if (screen != scanScreen || candidate == null || candidate.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (candidate.equals(lastCandidate) && now - lastCandidateSeenAtMs <= CANDIDATE_CONFIRM_WINDOW_MS) {
            candidateHits++;
        } else { lastCandidate = candidate; candidateHits = 1; }
        lastCandidateSeenAtMs = now;
        if (candidateHits < 2) return;
        runOnUiThread(() -> {
            if (screen != scanScreen) return;
            if (scanScreen == Screen.SCAN_ARRIVAL) { arrival = candidate; switchTo(Screen.CONFIRM_ARRIVAL); }
            else { code = candidate; switchTo(Screen.CONFIRM_CODE); }
        });
    }

    private void closeFrame(ImageProxy proxy) {
        analysisBusy = false;
        proxy.close();
    }

    private void resetCandidateDebounce() {
        lastCandidate = null;
        candidateHits = 0;
        lastCandidateSeenAtMs = 0L;
    }

    private void stopCamera() {
        scanGeneration.incrementAndGet(); // discard pending OCR results from old screen
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analysisBusy = false;
        resetCandidateDebounce();
    }

    private void showKeyboard() {
        binding.valueInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(binding.valueInput, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View current = getCurrentFocus();
        if (current != null) imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
    }

    @Override
    protected void onPause() {
        // Preserve even text that the user has typed but has not confirmed yet.
        if (binding != null && binding.valueInput.getVisibility() == View.VISIBLE) {
            String current = binding.valueInput.getText().toString().trim();
            if (screen == Screen.CONFIRM_ARRIVAL) arrival = current;
            if (screen == Screen.CONFIRM_CODE) code = current;
            if (screen == Screen.BOXES) boxes = current;
        }
        persistProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        appDestroyed = true;
        scanGeneration.incrementAndGet();
        persistProgress();
        stopCamera();
        recognizer.close();
        barcodeScanner.close();
        // Release native Tesseract on the same dedicated executor that created it.
        receiptOcrExecutor.execute(() -> {
            if (receiptRecognizer != null) receiptRecognizer.close();
        });
        receiptOcrExecutor.shutdown();
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
