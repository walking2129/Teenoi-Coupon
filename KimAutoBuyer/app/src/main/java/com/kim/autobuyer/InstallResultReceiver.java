package com.kim.autobuyer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_RESULT = "com.kim.autobuyer.INSTALL_RESULT";
    public static final String EXTRA_SESSION_ID = "install_session_id";
    public static final String EXTRA_VERSION = "install_version";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if (Build.VERSION.SDK_INT >= 33) {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirmation = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            } else {
                Toast.makeText(context,
                        "Android ต้องการให้ยืนยันการติดตั้งอัปเดตด้วยตนเอง",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            context.getSharedPreferences("kim_auto_updater", Context.MODE_PRIVATE).edit()
                    .remove("download_id")
                    .remove("download_version")
                    .remove("download_version_name")
                    .remove("download_sha256")
                    .putBoolean("install_launched", false)
                    .putBoolean("needs_install_permission", false)
                    .apply();
            Toast.makeText(context, "อัปเดต Kim Auto Buyer สำเร็จแล้ว",
                    Toast.LENGTH_LONG).show();
            return;
        }

        context.getSharedPreferences("kim_auto_updater", Context.MODE_PRIVATE).edit()
                .putBoolean("install_launched", false)
                .putString("last_install_error", message == null ? "" : message)
                .apply();
        String detail = (message == null || message.trim().isEmpty())
                ? "รหัสข้อผิดพลาด " + status : message;
        Toast.makeText(context, "Android ติดตั้งอัปเดตไม่สำเร็จ: " + detail,
                Toast.LENGTH_LONG).show();
    }
}
