package com.kim.autobuyer;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BuyerAccessibilityService extends AccessibilityService {
    private static final int PHONE = 0;
    private static final int PASSWORD = 1;
    private static final int CARD = 2;
    private static final int PERSONAL = 3;
    private static final int PASSPORT = 4;
    private static final int DONE = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickQueued = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        scheduleTick(500);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isRunning()) scheduleTick(350);
    }

    @Override
    public void onInterrupt() {
        setStatus("ระบบ Accessibility ถูกหยุดชั่วคราว");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private android.content.SharedPreferences state() {
        return getSharedPreferences("state", MODE_PRIVATE);
    }

    private boolean isRunning() {
        return state().getBoolean("running", false);
    }

    private void scheduleTick(long delay) {
        if (!isRunning() || tickQueued) return;
        tickQueued = true;
        handler.postDelayed(() -> {
            tickQueued = false;
            runStep();
        }, delay);
    }

    private void runStep() {
        if (!isRunning()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleTick(900);
            return;
        }

        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        String target = state().getString("target_package", "");
        if (!target.isEmpty() && !target.equals(pkg)) {
            // Android only exposes an actionable accessibility tree for the foreground window.
            scheduleTick(1200);
            return;
        }
        if (target.isEmpty()) {
            String all = collectText(root).toLowerCase(Locale.ROOT);
            if (pkg.toLowerCase(Locale.ROOT).contains("makro") ||
                    all.contains("makro") || all.contains("แม็คโคร")) {
                state().edit().putString("target_package", pkg).apply();
            } else {
                scheduleTick(1000);
                return;
            }
        }

        int step = state().getInt("step", PHONE);
        switch (step) {
            case PHONE:
                doPhone(root);
                break;
            case PASSWORD:
                doPassword(root);
                break;
            case CARD:
                doCard(root);
                break;
            case PERSONAL:
                doPersonal(root);
                break;
            case PASSPORT:
                doPassport(root);
                break;
            default:
                state().edit().putBoolean("running", false).putString("status",
                        "ถึงหน้า Passport แล้ว").apply();
                break;
        }

        if (isRunning()) scheduleTick(750);
    }

    private void doPhone(AccessibilityNodeInfo root) {
        if (hasPasswordField(root) || hasAny(root, "password", "รหัสผ่าน")) {
            setStep(PASSWORD, "พบหน้ากรอกรหัสผ่าน");
            return;
        }
        if (!hasAny(root, "mobile number", "phone number", "email", "เบอร์โทร",
                "หมายเลขมือถือ", "อีเมล", "เข้าสู่ระบบ", "log in", "login")) return;

        String phone = readCredentials("phone");
        if (phone.isEmpty()) {
            stopWith("ไม่พบเบอร์โทร/อีเมลที่บันทึกไว้");
            return;
        }
        AccessibilityNodeInfo field = findEditable(root, false);
        if (field != null) setNodeText(field, phone);
        AccessibilityNodeInfo next = findText(root, "continue", "ดำเนินการต่อ");
        if (next != null && clickNode(next)) {
            setStep(PASSWORD, "กรอกเบอร์/อีเมลแล้ว กำลังรอหน้ารหัสผ่าน");
        }
    }

    private void doPassword(AccessibilityNodeInfo root) {
        if (hasAny(root, "apply for makro card", "สมัคร makro card")) {
            setStep(CARD, "เข้าสู่ระบบแล้ว");
            return;
        }
        if (!hasPasswordField(root) && !hasAny(root, "password", "รหัสผ่าน")) return;
        String password = readCredentials("password");
        if (password.isEmpty()) {
            stopWith("ไม่พบรหัสผ่านที่บันทึกไว้");
            return;
        }
        AccessibilityNodeInfo field = findEditable(root, true);
        if (field == null) return;
        setNodeText(field, password);
        AccessibilityNodeInfo login = findLoginButton(root);
        if (login != null && clickNode(login)) {
            setStep(CARD, "ส่งคำสั่งเข้าสู่ระบบแล้ว กำลังรอหน้าแรก");
        }
    }

    private void doCard(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo apply = findText(root,
                "apply for makro card", "สมัคร makro card", "สมัครบัตรแม็คโคร");
        if (apply != null && clickNode(apply)) {
            setStep(PERSONAL, "เลือกเมนูสมัคร Makro Card แล้ว");
            return;
        }
        if (hasAny(root, "personal", "บุคคลธรรมดา") &&
                hasAny(root, "passport", "หนังสือเดินทาง")) {
            setStep(PERSONAL, "พบหน้าเลือกประเภทข้อมูล");
        }
    }

    private void doPersonal(AccessibilityNodeInfo root) {
        if (hasAny(root, "passport", "หนังสือเดินทาง") &&
                !hasAny(root, "select type", "choose type", "เลือกประเภท")) {
            setStep(PASSPORT, "พบตัวเลือก Passport");
            return;
        }
        AccessibilityNodeInfo personal = findText(root, "personal", "บุคคลธรรมดา");
        if (personal != null && clickNode(personal)) {
            setStep(PASSPORT, "เลือก Personal แล้ว กำลังรอตัวเลือก Passport");
        }
    }

    private void doPassport(AccessibilityNodeInfo root) {
        long clickedAt = state().getLong("passport_clicked_at", 0L);
        if (clickedAt > 0L) {
            if (System.currentTimeMillis() - clickedAt > 1800L) {
                state().edit().putBoolean("running", false)
                        .putInt("step", DONE)
                        .putString("status", "ถึงหน้า Passport แล้ว ระบบหยุดตามที่กำหนด").apply();
                Toast.makeText(this, "ถึงหน้า Passport แล้ว", Toast.LENGTH_LONG).show();
            }
            return;
        }
        AccessibilityNodeInfo passport = findText(root, "passport", "หนังสือเดินทาง");
        if (passport != null && clickNode(passport)) {
            state().edit().putLong("passport_clicked_at", System.currentTimeMillis())
                    .putString("status", "เลือก Passport แล้ว กำลังรอหน้าแบบฟอร์ม").apply();
        }
    }

    private boolean hasPasswordField(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : editableNodes(root)) {
            int t = n.getInputType();
            if ((t & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
                    (t & InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0 ||
                    textOf(n).toLowerCase(Locale.ROOT).contains("password")) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root, boolean password) {
        List<AccessibilityNodeInfo> nodes = editableNodes(root);
        for (AccessibilityNodeInfo n : nodes) {
            String hint = textOf(n).toLowerCase(Locale.ROOT);
            int type = n.getInputType();
            boolean isPass = (type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
                    (type & InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0 ||
                    hint.contains("password") || hint.contains("รหัสผ่าน");
            if (password == isPass) return n;
        }
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private List<AccessibilityNodeInfo> editableNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collectEditable(root, out, 0);
        return out;
    }

    private void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 35) return;
        if (n.isEditable()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out, depth + 1);
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String value) {
        CharSequence existing = node.getText();
        if (existing != null && existing.toString().equals(value)) return true;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findLoginButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            String t = textOf(n).toLowerCase(Locale.ROOT).trim();
            if (t.contains("otp") || t.contains("one time password")) continue;
            if (t.equals("login") || t.equals("log in") || t.equals("เข้าสู่ระบบ") ||
                    t.equals("ลงชื่อเข้าใช้") || t.equals("ล็อกอิน")) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String... matches) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            String t = textOf(n).toLowerCase(Locale.ROOT).trim();
            if (t.isEmpty()) continue;
            for (String m : matches) {
                String q = m.toLowerCase(Locale.ROOT);
                if (t.equals(q) || t.contains(q)) return n;
            }
        }
        return null;
    }

    private boolean hasAny(AccessibilityNodeInfo root, String... matches) {
        return findText(root, matches) != null;
    }

    private void collectNodes(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 35) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectNodes(n.getChild(i), out, depth + 1);
    }

    private String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder b = new StringBuilder();
        if (n.getText() != null) b.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) b.append(n.getContentDescription()).append(' ');
        if (android.os.Build.VERSION.SDK_INT >= 26 && n.getHintText() != null)
            b.append(n.getHintText()).append(' ');
        return b.toString();
    }

    private String collectText(AccessibilityNodeInfo root) {
        StringBuilder b = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) b.append(textOf(n)).append(' ');
        return b.toString();
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 7; i++) {
            if (current.isEnabled() && current.isClickable() &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    private String readCredentials(String key) {
        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            android.content.SharedPreferences p = EncryptedSharedPreferences.create(
                    this, "secure_credentials", masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            return p.getString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private void setStep(int step, String message) {
        state().edit().putInt("step", step).putString("status", message).apply();
    }

    private void setStatus(String message) {
        state().edit().putString("status", message).apply();
    }

    private void stopWith(String message) {
        state().edit().putBoolean("running", false).putString("status", message).apply();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
