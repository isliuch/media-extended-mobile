package com.mediaextended.mobile.capture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PlayerControlAccessibilityService extends AccessibilityService {
    private static volatile PlayerControlAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static boolean isReady() {
        return instance != null;
    }

    static boolean revealPlayerControls(Map<String, String> query) {
        PlayerControlAccessibilityService service = instance;
        return service != null && service.tapPlayer(query);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    private boolean tapPlayer(Map<String, String> query) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
            || !"md.obsidian".contentEquals(root.getPackageName())) {
            return false;
        }

        Rect player = playerRect(query);
        if (player == null) return false;

        // Avoid the centre play/pause button and the bottom control buttons.
        float x = player.left + player.width() * 0.5f;
        float y = player.top + player.height() * 0.28f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
            .build();
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        mainHandler.post(() -> {
            boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                        completed.complete(true);
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                        completed.complete(false);
                    }
                },
                mainHandler
            );
            if (!accepted) completed.complete(false);
        });
        try {
            return completed.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Rect playerRect(Map<String, String> query) {
        double viewportWidth = number(query.get("viewportWidth"));
        double viewportHeight = number(query.get("viewportHeight"));
        double left = number(query.get("left"));
        double top = number(query.get("top"));
        double width = number(query.get("width"));
        double height = number(query.get("height"));
        if (!Double.isFinite(viewportWidth) || !Double.isFinite(viewportHeight)
            || !Double.isFinite(left) || !Double.isFinite(top)
            || !Double.isFinite(width) || !Double.isFinite(height)
            || viewportWidth <= 0 || viewportHeight <= 0 || width <= 0 || height <= 0) {
            return null;
        }

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int x = clamp((int) Math.round(left / viewportWidth * screenWidth), 0, screenWidth - 1);
        int y = clamp((int) Math.round(top / viewportHeight * screenHeight), 0, screenHeight - 1);
        int right = clamp(
            (int) Math.round((left + width) / viewportWidth * screenWidth),
            x + 1,
            screenWidth
        );
        int bottom = clamp(
            (int) Math.round((top + height) / viewportHeight * screenHeight),
            y + 1,
            screenHeight
        );
        return right > x && bottom > y ? new Rect(x, y, right, bottom) : null;
    }

    private double number(String value) {
        if (value == null) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
