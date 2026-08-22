package com.mediaextended.mobile.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CaptureService extends Service {
    static final String ACTION_START = "com.mediaextended.mobile.capture.START";
    private static final String ACTION_STOP = "com.mediaextended.mobile.capture.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_TOKEN = "token";
    private static final int NOTIFICATION_ID = 4102;
    private static final String CHANNEL_ID = "screen-capture";

    private static volatile CaptureService instance;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private LocalCaptureServer server;
    private volatile String token;
    private CompletableFuture<byte[]> pendingCapture;
    private Image latestImage;

    static boolean isReady() {
        CaptureService service = instance;
        return service != null && service.projection != null;
    }

    static void updateToken(String token) {
        CaptureService service = instance;
        if (service != null) service.token = token;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        imageThread = new HandlerThread("mx-capture-image");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        server = new LocalCaptureServer(this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        token = intent.getStringExtra(EXTRA_TOKEN);
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        if (projection == null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, ActivityResultCodes.CANCELED);
            Intent resultData = getResultData(intent);
            if (resultCode != ActivityResultCodes.OK || resultData == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, imageHandler);
            createCaptureSurface();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (projection != null) imageHandler.post(this::createCaptureSurface);
    }

    private void createCaptureSurface() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            metrics.widthPixels = bounds.width();
            metrics.heightPixels = bounds.height();
            metrics.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            // Android 8.1 compatibility.
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }

        ImageReader nextReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            3
        );
        nextReader.setOnImageAvailableListener(this::onImageAvailable, imageHandler);

        if (display == null) {
            display = projection.createVirtualDisplay(
                "MediaExtendedCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                nextReader.getSurface(),
                null,
                imageHandler
            );
        } else {
            display.setSurface(nextReader.getSurface());
            display.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        }
        ImageReader oldReader = imageReader;
        imageReader = nextReader;
        if (oldReader != null) {
            if (latestImage != null) {
                latestImage.close();
                latestImage = null;
            }
            oldReader.close();
        }
    }

    private synchronized void onImageAvailable(ImageReader reader) {
        if (reader != imageReader) {
            Image stale = reader.acquireLatestImage();
            if (stale != null) stale.close();
            return;
        }
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        CompletableFuture<byte[]> request = pendingCapture;
        if (request == null) {
            if (latestImage != null) latestImage.close();
            latestImage = image;
            return;
        }
        pendingCapture = null;
        try {
            request.complete(imageToPng(image));
        } catch (Throwable error) {
            request.completeExceptionally(error);
        } finally {
            image.close();
        }
    }

    byte[] capturePng() throws Exception {
        if (projection == null || imageReader == null) {
            throw new IllegalStateException("capture session is not ready");
        }
        CompletableFuture<byte[]> request = new CompletableFuture<>();
        imageHandler.post(() -> {
            synchronized (CaptureService.this) {
                if (pendingCapture != null) {
                    request.completeExceptionally(new IllegalStateException("capture already pending"));
                    return;
                }
                pendingCapture = request;
                Image image = latestImage;
                latestImage = null;
                if (image == null) image = imageReader.acquireLatestImage();
                if (image != null) {
                    pendingCapture = null;
                    try {
                        request.complete(imageToPng(image));
                    } catch (Throwable error) {
                        request.completeExceptionally(error);
                    } finally {
                        image.close();
                    }
                }
            }
        });
        return request.get(4, TimeUnit.SECONDS);
    }

    private byte[] imageToPng(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int paddedWidth = image.getWidth() + (rowStride - pixelStride * image.getWidth()) / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded) padded.recycle();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.PNG, 100, output);
        cropped.recycle();
        return output.toByteArray();
    }

    boolean accepts(String candidate) {
        String expected = token;
        return expected != null && expected.equals(candidate);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID,
            "屏幕截图会话",
            NotificationManager.IMPORTANCE_LOW
        ));
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Media Extended 截图助手")
            .setContentText("截图授权会话正在运行")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build())
            .build();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) server.close();
        if (display != null) display.release();
        if (latestImage != null) latestImage.close();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (pendingCapture != null) pendingCapture.completeExceptionally(
            new IllegalStateException("capture session stopped")
        );
        if (imageThread != null) imageThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class ActivityResultCodes {
        static final int OK = -1;
        static final int CANCELED = 0;
    }
}
