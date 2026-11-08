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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

public class BuyerAccessibilityService extends AccessibilityService {
    private static final int PHONE = 0;
    private static final int PASSWORD = 1;
    private static final int CARD = 2;
    private static final int PERSONAL = 3;
    private static final int PASSPORT = 4;
    private static final int PASSPORT_FORM = 5;
    private static final int ADDRESS = 6;
    private static final int APPLY = 7;
    private static final int SEARCH = 8;
    private static final int SET_PRODUCT_QTY = 9;
    private static final int RETURN_HOME = 10;
    private static final int OPEN_CART = 11;
    private static final int VERIFY_CART = 12;
    private static final int DONE = 13;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickQueued = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        refreshRemoteRules();
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
            case SEARCH:
                doSearch(root);
                break;
            case SET_PRODUCT_QTY:
                doSetProductQuantities(root);
                break;
            case RETURN_HOME:
                doReturnHome(root);
                break;
            case OPEN_CART:
                doOpenCart(root);
                break;
            case VERIFY_CART:
                doVerifyCart(root);
                break;
            case DONE:
                stopWith("เปิดหน้าตะกร้าเรียบร้อยแล้ว");
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
            if (clickAction(root, new String[]{"continue", "ดำเนินการต่อ", "ถัดไป", "ยืนยัน"}, false)) {
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

        // Never guess a coordinate for an unlabeled control: it may be Back or Close.
        // Wait safely for a clearly identified Login control instead.
        setStatus("ยังไม่พบปุ่ม Login ที่ระบุได้ชัดเจน — รอหน้าจอ Makro โดยไม่แตะปุ่มอื่น");
    }

