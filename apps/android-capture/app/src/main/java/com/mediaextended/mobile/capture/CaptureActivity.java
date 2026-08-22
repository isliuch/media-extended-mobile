package com.mediaextended.mobile.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class CaptureActivity extends Activity {
    private static final int CAPTURE_REQUEST = 4101;
    private static final int NOTIFICATION_REQUEST = 4100;
    private String sessionToken;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showInstructions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void showInstructions() {
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setPadding(48, 48, 48, 48);
        text.setTextSize(18);
        text.setText("Media Extended 截图助手\n\n请从 Obsidian 的媒体页面点击“辅助 APK 一键截图”。\n\n授权后通知栏会显示正在运行的截图会话，可随时点“停止”。");
        setContentView(text);
    }

    private void handleIntent(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !"mxextendedcapture".equals(uri.getScheme())) return;
        sessionToken = uri.getQueryParameter("token");
        if (sessionToken == null || !sessionToken.matches("[a-f0-9]{32,128}")) {
            new AlertDialog.Builder(this)
                .setMessage("截图请求无效，请返回 Obsidian 后重试。")
                .setPositiveButton("确定", null)
                .show();
            return;
        }

        if (CaptureService.isReady()) {
            CaptureService.updateToken(sessionToken);
            returnToObsidian();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                NOTIFICATION_REQUEST
            );
            return;
        }
        requestProjectionAuthorization();
    }

    private void requestProjectionAuthorization() {
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) requestProjectionAuthorization();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != RESULT_OK || data == null || sessionToken == null) {
            returnToObsidian();
            return;
        }

        Intent service = new Intent(this, CaptureService.class)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_TOKEN, sessionToken);
        startForegroundService(service);
        returnToObsidian();
    }

    private void returnToObsidian() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("md.obsidian");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
        }
        finish();
    }
}
