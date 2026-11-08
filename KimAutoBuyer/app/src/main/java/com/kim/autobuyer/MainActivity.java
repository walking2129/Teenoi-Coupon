package com.kim.autobuyer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.List;

public class MainActivity extends Activity {
    private EditText phoneInput;
    private EditText passwordInput;
    private EditText passportNumberInput;
    private CheckBox rememberBox;
    private TextView statusView;
    private AppUpdater appUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        loadCredentials();
        refreshStatus();
        appUpdater = new AppUpdater(this);
    }

    private void buildScreen() {
        int pad = dp(18);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(0xFFF6F7F9);

        TextView title = new TextView(this);
        title.setText("Kim Auto Buyer");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF202124);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("ตัวช่วยเข้าสู่ระบบ Makro PRO และไปยังหน้าเลือก Passport");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF5F6368);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        page.addView(subtitle);

        page.addView(label("เบอร์โทรศัพท์หรืออีเมล"));
        phoneInput = new EditText(this);
        phoneInput.setSingleLine(true);
        phoneInput.setTextSize(16);
        phoneInput.setHint("กรอกเบอร์โทรหรืออีเมล");
        phoneInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        page.addView(phoneInput, matchWrap());

        page.addView(label("รหัสผ่าน"));
        passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setTextSize(16);
        passwordInput.setHint("กรอกรหัสผ่าน Makro PRO");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(passwordInput, matchWrap());

        page.addView(label("Passport number (เลขจากพาสปอร์ตจริง)"));
        passportNumberInput = new EditText(this);
        passportNumberInput.setSingleLine(true);
        passportNumberInput.setTextSize(16);
        passportNumberInput.setHint("คีย์เลข Passport number ของคุณ");
        passportNumberInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        page.addView(passportNumberInput, matchWrap());

        rememberBox = new CheckBox(this);
        rememberBox.setText("บันทึกข้อมูลเข้าสู่ระบบในเครื่องนี้แบบเข้ารหัส");
        rememberBox.setChecked(true);
        rememberBox.setTextSize(14);
        page.addView(rememberBox);

        Button permission = button("1. เปิดการตั้งค่าสิทธิ์ Accessibility");
        page.addView(permission, buttonParams());
        permission.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button start = button("2. เริ่มทำงานกับ Makro PRO");
        page.addView(start, buttonParams());
        start.setOnClickListener(v -> startAutomation());

        TextView learningTitle = label("โหมดเรียนรู้จากการสาธิต (กดมือเอง)");
        page.addView(learningTitle);
        Button learnStart = button("เริ่มบันทึกการสาธิต");
        page.addView(learnStart, buttonParams());
        learnStart.setOnClickListener(v -> startLearning());

        Button learnStop = button("หยุดบันทึกการสาธิต");
        learnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF777777));
        page.addView(learnStop, buttonParams());
        learnStop.setOnClickListener(v -> stopLearning());

        Button learnExport = button("ส่งออกข้อมูลการสาธิต");
        page.addView(learnExport, buttonParams());
        learnExport.setOnClickListener(v -> exportLearning());

        Button learnClear = button("ล้างข้อมูลการสาธิต");
        page.addView(learnClear, buttonParams());
        learnClear.setOnClickListener(v -> {
            LearningRecorder.clear(this);
            Toast.makeText(this, "ล้างข้อมูลการสาธิตแล้ว", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        Button stop = button("หยุดการทำงาน");
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF777777));
        page.addView(stop, buttonParams());
        stop.setOnClickListener(v -> {
            getSharedPreferences("state", MODE_PRIVATE).edit()
                    .putBoolean("running", false).putString("status", "หยุดการทำงานแล้ว").apply();
            refreshStatus();
            Toast.makeText(this, "หยุดการทำงานแล้ว", Toast.LENGTH_SHORT).show();
        });

        Button update = button("ตรวจสอบและติดตั้งอัปเดต");
        page.addView(update, buttonParams());
        update.setOnClickListener(v -> appUpdater.checkForUpdates());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF202124);
        statusView.setPadding(0, dp(18), 0, 0);
        page.addView(statusView);

        TextView note = new TextView(this);
        note.setText("วิธีใช้: บันทึกข้อมูล → เปิดสิทธิ์ Accessibility → กลับมาหน้านี้แล้วกดเริ่ม แอปจะตรวจจับหน้าจอและรอจนแต่ละหน้าพร้อมก่อนทำขั้นตอนถัดไป");
        note.setTextSize(13);
        note.setTextColor(0xFF5F6368);
        note.setPadding(0, dp(16), 0, 0);
        page.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(0xFF333333);
        v.setPadding(0, dp(12), 0, dp(4));
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(8);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private android.content.SharedPreferences encryptedPrefs() throws Exception {
        MasterKey key = new MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        return EncryptedSharedPreferences.create(
                this, "secure_credentials", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private void loadCredentials() {
        try {
            android.content.SharedPreferences p = encryptedPrefs();
            phoneInput.setText(p.getString("phone", ""));
            passwordInput.setText(p.getString("password", ""));
            passportNumberInput.setText(p.getString("passport_number", ""));
        } catch (Exception e) {
            Toast.makeText(this, "เปิดพื้นที่เก็บข้อมูลเข้ารหัสไม่ได้: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveCredentials() throws Exception {
        android.content.SharedPreferences p = encryptedPrefs();
        if (rememberBox.isChecked()) {
            p.edit().putString("phone", phoneInput.getText().toString().trim())
                    .putString("password", passwordInput.getText().toString())
                    .putString("passport_number", passportNumberInput.getText().toString().trim())
                    .apply();
        } else {
            p.edit().clear().apply();
        }
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = new ComponentName(this, BuyerAccessibilityService.class)
                .flattenToString();
        return enabled != null && enabled.toLowerCase().contains(component.toLowerCase());
    }

    private void startAutomation() {
        if (getSharedPreferences("state", MODE_PRIVATE)
                .getBoolean("learning_recording", false)) {
            Toast.makeText(this, "กรุณาหยุดโหมดเรียนรู้ก่อนเริ่มระบบอัตโนมัติ",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String phone = phoneInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String passportNumber = passportNumberInput.getText().toString().trim();
        if (phone.isEmpty() || password.isEmpty() || passportNumber.isEmpty()) {
            Toast.makeText(this,
                    "กรอกเบอร์/อีเมล รหัสผ่าน และ Passport number จริงก่อนเริ่ม",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            saveCredentials();
        } catch (Exception e) {
            Toast.makeText(this, "บันทึกข้อมูลเข้ารหัสไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return;
        }
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "กรุณาเปิดสิทธิ์ Kim Auto Buyer ในหน้า Accessibility ก่อน", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        ResolveInfo makro = findMakroLauncher();
        if (makro == null || makro.activityInfo == null) {
            getSharedPreferences("state", MODE_PRIVATE).edit()
                    .putBoolean("running", true).putInt("step", 0)
                    .putString("target_package", "").putString("status", "เปิด Makro PRO ด้วยตัวเอง").apply();
            Toast.makeText(this, "ไม่พบไอคอน Makro อัตโนมัติ ให้เปิดแอป Makro PRO ด้วยตัวเอง", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }

        String pkg = makro.activityInfo.packageName;
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("running", true).putInt("step", 0)
                .putLong("passport_clicked_at", 0L)
                .putInt("passport_attempts", 0)
                .putInt("form_scrolls", 0)
                .putBoolean("passport_number_filled", false)
                .putBoolean("dob_filled", false)
                .putBoolean("referrer_filled", false)
                .putBoolean("address_filled", false)
                .putBoolean("zip_filled", false)
                .putBoolean("district_selected", false)
                .putBoolean("subdistrict_selected", false)
                .putBoolean("address_overlay_requested", false)
                .putBoolean("address_save_requested", false)
                .putBoolean("dob_focus_requested", false)
                .putBoolean("address_focus_requested", false)
                .putBoolean("zip_focus_requested", false)
                .putBoolean("district_open_requested", false)
                .putBoolean("district_select_pending", false)
                .putBoolean("subdistrict_open_requested", false)
                .putBoolean("subdistrict_select_pending", false)
                .putBoolean("search_filled", false)
                .putBoolean("cart_address_saved", false)
                .putBoolean("coupon_choice_clicked", false)
                .putBoolean("coupon_applied", false)
                .putBoolean("qty_click_pending", false)
                .putBoolean("back_requested", false)
                .putBoolean("apply_clicked", false)
                .putInt("login_attempts", 0)
                .putLong("login_clicked_at", 0L)
                .putLong("dob_focus_requested_at", 0L)
                .putLong("back_requested_at", 0L)
                .putLong("cart_open_clicked_at", 0L)
                .putLong("cart_address_opened_at", 0L)
                .putLong("cart_address_saved_at", 0L)
                .putLong("coupon_opened_at", 0L)
                .putLong("coupon_applied_at", 0L)
                .putLong("address_open_clicked_at", 0L)
                .putLong("address_save_clicked_at", 0L)
                .putLong("qty_click_at", 0L)
                .putInt("qty_before", -1)
                .putInt("qty_expected", -1)
                .putInt("qty_product_index", 0)
                .putInt("qty_click_attempts", 0)
                .putInt("address_open_attempts", 0)
                .putInt("district_open_attempts", 0)
                .putInt("subdistrict_open_attempts", 0)
                .putInt("address_scrolls", 0)
                .remove("form_scroll_fingerprint")
                .remove("address_scroll_fingerprint")
                .remove("district_popup_fingerprint")
                .remove("subdistrict_popup_fingerprint")
                .remove("district_selected_value")
                .remove("subdistrict_selected_value")
                .putString("target_package", pkg)
                .putString("status", "กำลังเปิด Makro PRO").apply();
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } else {
            Toast.makeText(this, "เปิด Makro PRO ไม่สำเร็จ กรุณาเปิดแอปด้วยตัวเอง", Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void startLearning() {
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "กรุณาเปิดสิทธิ์ Accessibility ก่อนเริ่มบันทึก",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("running", false)
                .putBoolean("learning_recording", true)
                .putString("status", "กำลังบันทึกการสาธิต — กดมือใน Makro PRO ได้เลย")
                .apply();
        LearningRecorder.clear(this);
        Toast.makeText(this,
                "เริ่มบันทึกแล้ว: ทำขั้นตอนใน Makro PRO ด้วยมือทีละขั้น",
                Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void stopLearning() {
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("learning_recording", false)
                .putString("status", "หยุดบันทึกการสาธิตแล้ว ขนาดไฟล์ " +
                        LearningRecorder.size(this) + " ไบต์")
                .apply();
        refreshStatus();
    }

    private void exportLearning() {
        java.io.File f = LearningRecorder.file(this);
        if (!f.exists() || f.length() == 0L) {
            Toast.makeText(this, "ยังไม่มีข้อมูลการสาธิตให้ส่งออก", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/jsonl");
            share.putExtra(Intent.EXTRA_SUBJECT, "Kim Auto Buyer manual demonstration");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "ส่งออกข้อมูลการสาธิต"));
        } catch (Exception e) {
            Toast.makeText(this, "ส่งออกข้อมูลไม่สำเร็จ: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private ResolveInfo findMakroLauncher() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo info : apps) {
            if (info.activityInfo == null) continue;
            CharSequence label = info.loadLabel(pm);
            String name = label == null ? "" : label.toString().toLowerCase();
            String pkg = info.activityInfo.packageName.toLowerCase();
            if ((name.contains("makro") || name.contains("แม็คโคร")) &&
                    !pkg.equals(getPackageName())) return info;
        }
        return null;
    }

    private void refreshStatus() {
        if (statusView == null) return;
        android.content.SharedPreferences s = getSharedPreferences("state", MODE_PRIVATE);
        String status = s.getString("status", "พร้อมเริ่ม");
        boolean running = s.getBoolean("running", false);
        String debug = s.getString("last_debug", "ยังไม่มีข้อมูลหน้าจอ");
        boolean learning = s.getBoolean("learning_recording", false);
        statusView.setText("สถานะ: " + status +
                (running ? "\nระบบกำลังตรวจจับหน้าจอ" : "") +
                (learning ? "\nโหมดเรียนรู้กำลังบันทึก" : "") +
                "\n\nข้อมูลหน้าจอล่าสุด: " + debug);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (appUpdater != null) appUpdater.resumePendingInstall();
    }

    @Override
    protected void onDestroy() {
        if (appUpdater != null) appUpdater.close();
        super.onDestroy();
    }
}
