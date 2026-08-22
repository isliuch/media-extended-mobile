package com.mediaextended.mobile.capture;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaptureTimeRecognizer implements Closeable {
    private static final Pattern TIMESTAMP = Pattern.compile(
        "(?<!\\d)(?:(\\d{1,2})[:：])?(\\d{1,3})[:：](\\d{2})(?!\\d)"
    );
    private final TextRecognizer recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    );

    Long recognize(byte[] png, Map<String, String> query) throws Exception {
        if (!"1".equals(query.get("recognizeTime"))) return null;
        Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (full == null) return null;
        Bitmap controls = null;
        try {
            Rect player = playerRect(full, query);
            if (player == null) return null;

            int controlsTop = player.top + Math.round(player.height() * 0.62f);
            controls = Bitmap.createBitmap(
                full,
                player.left,
                controlsTop,
                player.width(),
                player.bottom - controlsTop
            );
            Text text = Tasks.await(
                recognizer.process(InputImage.fromBitmap(controls, 0)),
                7,
                TimeUnit.SECONDS
            );
            return chooseCurrentTime(text, controls.getWidth(), controls.getHeight());
        } finally {
            if (controls != null) controls.recycle();
            full.recycle();
        }
    }

    private Rect playerRect(Bitmap image, Map<String, String> query) {
        double viewportWidth = number(query.get("viewportWidth"));
        double viewportHeight = number(query.get("viewportHeight"));
        double left = number(query.get("left"));
        double top = number(query.get("top"));
        double width = number(query.get("width"));
        double height = number(query.get("height"));
        if (
            !Double.isFinite(viewportWidth) ||
            !Double.isFinite(viewportHeight) ||
            !Double.isFinite(left) ||
            !Double.isFinite(top) ||
            !Double.isFinite(width) ||
            !Double.isFinite(height) ||
            viewportWidth <= 0 ||
            viewportHeight <= 0 ||
            width <= 0 ||
            height <= 0
        ) {
            return null;
        }

        int x = clamp((int) Math.round(left / viewportWidth * image.getWidth()), 0, image.getWidth() - 1);
        int y = clamp((int) Math.round(top / viewportHeight * image.getHeight()), 0, image.getHeight() - 1);
        int right = clamp(
            (int) Math.round((left + width) / viewportWidth * image.getWidth()),
            x + 1,
            image.getWidth()
        );
        int bottom = clamp(
            (int) Math.round((top + height) / viewportHeight * image.getHeight()),
            y + 1,
            image.getHeight()
        );
        return right > x && bottom > y ? new Rect(x, y, right, bottom) : null;
    }

    private Long chooseCurrentTime(Text text, int width, int height) {
        List<Candidate> candidates = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || box.left > width * 0.62f || box.top < height * 0.18f) continue;
                Matcher matcher = TIMESTAMP.matcher(line.getText());
                List<Long> lineTimes = new ArrayList<>();
                while (matcher.find()) {
                    Long seconds = parseSeconds(matcher);
                    if (seconds != null) lineTimes.add(seconds);
                }
                if (lineTimes.isEmpty()) continue;
                long current = lineTimes.get(0);
                if (lineTimes.size() > 1 && current > lineTimes.get(1)) continue;
                int confidence = lineTimes.size() > 1 ? 2 : 1;
                candidates.add(new Candidate(current, box.left, box.top, confidence));
            }
        }
        return candidates.stream()
            .sorted(
                Comparator.comparingInt((Candidate candidate) -> -candidate.confidence)
                    .thenComparingInt(candidate -> candidate.left)
                    .thenComparingInt(candidate -> -candidate.top)
            )
            .map(candidate -> candidate.seconds)
            .findFirst()
            .orElse(null);
    }

    private Long parseSeconds(Matcher matcher) {
        try {
            long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            if (seconds >= 60 || (matcher.group(1) != null && minutes >= 60)) return null;
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    @Override
    public void close() {
        recognizer.close();
    }

    private static final class Candidate {
        final long seconds;
        final int left;
        final int top;
        final int confidence;

        Candidate(long seconds, int left, int top, int confidence) {
            this.seconds = seconds;
            this.left = left;
            this.top = top;
            this.confidence = confidence;
        }
    }
}
