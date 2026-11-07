package com.kim.autobuyer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
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
    private static final int PASSPORT_FORM = 5;
    private static final int ADDRESS = 6;
    private static final int APPLY = 7;
    private static final int DONE = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickQueued = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        scheduleTick(600);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isRunning()) scheduleTick(450);
    }

    @Override
    public void onInterrupt() {
        setStatus("Accessibility ถูกหยุดชั่วคราว");
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
            scheduleTick(1000);
            return;
        }

        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        String target = state().getString("target_package", "");
        if (!target.isEmpty() && !target.equals(pkg)) {
            setStatus("กำลังรอ Makro PRO อยู่ด้านหน้า");
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
        saveScreenDiagnostics(root, step);
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
            case PASSPORT_FORM:
                doPassportForm(root);
                break;
            case ADDRESS:
                doAddress(root);
                break;
            case APPLY:
                doApply(root);
                break;
            default:
                state().edit().putBoolean("running", false)
                        .putString("status", "จบขั้นตอนที่กำหนดแล้ว").apply();
                break;
        }

        if (isRunning()) scheduleTick(850);
    }

    // Step 1: open the top-right Login entry on the Makro home screen,
    // then fill the phone/email form once it appears.
    private void doPhone(AccessibilityNodeInfo root) {
        if (hasPasswordField(root) || hasAny(root, "password", "รหัสผ่าน")) {
            setStep(PASSWORD, "พบหน้ารหัสผ่าน");
            return;
        }

        boolean phoneForm = hasAny(root, "mobile number", "phone number", "email address",
                "enter phone", "เบอร์โทร", "หมายเลขมือถือ", "อีเมล", "เบอร์โทรศัพท์");
        AccessibilityNodeInfo field = findEditable(root, false);
        if (phoneForm || (field != null && hasAny(root, "continue", "ดำเนินการต่อ", "ถัดไป"))) {
            String phone = readCredentials("phone");
            if (phone.isEmpty()) {
                stopWith("ไม่พบเบอร์โทร/อีเมลที่บันทึกไว้");
                return;
            }
            if (field != null) setNodeText(field, phone);
            if (clickAction(root, new String[]{"continue", "ดำเนินการต่อ", "ถัดไป", "ยืนยัน"}, true)) {
                setStep(PASSWORD, "กรอกเบอร์/อีเมลแล้ว กด Continue แล้ว กำลังรอหน้ารหัสผ่าน");
            } else {
                setStatus("กรอกเบอร์แล้ว แต่ยังหาปุ่ม Continue ไม่พบ — เก็บหน้าจอไว้ตรวจสอบ");
            }
            return;
        }

        if (clickAction(root, new String[]{"login", "log in", "sign in", "เข้าสู่ระบบ",
                "ลงชื่อเข้าใช้", "ล็อกอิน"}, false)) {
            setStatus("กดทางเข้า Login แล้ว กำลังรอหน้ากรอกเบอร์/อีเมล");
            return;
        }

        // Some home-screen builds expose the top-right Login control without a label.
        if (tapTopRightLogin(root)) {
            setStatus("แตะบริเวณปุ่ม Login มุมขวาบนแล้ว กำลังรอหน้ากรอกเบอร์/อีเมล");
        }
    }

    private void doPassword(AccessibilityNodeInfo root) {
        if (hasAny(root, "apply for makro card", "สมัคร makro card", "สมัครบัตรแม็คโคร")) {
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
        if (field != null) setNodeText(field, password);

        if (clickAction(root, new String[]{"login", "log in", "sign in", "เข้าสู่ระบบ",
                "ลงชื่อเข้าใช้", "ล็อกอิน"}, true)) {
            setStep(CARD, "กรอกรหัสผ่านแล้ว กด Login แล้ว กำลังรอหน้าแรก");
        } else {
            setStatus("กรอกรหัสผ่านแล้ว แต่ยังหาปุ่ม Login ไม่พบ");
        }
    }

    private void doCard(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo apply = findText(root,
                "apply for makro card", "สมัคร makro card", "สมัครบัตรแม็คโคร");
        if (apply != null && clickNodeOrTap(apply)) {
            setStep(PERSONAL, "กดเมนูสมัคร Makro Card แล้ว");
            return;
        }
        if (hasAny(root, "personal", "บุคคลธรรมดา") &&
                hasAny(root, "passport", "หนังสือเดินทาง")) {
            setStep(PERSONAL, "พบหน้าตัวเลือก Personal และ Passport");
        }
    }

    private void doPersonal(AccessibilityNodeInfo root) {
        // The old implementation jumped straight to PASSPORT whenever the word
        // "Passport" was visible, even when Personal and Passport were both options.
        AccessibilityNodeInfo personal = findText(root, "personal", "บุคคลธรรมดา");
        if (personal != null) {
            if (clickNodeOrTap(personal)) {
                setStep(PASSPORT, "เลือก Personal แล้ว กำลังหาตัวเลือก Passport");
            } else {
                setStatus("พบ Personal แต่แตะเลือกไม่สำเร็จ");
            }
            return;
        }
        if (hasAny(root, "passport", "หนังสือเดินทาง")) {
            setStep(PASSPORT, "พบหน้าหรือรายการเลือก Passport");
        }
    }

    private void doPassport(AccessibilityNodeInfo root) {
        if (isPassportForm(root)) {
            setStep(PASSPORT_FORM, "เปิดแบบฟอร์ม Passport แล้ว กำลังกรอกวันเกิดและรหัสผู้แนะนำ");
            return;
        }

        long clickedAt = state().getLong("passport_clicked_at", 0L);
        int attempts = state().getInt("passport_attempts", 0);
        if (clickedAt > 0L && System.currentTimeMillis() - clickedAt < 2500L) return;
        if (clickedAt > 0L && attempts >= 4) {
            stopWith("พบคำว่า Passport แต่ยืนยันการเปิดแบบฟอร์มไม่ได้ กรุณาตรวจหน้าจอ");
            return;
        }

        AccessibilityNodeInfo passport = findText(root, "passport", "หนังสือเดินทาง");
        if (passport != null && clickNodeOrTap(passport)) {
            state().edit()
                    .putLong("passport_clicked_at", System.currentTimeMillis())
                    .putInt("passport_attempts", attempts + 1)
                    .putString("status", "แตะเลือก Passport แล้ว กำลังตรวจว่าฟอร์มเปิดจริงหรือไม่")
                    .apply();
        } else {
            setStatus("ยังไม่พบปุ่ม/ตัวเลือก Passport ที่แตะได้");
        }
    }

    private boolean isPassportForm(AccessibilityNodeInfo root) {
        return hasAny(root, "passport number", "passport no", "passport details",
                "เลขที่หนังสือเดินทาง", "หมายเลขหนังสือเดินทาง",
                "วันหมดอายุหนังสือเดินทาง", "passport expiry", "date of expiry",
                "issuing country", "ประเทศที่ออกหนังสือเดินทาง",
                "date of birth", "referrer code", "referral code");
    }

    private void doPassportForm(AccessibilityNodeInfo root) {
        // Passport number is intentionally left for the user to enter from
        // their real passport. This flow never creates a fabricated ID number.
        boolean dobDone = state().getBoolean("dob_filled", false);
        boolean refDone = state().getBoolean("referrer_filled", false);

        AccessibilityNodeInfo dob = findFieldForLabel(root,
                "date of birth", "dob", "วันเดือนปีเกิด", "วันเกิด");
        if (!dobDone && dob != null && setNodeText(dob, "21/11/1986")) {
            dobDone = true;
            state().edit().putBoolean("dob_filled", true).apply();
        }

        AccessibilityNodeInfo referrer = findFieldForLabel(root,
                "referrer code", "referral code", "referrer", "referral",
                "รหัสผู้แนะนำ", "รหัสแนะนำ");
        if (!refDone && referrer != null && setNodeText(referrer, "WIEX2PIW")) {
            refDone = true;
            state().edit().putBoolean("referrer_filled", true).apply();
        }

        if (dobDone && refDone) {
            AccessibilityNodeInfo addAddress = findExactText(root,
                    "add address", "เพิ่มที่อยู่");
            if (addAddress != null && clickNodeOrTap(addAddress)) {
                setStep(ADDRESS, "กด Add address แล้ว กำลังกรอกที่อยู่");
                return;
            }
        }

        int scrolls = state().getInt("form_scrolls", 0);
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scrolls < 10 &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            state().edit().putInt("form_scrolls", scrolls + 1)
                    .putString("status", dobDone && refDone
                            ? "เลื่อนหา Add address — อย่าลืมกรอก Passport number จริงด้วยตนเอง"
                            : "เลื่อนแบบฟอร์มเพื่อหาช่อง Date of birth และ Referrer code")
                    .apply();
            return;
        }

        if (dobDone && refDone) {
            setStatus("กรอกวันเกิดและ Referrer code แล้ว กรุณากรอก Passport number จริงด้วยตนเอง และรอ/กด Add address");
        } else if (scrolls >= 10) {
            stopWith("พบหน้า Passport แต่หาช่องวันเกิดหรือ Referrer code ไม่ครบ กรุณาตรวจหน้าจอ");
        } else {
            setStatus("พบหน้า Passport แล้ว กำลังรอช่อง Date of birth และ Referrer code");
        }
    }

    private void doAddress(AccessibilityNodeInfo root) {
        boolean addressDone = state().getBoolean("address_filled", false);
        boolean zipDone = state().getBoolean("zip_filled", false);

        AccessibilityNodeInfo address = findFieldForLabel(root,
                "address", "house no", "house number", "ที่อยู่", "บ้านเลขที่");
        if (!addressDone && address != null && setNodeText(address, "888")) {
            addressDone = true;
            state().edit().putBoolean("address_filled", true).apply();
        }

        AccessibilityNodeInfo zip = findFieldForLabel(root,
                "zip code", "postal code", "postcode", "รหัสไปรษณีย์");
        if (!zipDone && zip != null && setNodeText(zip, "20000")) {
            zipDone = true;
            state().edit().putBoolean("zip_filled", true).apply();
        }

        if (!addressDone || !zipDone) {
            scrollAddressForm(root, "กำลังค้นหาช่อง Address และ Zip code");
            return;
        }

        boolean districtDone = state().getBoolean("district_selected", false);
        if (!districtDone) {
            AccessibilityNodeInfo option = findExactText(root,
                    "mueang chon buri", "เมืองชลบุรี");
            if (option != null && clickNodeOrTap(option)) {
                state().edit().putBoolean("district_selected", true)
                        .putString("status", "เลือก District: Mueang Chon Buri แล้ว").apply();
                return;
            }

            AccessibilityNodeInfo control = findControlForLabel(root,
                    "district", "อำเภอ");
            int attempts = state().getInt("district_open_attempts", 0);
            if (control != null && attempts < 3 && clickNodeOrTap(control)) {
                state().edit().putInt("district_open_attempts", attempts + 1)
                        .putString("status", "เปิดรายการ District เพื่อเลือก Mueang Chon Buri").apply();
                return;
            }
            scrollAddressForm(root, "กำลังค้นหารายการ District: Mueang Chon Buri");
            return;
        }

        boolean subdistrictDone = state().getBoolean("subdistrict_selected", false);
        if (!subdistrictDone) {
            AccessibilityNodeInfo option = findExactText(root,
                    "bang pla soi", "bang pla soy", "บางปลาสร้อย");
            if (option != null && clickNodeOrTap(option)) {
                state().edit().putBoolean("subdistrict_selected", true)
                        .putString("status", "เลือก Sub-district: Bang Pla Soi แล้ว").apply();
                return;
            }

            AccessibilityNodeInfo control = findControlForLabel(root,
                    "sub-district", "subdistrict", "ตำบล");
            int attempts = state().getInt("subdistrict_open_attempts", 0);
            if (control != null && attempts < 3 && clickNodeOrTap(control)) {
                state().edit().putInt("subdistrict_open_attempts", attempts + 1)
                        .putString("status", "เปิดรายการ Sub-district เพื่อเลือก Bang Pla Soi").apply();
                return;
            }
            scrollAddressForm(root, "กำลังค้นหารายการ Sub-district: Bang Pla Soi");
            return;
        }

        if (clickExactAction(root, new String[]{"save", "บันทึก"})) {
            setStep(APPLY, "กด Save แล้ว กำลังรอปุ่ม Apply");
        } else {
            setStatus("กรอกที่อยู่และเลือกเขต/ตำบลครบแล้ว แต่ยังไม่พบปุ่ม Save");
        }
    }

    private void doApply(AccessibilityNodeInfo root) {
        if (hasAny(root, "application submitted", "submitted successfully",
                "ส่งใบสมัครเรียบร้อย", "สมัครสำเร็จ")) {
            stopWith("ส่งใบสมัครแล้ว");
            return;
        }

        if (clickExactAction(root, new String[]{"apply", "apply now", "ยื่นใบสมัคร", "สมัคร"})) {
            stopWith("กด Apply แล้ว กรุณาตรวจผลบนหน้าจอ Makro PRO");
            return;
        }

        setStatus("บันทึกที่อยู่แล้ว กำลังรอปุ่ม Apply สีแดง");
    }

    private void scrollAddressForm(AccessibilityNodeInfo root, String message) {
        int scrolls = state().getInt("address_scrolls", 0);
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scrolls < 8 &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            state().edit().putInt("address_scrolls", scrolls + 1)
                    .putString("status", message).apply();
        } else if (scrolls >= 8) {
            stopWith(message + " — ยังไม่พบช่องหรือรายการที่ต้องเลือก กรุณาตรวจหน้าจอ");
        } else {
            setStatus(message);
        }
    }

    private AccessibilityNodeInfo findExactText(AccessibilityNodeInfo root, String... values) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            String text = textOf(node).toLowerCase(Locale.ROOT).trim()
                    .replaceAll("\\s+", " ");
            for (String value : values) {
                String wanted = value.toLowerCase(Locale.ROOT).trim()
                        .replaceAll("\\s+", " ");
                if (text.equals(wanted) || text.startsWith(wanted + " ")) return node;
            }
        }
        return null;
    }

    private boolean clickExactAction(AccessibilityNodeInfo root, String[] values) {
        AccessibilityNodeInfo node = findExactText(root, values);
        return node != null && clickNodeOrTap(node);
    }

    private AccessibilityNodeInfo findControlForLabel(AccessibilityNodeInfo root, String... labels) {
        AccessibilityNodeInfo field = findFieldForLabel(root, labels);
        if (field != null) return field;

        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(root, all, 0);
        for (AccessibilityNodeInfo labelNode : all) {
            if (!labelNode.isVisibleToUser()) continue;
            String labelText = textOf(labelNode).toLowerCase(Locale.ROOT).trim();
            boolean match = false;
            for (String label : labels) {
                if (labelText.equals(label.toLowerCase(Locale.ROOT)) ||
                        labelText.startsWith(label.toLowerCase(Locale.ROOT) + " ")) {
                    match = true;
                    break;
                }
            }
            if (!match) continue;

            AccessibilityNodeInfo parent = labelNode.getParent();
            if (parent != null && parent.isVisibleToUser() &&
                    parent.isEnabled() && parent.isClickable()) return parent;

            Rect labelBounds = new Rect();
            labelNode.getBoundsInScreen(labelBounds);
            AccessibilityNodeInfo best = null;
            long bestScore = Long.MAX_VALUE;
            for (AccessibilityNodeInfo candidate : all) {
                if (candidate == labelNode || !candidate.isVisibleToUser() ||
                        !candidate.isEnabled() || !(candidate.isClickable() || candidate.isEditable())) continue;
                Rect r = new Rect();
                candidate.getBoundsInScreen(r);
                if (r.isEmpty()) continue;
                int gap = r.top - labelBounds.bottom;
                if (gap < -dp(20) || gap > dp(150)) continue;
                int overlap = Math.min(r.right, labelBounds.right) -
                        Math.max(r.left, labelBounds.left);
                if (overlap <= 0 && Math.abs(r.centerX() - labelBounds.centerX()) > dp(110)) continue;
                long score = (long) Math.max(0, gap) * 1000L +
                        Math.abs(r.centerX() - labelBounds.centerX());
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private AccessibilityNodeInfo findFieldForLabel(AccessibilityNodeInfo root, String... labels) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(root, all, 0);
        List<AccessibilityNodeInfo> fields = editableNodes(root);

        // First prefer a text field whose own hint/description names the field.
        for (AccessibilityNodeInfo field : fields) {
            String own = textOf(field).toLowerCase(Locale.ROOT);
            for (String label : labels) {
                if (own.contains(label.toLowerCase(Locale.ROOT))) return field;
            }
        }

        // Otherwise associate the visible field label with the nearest editable
        // control directly below it, preferring horizontal alignment.
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo labelNode : all) {
            if (!labelNode.isVisibleToUser()) continue;
            String labelText = textOf(labelNode).toLowerCase(Locale.ROOT).trim();
            boolean matches = false;
            for (String label : labels) {
                if (labelText.contains(label.toLowerCase(Locale.ROOT))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;

            Rect labelBounds = new Rect();
            labelNode.getBoundsInScreen(labelBounds);
            if (labelBounds.isEmpty()) continue;

            for (AccessibilityNodeInfo field : fields) {
                if (!field.isVisibleToUser() || !field.isEnabled()) continue;
                Rect fieldBounds = new Rect();
                field.getBoundsInScreen(fieldBounds);
                if (fieldBounds.isEmpty()) continue;

                int verticalGap = fieldBounds.top - labelBounds.bottom;
                if (verticalGap < -dp(24) || verticalGap > dp(180)) continue;
                int horizontalOverlap = Math.min(labelBounds.right, fieldBounds.right) -
                        Math.max(labelBounds.left, fieldBounds.left);
                boolean aligned = horizontalOverlap > 0 ||
                        Math.abs(labelBounds.centerX() - fieldBounds.centerX()) < dp(100);
                if (!aligned) continue;

                long score = (long) Math.max(0, verticalGap) * 1000L +
                        Math.abs(labelBounds.centerX() - fieldBounds.centerX());
                if (score < bestScore) {
                    bestScore = score;
                    best = field;
                }
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(root, all, 0);
        for (AccessibilityNodeInfo node : all) {
            if (node.isVisibleToUser() && node.isScrollable() && node.isEnabled()) return node;
        }
        return null;
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
        if (n == null || depth > 40) return;
        if (n.isEditable() && n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out, depth + 1);
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String value) {
        CharSequence existing = node.getText();
        if (existing != null && existing.toString().equals(value)) return true;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean clickAction(AccessibilityNodeInfo root, String[] matches, boolean bottomFallback) {
        AccessibilityNodeInfo target = findText(root, matches);
        if (target != null && clickNodeOrTap(target)) return true;
        return bottomFallback && tapBottomButton(root);
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String... matches) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String t = textOf(n).toLowerCase(Locale.ROOT).trim();
            if (t.isEmpty()) continue;
            boolean loginSearch = false;
            for (String m : matches) {
                String q = m.toLowerCase(Locale.ROOT);
                if (q.equals("login") || q.equals("log in") || q.equals("sign in") ||
                        q.equals("เข้าสู่ระบบ") || q.equals("ลงชื่อเข้าใช้") || q.equals("ล็อกอิน")) {
                    loginSearch = true;
                }
            }
            if (loginSearch && (t.contains("otp") || t.contains("one time password") ||
                    t.contains("รหัส otp"))) continue;
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
        if (n == null || depth > 40) return;
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

    private boolean clickNodeOrTap(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 10; i++) {
            if (current.isEnabled() && current.isClickable() &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tap(bounds.centerX(), bounds.centerY());
        }
        return false;
    }

    // Fallback for custom buttons that Android exposes as clickable containers
    // but does not expose their visible label as an actionable node.
    private boolean tapBottomButton(AccessibilityNodeInfo root) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MIN_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isEnabled() || !n.isClickable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() < screenW * 0.48f || r.height() < dp(30)) continue;
            if (r.width() > screenW * 0.97f || r.height() > screenH * 0.45f) continue;
            if (r.centerY() < screenH * 0.52f || r.centerY() > screenH * 0.92f) continue;
            long score = (long) r.centerY() * 1000L + r.width() - r.height();
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best != null) {
            Rect r = new Rect();
            best.getBoundsInScreen(r);
            if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    private boolean tapTopRightLogin(AccessibilityNodeInfo root) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser() || !n.isEnabled()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty() || r.width() > screenW * 0.45f || r.height() > dp(90)) continue;
            if (r.centerX() < screenW * 0.72f || r.centerY() > screenH * 0.20f) continue;
            // Prefer the smallest actionable item in the top-right corner.
            long score = (long) r.width() * r.height() - (long) r.centerX() * 3L;
            if (score < bestScore && (n.isClickable() || r.width() > dp(24))) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) return false;
        return clickNodeOrTap(best);
    }

    private boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveScreenDiagnostics(AccessibilityNodeInfo root, int step) {
        // Never save text from editable fields (phone number/password may be present).
        int clickable = 0;
        int editable = 0;
        StringBuilder labels = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isClickable()) clickable++;
            if (n.isEditable()) editable++;
            if (n.isClickable() || n.isCheckable()) {
                String label = textOf(n).trim();
                if (!label.isEmpty() && labels.length() < 500) labels.append(" [").append(label).append("]");
            }
        }
        String pkg = root.getPackageName() == null ? "?" : root.getPackageName().toString();
        String debug = "package=" + pkg + ", step=" + step + ", clickable=" + clickable +
                ", editable=" + editable + ", controls=" + labels;
        state().edit().putString("last_debug", debug).apply();
    }

    private String collectText(AccessibilityNodeInfo root) {
        StringBuilder b = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isEditable()) b.append(textOf(n)).append(' ');
        }
        return b.toString();
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