    private void doPassword(AccessibilityNodeInfo root) {
        if (hasAny(root, "apply for makro card", "สมัคร makro card", "สมัครบัตรแม็คโคร")) {
            setStep(CARD, "เข้าสู่ระบบแล้ว");
            return;
        }

        long clickedAt = state().getLong("login_clicked_at", 0L);
        boolean passwordPage = hasPasswordField(root) || hasAny(root, "password", "รหัสผ่าน");
        if (!passwordPage) {
            if (clickedAt > 0L && System.currentTimeMillis() - clickedAt >= 1800L) {
                if (hasAny(root, "incorrect password", "invalid password", "wrong password",
                        "รหัสผ่านไม่ถูกต้อง", "เข้าสู่ระบบไม่สำเร็จ")) {
                    stopWith("Makro แจ้งว่ารหัสผ่านไม่ถูกต้อง กรุณาตรวจข้อมูลก่อนเริ่มใหม่");
                } else {
                    setStep(CARD, "หน้ารหัสผ่านปิดแล้ว กำลังตรวจหน้า Makro หลัง Login");
                }
            }
            return;
        }

        String password = readCredentials("password");
        if (password.isEmpty()) {
            stopWith("ไม่พบรหัสผ่านที่บันทึกไว้");
            return;
        }
        AccessibilityNodeInfo field = findEditable(root, true);
        if (field == null || !setNodeText(field, password)) {
            setStatus("พบหน้ารหัสผ่าน แต่ยังใส่รหัสผ่านในช่องไม่ได้");
            return;
        }

        if (clickedAt > 0L && System.currentTimeMillis() - clickedAt < 4500L) {
            setStatus("ส่งคำสั่ง Login แล้ว กำลังรอผลจาก Makro");
            return;
        }
        int attempts = state().getInt("login_attempts", 0);
        if (attempts >= 3) {
            stopWith("ลองกด Login 3 ครั้งแล้วแต่หน้ารหัสผ่านยังไม่ปิด จึงหยุดเพื่อไม่กดซ้ำไม่สิ้นสุด");
            return;
        }
        if (clickAction(root, new String[]{"login", "log in", "sign in", "เข้าสู่ระบบ",
                "ลงชื่อเข้าใช้", "ล็อกอิน"}, false)) {
            state().edit().putLong("login_clicked_at", System.currentTimeMillis())
                    .putInt("login_attempts", attempts + 1)
                    .putString("status", "กดปุ่ม Login แล้ว กำลังรอให้ Makro ยืนยันผล")
                    .apply();
        } else {
            setStatus("กรอกรหัสผ่านแล้ว แต่ยังจับปุ่ม Login โดยตรงไม่พบ — ไม่แตะปุ่มอื่นแทน");
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
        if (!state().getBoolean("passport_number_filled", false)) {
            String number = readCredentials("passport_number");
            if (number.isEmpty()) { stopWith("ไม่พบเลข Passport ที่บันทึกไว้"); return; }
            String[] labels = ruleLabels("passportNumberLabels",
                    "passport number", "passport no", "passport no.", "passport number (as shown)",
                    "เลขที่หนังสือเดินทาง", "หมายเลขหนังสือเดินทาง");
            AccessibilityNodeInfo field = findFieldForLabel(root, labels);
            if (field != null) {
                if (setNodeTextVerified(field, number)) {
                    state().edit().putBoolean("passport_number_filled", true).apply();
                    setStatus("กรอก Passport number และตรวจค่าจากช่องแล้ว");
                } else {
                    setStatus("พบช่อง Passport number แต่ยังยืนยันค่าไม่ได้ — ไม่กรอกช่อง First name แทน");
                }
                return;
            }
            scrollPassportForm(root, "กำลังค้นหาช่อง Passport number โดยไม่แตะช่อง First name");
            return;
        }

        if (!state().getBoolean("dob_filled", false)) {
            if (handleDateOfBirth(root)) return;
            scrollPassportForm(root, "กำลังค้นหาช่อง Date of birth");
            return;
        }

        if (!state().getBoolean("referrer_filled", false)) {
            String[] labels = ruleLabels("referrerCodeLabels",
                    "referrer code", "referral code", "referrer", "referral",
                    "รหัสผู้แนะนำ", "รหัสแนะนำ");
            AccessibilityNodeInfo field = findFieldForLabel(root, labels);
            if (field != null) {
                // WIEX2PIW is already confirmed by the user to be in the correct Referral code field.
                if (setNodeTextVerified(field, "WIEX2PIW")) {
                    state().edit().putBoolean("referrer_filled", true).apply();
                    setStatus("กรอก WIEX2PIW ในช่อง Referral code และตรวจค่าจากช่องแล้ว");
                } else {
                    setStatus("พบช่อง Referral code แต่ยังยืนยันค่าไม่ได้ — ไม่ย้ายโค้ดไปช่องอื่น");
                }
                return;
            }
            scrollPassportForm(root, "กำลังค้นหาช่อง Referral code");
            return;
        }

        if (isAddressOverlay(root)) {
            resetAddressState();
            setStep(ADDRESS, "ยืนยันหน้าต่าง Add address แล้ว");
            return;
        }
        if (state().getBoolean("address_overlay_requested", false)) {
            long clickedAt = state().getLong("address_open_clicked_at", 0L);
            if (clickedAt > 0L && System.currentTimeMillis() - clickedAt > 15000L) {
                stopWith("กด Add address แล้ว แต่ไม่พบหน้าต่างที่อยู่ จึงหยุดโดยไม่กดซ้ำ");
            } else setStatus("กำลังรอหน้าต่าง Address หลังจากกด Add address");
            return;
        }
        AccessibilityNodeInfo addAddress = findOptionText(root, "add address", "เพิ่มที่อยู่");
        AccessibilityNodeInfo button = findClickableAncestor(addAddress, null);
        if (button != null && isReasonableAction(button) &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            state().edit().putBoolean("address_overlay_requested", true)
                    .putLong("address_open_clicked_at", System.currentTimeMillis())
                    .putString("status", "กดปุ่ม Add address แล้ว กำลังยืนยันหน้าต่างที่อยู่").apply();
        } else {
            setStatus("ข้อมูล Passport ครบแล้ว กำลังรอปุ่ม Add address ที่กดได้จริง");
        }
    }

    private boolean handleDateOfBirth(AccessibilityNodeInfo root) {
        String[] labels = ruleLabels("dateOfBirthLabels",
                "date of birth", "dob", "วันเดือนปีเกิด", "วันเกิด");
        AccessibilityNodeInfo field = findFieldForLabel(root, labels);
        if (field != null) {
            if (dateTextMatches(textOf(field), "21/11/1986")) {
                state().edit().putBoolean("dob_filled", true).remove("dob_focus_requested").apply();
                setStatus("ตรวจพบวันเกิด 21/11/1986 ในช่อง Date of birth แล้ว");
                return true;
            }
            if (field.isEditable() && setNodeTextVerified(field, "21/11/1986")) {
                state().edit().putBoolean("dob_filled", true).remove("dob_focus_requested").apply();
                setStatus("กรอกวันเกิด 21/11/1986 และตรวจค่าจากช่องแล้ว");
                return true;
            }
            setStatus("พบช่องวันเกิดแต่กรอกข้อความไม่ได้ — ไม่เปิดปฏิทินและไม่กรอกช่องอื่น");
            return true;
        }
        AccessibilityNodeInfo control = findControlForLabel(root, labels);
        if (control != null) {
            if (!state().getBoolean("dob_focus_requested", false)) {
                boolean focused = control.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                        control.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                state().edit().putBoolean("dob_focus_requested", true)
                        .putLong("dob_focus_requested_at", System.currentTimeMillis()).apply();
                setStatus(focused
                        ? "โฟกัสช่องวันเกิดแล้ว กำลังอ่านช่องข้อความใหม่ โดยไม่เปิดปฏิทิน"
                        : "พบกรอบวันเกิดแต่โฟกัสไม่ได้ กำลังรอ input ข้อความโดยไม่เปิดปฏิทิน");
            } else {
                long requestedAt = state().getLong("dob_focus_requested_at", 0L);
                if (requestedAt > 0L && System.currentTimeMillis() - requestedAt > 6000L) {
                    stopWith("ช่องวันเกิดไม่แสดงเป็น input ข้อความหลังโฟกัส จึงหยุดโดยไม่เปิด Date Picker");
                } else {
                    setStatus("กำลังรอช่องข้อความวันเกิดหลังโฟกัส — ไม่เปิดปฏิทิน");
                }
            }
            return true;
        }
        return false;
    }

    private boolean dateTextMatches(String actual, String expected) {
        return actual != null && expected != null &&
                actual.trim().equals(expected.trim());
    }

    private void scrollPassportForm(AccessibilityNodeInfo root, String message) {
        int count = state().getInt("form_scrolls", 0);
        String fingerprint = screenFingerprint(root);
        String previous = state().getString("form_scroll_fingerprint", "");
        if (count > 0 && fingerprint.equals(previous)) {
            stopWith(message + " — หน้าจอไม่เปลี่ยนหลังเลื่อน จึงหยุดแทนการปัดซ้ำ");
            return;
        }
        if (count >= 4) {
            stopWith(message + " — เลื่อน 4 ครั้งแล้วยังไม่พบช่อง จึงหยุดโดยไม่กรอกผิดช่อง");
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollable(root);
        boolean moved = scrollable != null &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!moved && count < 2) moved = swipeUp();
        if (moved) {
            state().edit().putInt("form_scrolls", count + 1)
                    .putString("form_scroll_fingerprint", fingerprint)
                    .putString("status", message).apply();
        } else {
            setStatus(message + " — ไม่พบส่วนที่เลื่อนได้ จึงรอโดยไม่ปัดซ้ำ");
        }
    }

    private void doAddress(AccessibilityNodeInfo root) {
        if (state().getBoolean("address_save_requested", false)) {
            if (!isAddressOverlay(root) && isPassportForm(root)) {
                state().edit().putBoolean("address_save_requested", false)
                        .putBoolean("address_overlay_requested", false).apply();
                setStep(APPLY, "หน้าต่าง Address ปิดแล้ว กำลังค้นหาปุ่ม Apply บนฟอร์มเดิม");
                return;
            }
            long at = state().getLong("address_save_clicked_at", 0L);
            if (at > 0L && System.currentTimeMillis() - at > 15000L) {
                stopWith("กด Save แล้ว แต่ยืนยันการปิดหน้าต่าง Address ไม่ได้");
            } else setStatus("กด Save แล้ว กำลังรอหน้าต่าง Address ปิด");
            return;
        }

        if (!state().getBoolean("address_filled", false)) {
            String[] labels = ruleLabels("addressLabels",
                    "address line 1", "address", "house no", "house number", "ที่อยู่", "บ้านเลขที่");
            AccessibilityNodeInfo field = findFieldForLabel(root, labels);
            if (field != null) {
                if (setNodeTextVerified(field, "888")) {
                    state().edit().putBoolean("address_filled", true).apply();
                    setStatus("กรอก Address = 888 และตรวจค่าจากช่องแล้ว");
                } else setStatus("พบช่อง Address แต่ยืนยันค่าไม่ได้");
                return;
            }
            if (focusLabeledInput(root, labels, "address_focus_requested")) return;
            scrollAddressForm(root, "กำลังค้นหาช่อง Address");
            return;
        }

        if (!state().getBoolean("zip_filled", false)) {
            String[] labels = ruleLabels("postalCodeLabels",
                    "zip code", "postal code", "postcode", "รหัสไปรษณีย์");
            AccessibilityNodeInfo field = findFieldForLabel(root, labels);
            if (field != null) {
                if (setNodeTextVerified(field, "20000")) {
                    state().edit().putBoolean("zip_filled", true).apply();
                    setStatus("กรอก Postal code = 20000 และตรวจค่าจากช่องแล้ว");
                } else setStatus("พบช่อง Postal code แต่ยืนยันค่าไม่ได้");
                return;
            }
            if (focusLabeledInput(root, labels, "zip_focus_requested")) return;
            scrollAddressForm(root, "กำลังค้นหาช่อง Postal code");
            return;
        }

        if (!state().getBoolean("district_selected", false)) {
            handleFirstDropdownOption(root, "district",
                    ruleLabels("districtLabels", "district", "อำเภอ"),
                    "district_open_requested", "district_select_pending",
                    "district_popup_fingerprint", "district_selected_value",
                    "district_open_time", "district_open_attempts");
            return;
        }

        if (!state().getBoolean("subdistrict_selected", false)) {
            handleFirstDropdownOption(root, "subdistrict",
                    ruleLabels("subdistrictLabels", "sub-district", "subdistrict", "ตำบล"),
                    "subdistrict_open_requested", "subdistrict_select_pending",
                    "subdistrict_popup_fingerprint", "subdistrict_selected_value",
                    "subdistrict_open_time", "subdistrict_open_attempts");
            return;
        }

        AccessibilityNodeInfo save = findExactText(root, "save", "บันทึก");
        AccessibilityNodeInfo button = findClickableAncestor(save, null);
        if (button != null && isReasonableAction(button) &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            state().edit().putBoolean("address_save_requested", true)
                    .putLong("address_save_clicked_at", System.currentTimeMillis())
                    .putString("status", "กด Save แล้ว กำลังตรวจว่าหน้าต่าง Address ปิด").apply();
        } else scrollAddressForm(root, "กรอกที่อยู่ครบแล้ว กำลังรอปุ่ม Save ที่กดได้จริง");
    }

    private boolean focusLabeledInput(AccessibilityNodeInfo root, String[] labels, String flag) {
        AccessibilityNodeInfo control = findControlForLabel(root, labels);
        if (control == null) return false;
        if (!state().getBoolean(flag, false)) {
            boolean focused = control.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (focused) {
                state().edit().putBoolean(flag, true)
                        .putLong(flag + "_time", System.currentTimeMillis()).apply();
                setStatus("โฟกัสช่องข้อมูลแล้ว กำลังอ่าน Accessibility tree ใหม่");
            } else setStatus("พบกรอบช่องข้อมูลแต่โฟกัสไม่ได้ — ไม่กรอกช่องอื่น");
        } else {
            long at = state().getLong(flag + "_time", 0L);
            if (at > 0L && System.currentTimeMillis() - at > 8000L) {
                stopWith("โฟกัสช่องแล้วแต่ไม่พบช่องข้อความที่สัมพันธ์กับ label");
            } else setStatus("กำลังรอช่องข้อความหลังโฟกัส");
        }
        return true;
    }

    private boolean isAddressOverlay(AccessibilityNodeInfo root) {
        boolean address = hasExactLabel(root, "address", "address line 1", "house number",
                "house no", "ที่อยู่", "บ้านเลขที่");
        boolean postal = hasExactLabel(root, "postal code", "postcode", "zip code", "รหัสไปรษณีย์");
        boolean district = hasExactLabel(root, "district", "อำเภอ");
        boolean subdistrict = hasExactLabel(root, "sub-district", "subdistrict", "ตำบล");
        return (address && postal) || (postal && district && subdistrict);
    }

    private boolean hasExactLabel(AccessibilityNodeInfo root, String... labels) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable()) continue;
            String text = textOf(node).toLowerCase(Locale.ROOT).trim()
                    .replaceAll("\\s+", " ").replaceAll("[*:：]+$", "").trim();
            for (String label : labels) {
                if (text.equals(label.toLowerCase(Locale.ROOT).trim())) return true;
            }
        }
        return false;
    }

    private void resetAddressState() {
        state().edit().putBoolean("address_overlay_requested", false)
                .putBoolean("address_filled", false).putBoolean("zip_filled", false)
                .putBoolean("district_selected", false).putBoolean("subdistrict_selected", false)
                .putBoolean("district_open_requested", false).putBoolean("district_select_pending", false)
                .putBoolean("subdistrict_open_requested", false).putBoolean("subdistrict_select_pending", false)
                .putBoolean("address_save_requested", false)
                .putInt("district_open_attempts", 0).putInt("subdistrict_open_attempts", 0)
                .putInt("address_scrolls", 0)
                .remove("address_focus_requested").remove("zip_focus_requested")
                .remove("district_popup_fingerprint").remove("subdistrict_popup_fingerprint")
                .remove("district_selected_value").remove("subdistrict_selected_value")
                .remove("address_scroll_fingerprint").apply();
    }

    private void handleFirstDropdownOption(AccessibilityNodeInfo root, String type,
                                           String[] labels, String openKey, String pendingKey,
                                           String fingerprintKey, String selectedValueKey,
                                           String timeKey, String attemptsKey) {
        String baseline = state().getString(fingerprintKey, "");
        String selectedFlag = type.equals("district") ? "district_selected" : "subdistrict_selected";

        if (state().getBoolean(pendingKey, false)) {
            String selected = state().getString(selectedValueKey, "");
            if (findFirstNewPopupOption(root, baseline) == null &&
                    !selected.isEmpty() && hasSelectedValueNearLabel(root, labels, selected)) {
                state().edit().putBoolean(pendingKey, false).putBoolean(openKey, false)
                        .putBoolean(selectedFlag, true)
                        .putString("status", "ยืนยันการเลือก " + type + " แล้ว: " + selected).apply();
            } else setStatus("กำลังตรวจว่ารายการ " + type + " ปิดและแสดงค่าที่เลือกแล้ว");
            return;
        }

        if (state().getBoolean(openKey, false)) {
            AccessibilityNodeInfo first = findFirstNewPopupOption(root, baseline);
            if (first == null) {
                long at = state().getLong(timeKey, 0L);
                if (at > 0L && System.currentTimeMillis() - at > 10000L) {
                    stopWith("เปิดรายการ " + type + " แล้วแต่ไม่พบตัวเลือกแรกที่ระบุได้ — ไม่สุ่มเลือก");
                } else setStatus("กำลังรอตัวเลือกแรกของ " + type + " ใน popup");
                return;
            }
            String value = first.getText() == null ? textOf(first).trim() : first.getText().toString().trim();
            AccessibilityNodeInfo option = findClickableOptionAncestor(first);
            if (value.isEmpty() || option == null ||
                    !option.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                setStatus("พบตัวเลือกแรกของ " + type + " แต่กดปุ่มตัวเลือกไม่ได้");
                return;
            }
            state().edit().putBoolean(pendingKey, true).putString(selectedValueKey, value)
                    .putString("status", "กดตัวเลือกแรกของ " + type + " แล้ว กำลังตรวจผลจาก tree ใหม่").apply();
            return;
        }

        AccessibilityNodeInfo control = findControlForLabel(root, labels);
        AccessibilityNodeInfo button = findClickableAncestor(control, null);
        if (button != null && isReasonableAction(button) &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            state().edit().putBoolean(openKey, true).putString(fingerprintKey, screenFingerprint(root))
                    .putLong(timeKey, System.currentTimeMillis())
                    .putInt(attemptsKey, state().getInt(attemptsKey, 0) + 1)
                    .putString("status", "เปิด dropdown " + type + " แล้ว กำลังรอรายการตัวเลือก").apply();
        } else setStatus("กำลังรอ control ของ " + type + " ที่สัมพันธ์กับ label");
    }

    private AccessibilityNodeInfo findFirstNewPopupOption(AccessibilityNodeInfo root, String baseline) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        List<AccessibilityNodeInfo> choices = new ArrayList<>();
        List<Rect> used = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable()) continue;
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            if (text.isEmpty() || text.length() > 100 || isDropdownChrome(text)) continue;
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.isEmpty() || rect.height() < dp(12) || rect.height() > dp(100) ||
                    baseline.contains(nodeFingerprint(node))) continue;
            AccessibilityNodeInfo button = findClickableOptionAncestor(node);
            if (button == null) continue;
            Rect buttonRect = new Rect();
            button.getBoundsInScreen(buttonRect);
            boolean duplicate = false;
            for (Rect previous : used) {
                if (Math.abs(previous.top - buttonRect.top) < dp(4) &&
                        Math.abs(previous.left - buttonRect.left) < dp(8)) { duplicate = true; break; }
            }
            if (!duplicate) { used.add(buttonRect); choices.add(node); }
        }
        choices.sort((a, b) -> {
            Rect ra = new Rect(); a.getBoundsInScreen(ra);
            Rect rb = new Rect(); b.getBoundsInScreen(rb);
            int y = Integer.compare(ra.top, rb.top);
            return y != 0 ? y : Integer.compare(ra.left, rb.left);
        });
        return choices.isEmpty() ? null : choices.get(0);
    }

    private boolean isDropdownChrome(String text) {
        String value = text.toLowerCase(Locale.ROOT).trim();
        String[] excluded = {"district", "subdistrict", "sub-district", "select", "search",
                "cancel", "close", "done", "confirm", "clear", "choose", "save", "อำเภอ",
                "ตำบล", "เลือก", "ค้นหา", "ยกเลิก", "ปิด", "เสร็จ", "ตกลง", "บันทึก"};
        for (String word : excluded) {
            if (value.equals(word) || value.startsWith(word + " ")) return true;
        }
        return value.equals("+") || value.equals("-");
    }

    private AccessibilityNodeInfo findClickableOptionAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; current != null && i < 7; i++) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable() &&
                    !rect.isEmpty() && rect.height() >= dp(18) && rect.height() <= dp(110) &&
                    rect.width() <= screenWidth) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean hasSelectedValueNearLabel(AccessibilityNodeInfo root, String[] labels, String selected) {
        if (selected == null || selected.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo labelNode : nodes) {
            if (!labelNode.isVisibleToUser()) continue;
            String label = textOf(labelNode).toLowerCase(Locale.ROOT).trim()
                    .replaceAll("[*:：]+$", "").trim();
            boolean match = false;
            for (String wanted : labels) {
                if (label.equals(wanted.toLowerCase(Locale.ROOT).trim())) { match = true; break; }
            }
            if (!match) continue;
            Rect lr = new Rect(); labelNode.getBoundsInScreen(lr);
            for (AccessibilityNodeInfo valueNode : nodes) {
                if (!valueNode.isVisibleToUser() || valueNode.isEditable()) continue;
                String value = valueNode.getText() == null ? textOf(valueNode).trim() :
                        valueNode.getText().toString().trim();
                if (!value.equalsIgnoreCase(selected)) continue;
                Rect vr = new Rect(); valueNode.getBoundsInScreen(vr);
                int gap = vr.top - lr.bottom;
                if ((Math.abs(vr.centerY() - lr.centerY()) <= dp(80) ||
                        (gap >= -dp(12) && gap <= dp(180))) &&
                        Math.abs(vr.centerX() - lr.centerX()) <= dp(180)) return true;
            }
        }
        return false;
    }

    private String nodeFingerprint(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return textOf(node).trim() + "|" + rect.top + "," + rect.bottom + ";";
    }

    private void doApply(AccessibilityNodeInfo root) {
        if (hasAny(root, "application submitted", "submitted successfully",
                "ส่งใบสมัครเรียบร้อย", "สมัครสำเร็จ")) {
            setStep(SEARCH, "ตรวจพบการส่งใบสมัครแล้ว กำลังรอหน้า Home เพื่อค้นหาสินค้า");
            return;
        }
        AccessibilityNodeInfo label = findExactText(root, "apply now", "apply", "ยื่นใบสมัคร");
        AccessibilityNodeInfo button = findClickableAncestor(label, null);
        if (button != null && isReasonableAction(button) &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            setStep(SEARCH, "กด Apply แล้ว กำลังรอหน้า Home เพื่อค้นหาสินค้า");
            return;
        }
        setStatus("บันทึกที่อยู่แล้ว กำลังรอปุ่ม Apply ที่กดได้จริง");
    }

    private void doSearch(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo field = findSearchInput(root);
        if (field == null) {
            setStatus("กำลังรอช่อง Search for products / ค้นหาสินค้าบนหน้า Home");
            return;
        }
        String actual = field.getText() == null ? "" : field.getText().toString().trim();
        if (!actual.equals("คาราบาว") &&
                (!field.isEditable() || !setNodeTextVerified(field, "คาราบาว"))) {
            setStatus("พบช่องค้นหาสินค้าแต่กรอกข้อความไม่ได้ — ไม่กดปุ่มค้นหาหรือ Enter");
            return;
        }
        state().edit().putBoolean("search_filled", true).apply();
        setStep(SET_PRODUCT_QTY, "กรอกคำว่า คาราบาว แล้ว กำลังรอผลลัพธ์สินค้า");
    }

    private AccessibilityNodeInfo findSearchInput(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo field : editableNodes(root)) {
            String descriptor = fieldDescriptor(field).toLowerCase(Locale.ROOT);
            if (descriptor.contains("search for products") || descriptor.contains("ค้นหาสินค้า") ||
                    descriptor.contains("product search")) return field;
        }
        return null;
    }

    private void doSetProductQuantities(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> rows = findProductRows(root);
        if (rows.size() < 2) {
            setStatus("กำลังรอผลค้นหา CARABAO ให้มีสินค้าอย่างน้อย 2 แถว");
            return;
        }
        int index = state().getInt("qty_product_index", 0);
        if (index < 0 || index > 1) {
            stopWith("สถานะรายการสินค้าไม่ถูกต้อง จึงหยุด");
            return;
        }
        int target = index == 0 ? 3 : 2;
        AccessibilityNodeInfo row = rows.get(index);
        int current = readRowQuantity(row);
        if (current < 0) {
            setStatus("พบสินค้าแถวที่ " + (index + 1) + " แต่ยังอ่านจำนวนปัจจุบันไม่ได้");
            return;
        }
        if (state().getBoolean("qty_click_pending", false)) {
            int before = state().getInt("qty_before", -1);
            int expected = state().getInt("qty_expected", -1);
            long clickedAt = state().getLong("qty_click_at", 0L);
            if (current == expected) {
                state().edit().putBoolean("qty_click_pending", false).apply();
                setStatus("ยืนยันจำนวนสินค้าแถวที่ " + (index + 1) + " เพิ่มเป็น " + current + " แล้ว");
                return;
            }
            if (current == before && System.currentTimeMillis() - clickedAt < 5000L) {
                setStatus("กด + แล้ว กำลังรอจำนวนสินค้าเปลี่ยน");
                return;
            }
            stopWith("จำนวนสินค้าไม่เปลี่ยนตามที่คาดหลังจากกด + จึงหยุดเพื่อไม่กดซ้ำ");
            return;
        }
        if (current > target) {
            stopWith("สินค้าแถวที่ " + (index + 1) + " มีจำนวน " + current +
                    " มากกว่าเป้าหมาย " + target + " และระบบจะไม่ลดจำนวน");
            return;
        }
        if (current == target) {
            if (index == 0) {
                state().edit().putInt("qty_product_index", 1)
                        .putString("status", "ยืนยันสินค้าแถวแรก = 3 แล้ว กำลังตั้งแถวที่สอง = 2").apply();
            } else {
                state().edit().putBoolean("back_requested", false).apply();
                setStep(RETURN_HOME, "ตั้งสินค้า CARABAO รายการแรกเป็น 3 และรายการที่สองเป็น 2 แล้ว กำลังกลับหน้า Home");
            }
            return;
        }
        AccessibilityNodeInfo plus = findPlusButtonInRow(row);
        if (plus == null) {
            setStatus("ระบุปุ่ม + ของสินค้าแถวที่ " + (index + 1) + " ไม่ได้ชัดเจน — ไม่กดปุ่มอื่น");
            return;
        }
        if (plus.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            state().edit().putBoolean("qty_click_pending", true)
                    .putInt("qty_before", current).putInt("qty_expected", current + 1)
                    .putLong("qty_click_at", System.currentTimeMillis())
                    .putString("status", "กด + ของสินค้าแถวที่ " + (index + 1) +
                            " หนึ่งครั้ง กำลังตรวจจำนวนจาก tree ใหม่").apply();
        } else setStatus("พบปุ่ม + แต่สั่งคลิกไม่ได้");
    }

    private List<AccessibilityNodeInfo> findProductRows(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(root, all, 0);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo node : all) {
            if (!node.isVisibleToUser()) continue;
            Rect rect = new Rect(); node.getBoundsInScreen(rect);
            if (rect.isEmpty() || rect.width() < sw * 0.45f ||
                    rect.height() < dp(55) || rect.height() > sh * 0.42f) continue;
            String text = subtreeText(node).toLowerCase(Locale.ROOT);
            if (!(text.contains("carabao") || text.contains("คาราบาว")) ||
                    !hasPriceEvidence(text) || findPlusButtonInRow(node) == null) continue;
            candidates.add(node);
        }
        candidates.sort((a, b) -> {
            Rect ra = new Rect(); a.getBoundsInScreen(ra);
            Rect rb = new Rect(); b.getBoundsInScreen(rb);
            return Long.compare((long) ra.width() * ra.height(), (long) rb.width() * rb.height());
        });
        List<AccessibilityNodeInfo> rows = new ArrayList<>();
        List<Rect> used = new ArrayList<>();
        for (AccessibilityNodeInfo candidate : candidates) {
            Rect rect = new Rect(); candidate.getBoundsInScreen(rect);
            boolean duplicate = false;
            for (Rect prior : used) {
                Rect intersection = new Rect(prior);
                if (intersection.intersect(rect)) {
                    long overlap = (long) intersection.width() * intersection.height();
                    long smaller = Math.min((long) prior.width() * prior.height(),
                            (long) rect.width() * rect.height());
                    if (smaller > 0 && overlap >= smaller * 0.70d) { duplicate = true; break; }
                }
            }
            if (!duplicate) { rows.add(candidate); used.add(rect); }
        }
        rows.sort((a, b) -> {
            Rect ra = new Rect(); a.getBoundsInScreen(ra);
            Rect rb = new Rect(); b.getBoundsInScreen(rb);
            int y = Integer.compare(ra.top, rb.top);
            return y != 0 ? y : Integer.compare(ra.left, rb.left);
        });
        return rows;
    }

    private String subtreeText(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && !node.isEditable()) out.append(textOf(node)).append(' ');
        }
        return out.toString();
    }

    private boolean hasPriceEvidence(String text) {
        return text.contains("฿") || text.contains("baht") || text.contains("unit(s)") ||
                text.contains("units") || text.contains("ราคา");
    }

    private boolean isSmallControl(AccessibilityNodeInfo node) {
        Rect rect = new Rect(); node.getBoundsInScreen(rect);
        return !rect.isEmpty() && rect.width() <= dp(100) && rect.height() <= dp(100);
    }

    private AccessibilityNodeInfo findPlusButtonInRow(AccessibilityNodeInfo row) {
        Rect rowBounds = new Rect(); row.getBoundsInScreen(rowBounds);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(row, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            String label = textOf(node).toLowerCase(Locale.ROOT).trim();
            String id = node.getViewIdResourceName() == null ? "" :
                    node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            boolean isPlus = label.equals("+") || label.contains("increase quantity") ||
                    label.contains("increment") || label.contains("เพิ่มจำนวน") ||
                    id.contains("quantity_plus") || id.contains("qty_plus") ||
                    id.contains("increment") || id.endsWith("_plus");
            if (isPlus) {
                AccessibilityNodeInfo button = findClickableAncestor(node, rowBounds);
                if (button != null && isSmallControl(button)) return button;
            }
        }
        AccessibilityNodeInfo minus = findMinusButtonInRow(row, rowBounds);
        if (minus == null) return null;
        Rect mr = new Rect(); minus.getBoundsInScreen(mr);
        AccessibilityNodeInfo fallback = null;
        int rightmost = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable() ||
                    !isSmallControl(node)) continue;
            Rect rect = new Rect(); node.getBoundsInScreen(rect);
            if (rect.centerX() <= mr.centerX() ||
                    Math.abs(rect.centerY() - mr.centerY()) > dp(35)) continue;
            if (readQuantityBetween(row, minus, node) >= 0 && rect.centerX() > rightmost) {
                rightmost = rect.centerX();
                fallback = node;
            }
        }
        return fallback;
    }

    private AccessibilityNodeInfo findMinusButtonInRow(AccessibilityNodeInfo row, Rect rowBounds) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(row, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            String label = textOf(node).toLowerCase(Locale.ROOT).trim();
            String id = node.getViewIdResourceName() == null ? "" :
                    node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (label.equals("-") || label.contains("decrease quantity") ||
                    label.contains("ลดจำนวน") || id.contains("quantity_minus") ||
                    id.contains("qty_minus") || id.contains("decrement")) {
                AccessibilityNodeInfo button = findClickableAncestor(node, rowBounds);
                if (button != null && isSmallControl(button)) return button;
            }
        }
        return null;
    }

    private int readRowQuantity(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(row, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable()) continue;
            String descriptor = fieldDescriptor(node).toLowerCase(Locale.ROOT);
            if (descriptor.contains("quantity") || descriptor.contains("qty") ||
                    descriptor.contains("จำนวน")) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)")
                        .matcher(textOf(node));
                if (matcher.find()) {
                    try { return Integer.parseInt(matcher.group(1)); }
                    catch (NumberFormatException ignored) { return -1; }
                }
            }
        }
        return readQuantityBetween(row, findMinusButtonInRow(row, boundsOf(row)),
                findPlusButtonLabelOnly(row));
    }

    private Rect boundsOf(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        if (node != null) node.getBoundsInScreen(rect);
        return rect;
    }

    private AccessibilityNodeInfo findPlusButtonLabelOnly(AccessibilityNodeInfo row) {
        Rect rowBounds = boundsOf(row);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(row, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            String label = textOf(node).toLowerCase(Locale.ROOT).trim();
            String id = node.getViewIdResourceName() == null ? "" :
                    node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (label.equals("+") || label.contains("increase quantity") ||
                    label.contains("increment") || id.contains("quantity_plus") ||
                    id.contains("qty_plus") || id.contains("increment")) {
                AccessibilityNodeInfo button = findClickableAncestor(node, rowBounds);
                if (button != null && isSmallControl(button)) return button;
            }
        }
        return null;
    }

    private int readQuantityBetween(AccessibilityNodeInfo row,
                                   AccessibilityNodeInfo minus, AccessibilityNodeInfo plus) {
        if (minus == null || plus == null) return -1;
        Rect mr = boundsOf(minus), pr = boundsOf(plus);
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(row, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable() || node.getText() == null) continue;
            String raw = node.getText().toString().trim();
            if (!raw.matches("\\d{1,2}")) continue;
            int value;
            try { value = Integer.parseInt(raw); } catch (NumberFormatException ignored) { continue; }
            Rect rect = boundsOf(node);
            if (rect.isEmpty() || Math.abs(rect.centerY() - pr.centerY()) > dp(35) ||
                    rect.centerX() <= mr.centerX() || rect.centerX() >= pr.centerX()) continue;
            long score = Math.abs((long) rect.centerX() - (mr.centerX() + pr.centerX()) / 2L);
            if (score < bestScore) { bestScore = score; best = value; }
        }
        return best;
    }

    private boolean isHomeSearchScreen(AccessibilityNodeInfo root) {
        boolean searchPrompt = false;
        for (AccessibilityNodeInfo node : editableNodes(root)) {
            String descriptor = fieldDescriptor(node).toLowerCase(Locale.ROOT);
            if (descriptor.contains("search for products") || descriptor.contains("ค้นหาสินค้า")) {
                searchPrompt = true;
                break;
            }
        }
        if (!searchPrompt) return false;
        String visibleText = collectText(root).toLowerCase(Locale.ROOT);
        return !visibleText.contains("carabao") && !visibleText.contains("คาราบาว");
    }

    private void doReturnHome(AccessibilityNodeInfo root) {
        if (isHomeSearchScreen(root)) {
            state().edit().putBoolean("back_requested", false).apply();
            setStep(OPEN_CART, "กลับหน้า Home แล้ว กำลังค้นหาปุ่มตะกร้ามุมขวาบน");
            return;
        }
        if (state().getBoolean("back_requested", false)) {
            long requestedAt = state().getLong("back_requested_at", 0L);
            if (requestedAt > 0L && System.currentTimeMillis() - requestedAt > 15000L) {
                stopWith("กด Global Back หนึ่งครั้งแล้วแต่ไม่พบหน้า Home จึงหยุดโดยไม่กด Back ซ้ำ");
            } else {
                setStatus("กด Global Back ไปแล้วหนึ่งครั้ง กำลังรอหน้า Home โดยไม่กดซ้ำ");
            }
            return;
        }
        if (performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            state().edit().putBoolean("back_requested", true)
                    .putLong("back_requested_at", System.currentTimeMillis())
                    .putString("status", "กด Global Back หนึ่งครั้ง กำลังรอหน้า Home").apply();
        } else stopWith("ส่งคำสั่ง Global Back ไม่สำเร็จ — ไม่ใช้พิกัดย้อนกลับ");
    }

    private void doOpenCart(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo cart = findCartButton(root);
        if (cart == null) {
            setStatus("กำลังรอปุ่มรถเข็นบริเวณมุมขวาบนที่ระบุได้ชัดเจน");
            return;
        }
        if (cart.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            state().edit().putLong("cart_open_clicked_at", System.currentTimeMillis()).apply();
            setStep(VERIFY_CART, "กดรถเข็นแล้ว กำลังตรวจหน้าตะกร้าจาก tree ใหม่");
        } else setStatus("พบปุ่มรถเข็นแต่คลิกไม่ได้");
    }

    private AccessibilityNodeInfo findCartButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int rightmost = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            String label = textOf(node).toLowerCase(Locale.ROOT);
            String id = node.getViewIdResourceName() == null ? "" :
                    node.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (!(label.contains("cart") || label.contains("shopping cart") ||
                    label.contains("ตะกร้า") || id.contains("cart"))) continue;
            AccessibilityNodeInfo button = findClickableAncestor(node, null);
            if (button == null || !isSmallControl(button)) continue;
            Rect rect = boundsOf(button);
            if (rect.centerX() > sw * 0.65f && rect.centerY() < sh * 0.30f &&
                    rect.centerX() > rightmost) {
                best = button;
                rightmost = rect.centerX();
            }
        }
        // Do not infer Cart from a numeric badge (for example "2").
        // Without cart text/contentDescription/resource ID, wait rather than tap an unknown icon.
        return best;
    }

    private void doVerifyCart(AccessibilityNodeInfo root) {
        boolean heading = hasCartHeading(root);
        boolean productEvidence = hasCartProductEvidence(root);
        boolean totalEvidence = hasCartTotalEvidence(root);
        boolean actionEvidence = hasCartActionEvidence(root);
        int supportingEvidence = (productEvidence ? 1 : 0) + (totalEvidence ? 1 : 0) +
                (actionEvidence ? 1 : 0);
        if (heading && supportingEvidence >= 2) {
            setStep(DONE, "ยืนยันหัวข้อหน้าตะกร้าและองค์ประกอบหน้าตะกร้าอีกอย่างน้อย 2 อย่างแล้ว");
            return;
        }
        long clickedAt = state().getLong("cart_open_clicked_at", 0L);
        if (clickedAt > 0L && System.currentTimeMillis() - clickedAt > 25000L) {
            stopWith("กดปุ่มตะกร้าแล้ว แต่ยังไม่พบหัวข้อและองค์ประกอบหน้าตะกร้าอย่างน้อย 2 อย่าง");
            return;
        }
        setStatus("กำลังตรวจหน้าตะกร้า: หัวข้อ=" + heading + ", สินค้า=" +
                productEvidence + ", ยอดรวม=" + totalEvidence + ", ปุ่มดำเนินการ=" + actionEvidence);
    }

    private boolean hasCartHeading(AccessibilityNodeInfo root) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        String[] headings = {"cart", "shopping cart", "your cart", "my cart",
                "ตะกร้า", "ตะกร้าสินค้า", "ตะกร้าของคุณ"};
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable()) continue;
            String text = textOf(node).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
            Rect rect = boundsOf(node);
            if (rect.isEmpty() || rect.centerY() > screenHeight * 0.28f) continue;
            for (String heading : headings) if (text.equals(heading)) return true;
        }
        return false;
    }

    private boolean hasCartProductEvidence(AccessibilityNodeInfo root) {
        String text = collectText(root).toLowerCase(Locale.ROOT);
        boolean product = text.contains("carabao") || text.contains("คาราบาว");
        boolean quantityOrPrice = text.contains("฿") || text.contains("baht") ||
                text.contains("unit(s)") || text.contains("quantity") ||
                text.contains("qty") || text.contains("จำนวน");
        return product && quantityOrPrice;
    }

    private boolean hasCartTotalEvidence(AccessibilityNodeInfo root) {
        return hasAny(root, "subtotal", "order total", "total amount", "estimated total",
                "ยอดรวม", "รวมทั้งสิ้น", "ยอดที่ต้องชำระ", "ยอดชำระ");
    }

    private boolean hasCartActionEvidence(AccessibilityNodeInfo root) {
        String[] actions = {"checkout", "proceed to checkout", "place order",
                "ดำเนินการต่อ", "ชำระเงิน", "สั่งซื้อ", "ยืนยันคำสั่งซื้อ"};
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()) continue;
            String text = textOf(node).toLowerCase(Locale.ROOT);
            for (String action : actions) if (text.contains(action)) return true;
        }
        return false;
    }

    private void scrollAddressForm(AccessibilityNodeInfo root, String message) {
        int scrolls = state().getInt("address_scrolls", 0);
        String fingerprint = screenFingerprint(root);
        String previous = state().getString("address_scroll_fingerprint", "");
        if (scrolls > 0 && fingerprint.equals(previous)) {
            stopWith(message + " — หน้าจอไม่เปลี่ยนหลังเลื่อน จึงหยุดแทนการปัดซ้ำ");
            return;
        }
        if (scrolls >= 3) {
            stopWith(message + " — เลื่อน 3 ครั้งแล้วยังไม่พบช่อง จึงหยุดโดยไม่กรอกข้อมูลผิด");
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollable(root);
        boolean moved = scrollable != null &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!moved && scrolls < 2) moved = swipeUp();
        if (moved) {
            state().edit().putInt("address_scrolls", scrolls + 1)
                    .putString("address_scroll_fingerprint", fingerprint)
                    .putString("status", message).apply();
        } else {
            setStatus(message + " — ไม่พบส่วนที่เลื่อนได้ จึงรอโดยไม่ปัดซ้ำ");
        }
    }

    private String screenFingerprint(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || node.isEditable()) continue;
            String label = textOf(node).trim();
            if (!label.isEmpty()) out.append(label).append('|');
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            out.append(bounds.top).append(',').append(bounds.bottom).append(';');
            if (out.length() > 1800) break;
        }
        return out.toString();
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
                if (text.equals(wanted)) return node;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findOptionText(AccessibilityNodeInfo root, String... values) {
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

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node, Rect boundsLimit) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 10; i++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                Rect rect = new Rect();
                current.getBoundsInScreen(rect);
                boolean valid = !rect.isEmpty();
                if (valid && boundsLimit != null) {
                    valid = boundsLimit.contains(rect.centerX(), rect.centerY()) &&
                            rect.width() <= boundsLimit.width() + dp(12) &&
                            rect.height() <= boundsLimit.height() + dp(12);
                }
                if (valid) return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isReasonableAction(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        return !rect.isEmpty() && rect.width() <= width &&
                rect.height() <= Math.min(height * 0.35f, dp(180));
    }

    private AccessibilityNodeInfo findClickableTextAction(AccessibilityNodeInfo root, String... matches) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            String text = textOf(node).toLowerCase(Locale.ROOT).trim();
            if (text.isEmpty()) continue;
            boolean loginSearch = false;
            for (String match : matches) {
                String q = match.toLowerCase(Locale.ROOT);
                if (q.equals("login") || q.equals("log in") || q.equals("sign in") ||
                        q.equals("เข้าสู่ระบบ") || q.equals("ลงชื่อเข้าใช้") || q.equals("ล็อกอิน")) {
                    loginSearch = true;
                }
            }
            if (loginSearch && (text.contains("otp") || text.contains("one time password") ||
                    text.contains("รหัส otp"))) continue;
            boolean matched = false;
            for (String match : matches) {
                String q = match.toLowerCase(Locale.ROOT);
                if (text.equals(q) || text.contains(q)) { matched = true; break; }
            }
            if (!matched) continue;
            AccessibilityNodeInfo button = findClickableAncestor(node, null);
            if (button == null || !isReasonableAction(button)) continue;
            Rect rect = new Rect();
            button.getBoundsInScreen(rect);
            long area = (long) rect.width() * rect.height();
            if (area < bestArea) { bestArea = area; best = button; }
        }
        return best;
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

        // Prefer an input whose own non-value metadata identifies the target.
        for (AccessibilityNodeInfo field : fields) {
            String own = fieldDescriptor(field).toLowerCase(Locale.ROOT).trim()
                    .replaceAll("\\s+", " ");
            if (own.isEmpty() || isConflictingField(own, labels)) continue;
            for (String label : labels) {
                String wanted = label.toLowerCase(Locale.ROOT).trim();
                boolean referralAlias = (wanted.contains("referrer") || wanted.contains("referral")) &&
                        (own.contains("referrer") || own.contains("referral"));
                if (referralAlias || own.equals(wanted) || own.startsWith(wanted + " ") ||
                        own.startsWith(wanted + ":") || own.contains("/" + wanted) ||
                        own.contains("_" + wanted.replace(' ', '_')) ||
                        own.contains(" " + wanted + " ")) return field;
            }
        }

        // Otherwise only accept an input directly below the exact label and in the same column.
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo labelNode : all) {
            if (!labelNode.isVisibleToUser() || labelNode.isEditable()) continue;
            String labelText = textOf(labelNode).toLowerCase(Locale.ROOT).trim()
                    .replaceAll("\\s+", " ");
            boolean matches = false;
            for (String label : labels) {
                String wanted = label.toLowerCase(Locale.ROOT).trim();
                if (labelText.equals(wanted) || labelText.equals(wanted + "*") ||
                        labelText.equals(wanted + " *") || labelText.startsWith(wanted + ":")) {
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
                if (isConflictingField(fieldDescriptor(field).toLowerCase(Locale.ROOT), labels)) continue;
                Rect fieldBounds = new Rect();
                field.getBoundsInScreen(fieldBounds);
                if (fieldBounds.isEmpty()) continue;
                int gap = fieldBounds.top - labelBounds.bottom;
                if (gap < -dp(8) || gap > dp(110)) continue;
                int overlap = Math.min(labelBounds.right, fieldBounds.right) -
                        Math.max(labelBounds.left, fieldBounds.left);
                if (overlap <= dp(12) &&
                        Math.abs(labelBounds.centerX() - fieldBounds.centerX()) > dp(58)) continue;
                long score = (long) Math.max(0, gap) * 1000L +
                        Math.abs(labelBounds.centerX() - fieldBounds.centerX());
                if (score < bestScore) { bestScore = score; best = field; }
            }
        }
        return best;
    }

    private String fieldDescriptor(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder out = new StringBuilder();
        if (android.os.Build.VERSION.SDK_INT >= 26 && node.getHintText() != null)
            out.append(node.getHintText()).append(' ');
        if (node.getContentDescription() != null) out.append(node.getContentDescription()).append(' ');
        if (android.os.Build.VERSION.SDK_INT >= 18 && node.getViewIdResourceName() != null)
            out.append(node.getViewIdResourceName()).append(' ');
        return out.toString();
    }

    private boolean isConflictingField(String descriptor, String... targets) {
        if (descriptor == null || descriptor.isEmpty()) return false;
        String[] guards = {"first name", "given name", "middle name", "last name", "surname",
                "phone", "mobile", "email", "date of birth", "referrer", "referral",
                "address", "postal", "zip code", "district", "subdistrict"};
        for (String guard : guards) {
            if (!descriptor.contains(guard)) continue;
            boolean target = false;
            if (targets != null) for (String label : targets) {
                if (label != null && (label.toLowerCase(Locale.ROOT).contains(guard) ||
                        guard.contains(label.toLowerCase(Locale.ROOT)))) { target = true; break; }
            }
            if (!target) return true;
        }
        return false;
    }

    private String[] ruleLabels(String key, String... fallback) {
        try {
            String raw = state().getString("automation_rules_json", "{}");
            JSONArray array = new JSONObject(raw).optJSONArray(key);
            if (array == null || array.length() == 0) return fallback;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) values.add(value);
            }
            return values.isEmpty() ? fallback : values.toArray(new String[0]);
        } catch (Exception ignored) { return fallback; }
    }

    private void refreshRemoteRules() {
        long now = System.currentTimeMillis();
        if (now - state().getLong("automation_rules_attempt", 0L) < 60_000L) return;
        state().edit().putLong("automation_rules_attempt", now).apply();
        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://walking2129.github.io/Teenoi-Coupon/update-server/automation.json?t=" + now);
                if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                        !"walking2129.github.io".equalsIgnoreCase(url.getHost())) return;
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                StringBuilder body = new StringBuilder();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (body.length() > 48000) return;
                        body.append(new String(buffer, 0, count, "UTF-8"));
                    }
                }
                JSONObject rules = new JSONObject(body.toString());
                if (rules.optInt("schemaVersion", 0) == 1) {
                    state().edit().putString("automation_rules_json", body.toString())
                            .putLong("automation_rules_fetched", System.currentTimeMillis()).apply();
                }
            } catch (Exception ignored) {
                // Last-known-good rules are retained when the network is unavailable.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "KimAutoBuyer-RemoteRules");
        worker.setDaemon(true);
        worker.start();
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

    private boolean setNodeTextVerified(AccessibilityNodeInfo node, String value) {
        if (node == null || !node.isEditable() || value == null) return false;
        if (!setNodeText(node, value)) return false;
        try {
            node.refresh();
            CharSequence actual = node.getText();
            return actual != null && actual.toString().trim().equals(value.trim());
        } catch (Exception ignored) { return false; }
    }

    private boolean clickAction(AccessibilityNodeInfo root, String[] matches, boolean bottomFallback) {
        AccessibilityNodeInfo target = findClickableTextAction(root, matches);
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
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
        AccessibilityNodeInfo button = findClickableAncestor(node, null);
        return button != null && isReasonableAction(button) &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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

    private boolean swipeUp() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(width / 2f, height * 0.82f);
        path.lineTo(width / 2f, height * 0.34f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 420))
                .build();
        return dispatchGesture(gesture, null, null);
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
        StringBuilder fieldHints = new StringBuilder();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isClickable()) clickable++;
            if (n.isEditable()) {
                editable++;
                // Only non-sensitive accessibility metadata is logged, never entered values.
                String hint = fieldDescriptor(n).trim();
                Rect bounds = new Rect();
                n.getBoundsInScreen(bounds);
                if (fieldHints.length() < 850) fieldHints.append(" {")
                        .append(hint.isEmpty() ? n.getClassName() : hint)
                        .append("@").append(bounds.left).append(",").append(bounds.top)
                        .append(",").append(bounds.right).append(",").append(bounds.bottom).append("}");
            }
            if (!n.isEditable() && (n.isClickable() || n.isCheckable())) {
                String label = textOf(n).trim();
                if (!label.isEmpty() && labels.length() < 500) labels.append(" [").append(label).append("]");
            }
        }
        String pkg = root.getPackageName() == null ? "?" : root.getPackageName().toString();
        String debug = "package=" + pkg + ", step=" + step + ", clickable=" + clickable +
                ", editable=" + editable + ", fields=" + fieldHints + ", controls=" + labels;
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
