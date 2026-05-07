package com.fadcam.ui.miniapps;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QR/Barcode Scanner using CameraX + ZXing.
 * Scans all barcode formats, captures frame on decode, saves JPEG + metadata JSON.
 */
public class QRScannerActivity extends AppCompatActivity {

    private static final int RC_CAMERA = 101;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isScanning = new AtomicBoolean(true);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessCameraProvider cameraProvider;
    private ImageView ivTorch;
    private boolean torchOn;
    private View scanLine;
    private View resultContainer;
    private TextView rt;
    private TextView ht;
    private View btnCopy;
    private View btnAgain;
    private String lastScannedText;
    private String lastScannedFormat;
    private Date lastScannedTime;
    private androidx.camera.core.Camera camera;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);
        ivTorch = findViewById(R.id.qr_torch);
        ImageView ivClose = findViewById(R.id.qr_close);
        ImageView ivInfo = findViewById(R.id.qr_info);
        scanLine = findViewById(R.id.qr_scan_line);
        resultContainer = findViewById(R.id.qr_result_container);
        rt = findViewById(R.id.qr_result_text);
        ht = findViewById(R.id.qr_hint_text);
        btnCopy = findViewById(R.id.qr_btn_copy);
        btnAgain = findViewById(R.id.qr_btn_scan_again);

        if (ivClose != null) ivClose.setOnClickListener(v -> finish());
        if (ivTorch != null) ivTorch.setOnClickListener(v -> toggleTorch());
        if (ivInfo != null) ivInfo.setOnClickListener(v -> showInfoDialog());

        View headerBar = findViewById(R.id.qr_header_bar);
        if (headerBar != null) headerBar.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + ins.getSystemWindowInsetTop(),
                    v.getPaddingRight(), v.getPaddingBottom());
            return ins;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
        } else startCamera();
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == RC_CAMERA && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else { Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show(); finish(); }
    }

    private void startCamera() {
        FLog.i("QRScanner","startCamera");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
                startScanLineAnimation();
                FLog.i("QRScanner","started");
            } catch (Exception e) {
                FLog.e("QRScanner","Camera error",e);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        cameraProvider.unbindAll();
        PreviewView pv = findViewById(R.id.qr_preview);
        Preview preview = new Preview.Builder().setTargetResolution(new Size(1280,720)).build();
        preview.setSurfaceProvider(pv.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280,720))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
        camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        if (!isScanning.get()) { image.close(); return; }
        Result result = decodeImage(image);
        if (result != null) {
            FLog.i("QRScanner","Detected: "+result.getText()+" ("+result.getBarcodeFormat()+")");
            if (isScanning.compareAndSet(true, false)) {
                lastScannedText = result.getText();
                lastScannedFormat = result.getBarcodeFormat().name();
                lastScannedTime = new Date();
                Bitmap frameBitmap = yuvToBitmap(image);
                image.close();
                final String text = result.getText();
                final String format = result.getBarcodeFormat().name();
                mainHandler.post(() -> showScanResult(text, format));
                final Bitmap bmp = frameBitmap;
                cameraExecutor.execute(() -> saveScan(text, format, bmp));
                return;
            }
        }
        image.close();
    }

    /**
     * Converts a YUV_420_888 ImageProxy frame to a Bitmap by:
     * 1. Converting the 3-plane YUV_420_888 to interleaved NV21
     * 2. Using YuvImage.compressToJpeg() for hardware-accelerated JPEG encoding
     * 3. Decoding the JPEG bytes to a Bitmap
     *
     * YUV_420_888 has separate planes (Y, U, V) but YuvImage expects
     * interleaved NV21 format [Y][VU][VU]... Passing raw plane[0] with
     * ImageFormat.NV21 causes native SIGSEGV in Yuv420SpToJpegEncoder.
     */
    private Bitmap yuvToBitmap(@NonNull ImageProxy image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            byte[] nv21 = yuv420888ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, out);
            byte[] jpeg = out.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0 && bitmap != null) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            return bitmap;
        } catch (Exception e) {
            FLog.e("QRScanner", "yuvToBitmap failed", e);
            return null;
        }
    }

    /**
     * Converts YUV_420_888 (3 separate planes) to NV21 (interleaved).
     * NV21 layout: [Y data for all pixels][VU VU VU... for each 2x2 block]
     */
    private static byte[] yuv420888ToNv21(@NonNull ImageProxy image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ImageProxy.PlaneProxy[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Rewind — decodeImage() may have consumed buffer positions
        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        int ySize = w * h;
        byte[] nv21 = new byte[ySize * 3 / 2];

        // Copy Y plane (handle row stride if != width)
        int yRowStride = planes[0].getRowStride();
        if (yRowStride == w) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            int yPos = 0;
            for (int row = 0; row < h; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, yPos, w);
                yPos += w;
            }
        }

        // Interleave V and U into NV21 tail
        int vuPos = ySize;
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int uvH = h / 2;
        int uvW = w / 2;

        for (int row = 0; row < uvH; row++) {
            for (int col = 0; col < uvW; col++) {
                nv21[vuPos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                nv21[vuPos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
            }
        }

        return nv21;
    }

    private void showScanResult(String text, String format) {
        View root = findViewById(android.R.id.content);
        if (root != null) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("audio/qrscanner_beep.mp3");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            mp.start();
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
        } catch (Exception e) { FLog.w("QRScanner", "Beep failed", e); }
        
        if (scanLine != null) { 
            scanLine.clearAnimation(); 
            scanLine.setVisibility(View.GONE); 
        }
        
        if (rt != null) { 
            rt.setText(text); 
        }
        
        if (resultContainer != null) {
            resultContainer.setVisibility(View.VISIBLE);
        }
        
        if (ht != null) {
            ht.setVisibility(View.GONE);
        }
        
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Scanned", text));
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            });
        }
        
        if (btnAgain != null) {
            btnAgain.setOnClickListener(v -> {
                if (resultContainer != null) resultContainer.setVisibility(View.GONE);
                if (ht != null) ht.setVisibility(View.VISIBLE);
                isScanning.set(true);
                startScanLineAnimation();
            });
        }
    }

    private void saveScan(String text, String format, Bitmap frameBitmap) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File scanDir = getScanDir(ts);
        FLog.i("QRScanner","saveScan: "+scanDir);
        if (scanDir == null || !scanDir.mkdirs()) return;
        File meta = new File(scanDir, "metadata.json");
        try (FileOutputStream fos = new FileOutputStream(meta)) {
            fos.write(String.format(Locale.US,
                "{\"rawValue\":\"%s\",\"format\":\"%s\",\"timestamp\":\"%s\"}",
                escapeJson(text), format, ts).getBytes());
        } catch (Exception ignored) {}
        if (frameBitmap != null) {
            String fileName = "FadCam_MiniApps_QRScanner_" + ts + ".jpg";
            File jpg = new File(scanDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(jpg)) {
                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                FLog.i("QRScanner", fileName + " saved: " + jpg.length() + " bytes");
            } catch (Exception ignored) {}
        }
        Intent intent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
        intent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
        intent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, Uri.fromFile(scanDir).toString());
        sendBroadcast(intent);
    }

    @Nullable private Result decodeImage(@NonNull ImageProxy image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()]; buf.get(data);
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, image.getWidth(), image.getHeight(), 0, 0, image.getWidth(), image.getHeight(), false);
            Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
                BarcodeFormat.QR_CODE,BarcodeFormat.EAN_8,BarcodeFormat.EAN_13,BarcodeFormat.UPC_A,BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_39,BarcodeFormat.CODE_93,BarcodeFormat.CODE_128,BarcodeFormat.DATA_MATRIX,BarcodeFormat.AZTEC,BarcodeFormat.PDF_417));
            MultiFormatReader r = new MultiFormatReader(); r.setHints(hints);
            return r.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
        } catch (NotFoundException e) { return null; }
    }

    private File getScanDir(String ts) {
        File ext = getExternalFilesDir(null);
        if (ext == null) return null;
        return new File(new File(new File(ext, Constants.RECORDING_DIRECTORY),
                Constants.RECORDING_SUBDIR_MINIAPPS), Constants.RECORDING_SUBDIR_MINIAPPS_QR + File.separator + ts);
    }

    private String escapeJson(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    private void startScanLineAnimation() {
        if (scanLine == null) return;
        scanLine.setVisibility(View.VISIBLE); scanLine.clearAnimation();
        scanLine.startAnimation(new TranslateAnimation(Animation.RELATIVE_TO_PARENT,0f,Animation.RELATIVE_TO_PARENT,0f,
                Animation.RELATIVE_TO_PARENT,0f,Animation.RELATIVE_TO_PARENT,1f) {{
            setDuration(2500); setRepeatCount(Animation.INFINITE); setRepeatMode(Animation.RESTART);
        }});
    }

    private void toggleTorch() {
        torchOn = !torchOn;
        if (ivTorch != null) {
            ivTorch.setImageResource(torchOn ? R.drawable.ic_flashlight_on : R.drawable.ic_flashlight_off);
        }
        if (camera != null) {
            camera.getCameraControl().enableTorch(torchOn);
        }
    }

    private void showInfoDialog() {
        if (lastScannedText == null) {
            Toast.makeText(this, "Scan something first to see info", Toast.LENGTH_SHORT).show();
            return;
        }
        String sb = "Format: " + lastScannedFormat + "\n\n"
                + "Value:\n" + lastScannedText + "\n\n"
                + "Saved: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(lastScannedTime) + "\n"
                + "File: FadCam_MiniApps_QRScanner_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(lastScannedTime) + ".jpg";
        new AlertDialog.Builder(this, com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle("Last Scan Info")
                .setMessage(sb)
                .setPositiveButton("Got it", null).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (scanLine != null) scanLine.clearAnimation();
    }
}
