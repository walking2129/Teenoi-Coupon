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
    private static final int DONE = 8;

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

        AccessibilityNodeInfo addAddress = findOptionText(root, "add address", "เพิ่มที่อยู่");
        if (addAddress != null && clickNodeOrTap(addAddress)) {
            state().edit().putBoolean("address_filled", false)
                    .putBoolean("zip_filled", false)
                    .putBoolean("district_selected", false)
                    .putBoolean("subdistrict_selected", false)
                    .putInt("district_open_attempts", 0)
                    .putInt("subdistrict_open_attempts", 0)
                    .putInt("address_scrolls", 0)
                    .remove("address_scroll_fingerprint").apply();
            setStep(ADDRESS, "ข้อมูล Passport และ Referral code ผ่านการตรวจแล้ว — กด Add address");
            return;
        }
        setStatus("ข้อมูล Passport ครบแล้ว กำลังค้นหาปุ่ม Add address");
    }

    private boolean handleDateOfBirth(AccessibilityNodeInfo root) {
        String[] labels = ruleLabels("dateOfBirthLabels",
                "date of birth", "dob", "วันเดือนปีเกิด", "วันเกิด");
        AccessibilityNodeInfo field = findFieldForLabel(root, labels);
        if (field != null) {
            if (dateTextMatches(textOf(field), "21/11/1986")) {
                state().edit().putBoolean("dob_filled", true).apply();
                setStatus("ตรวจพบวันเกิด 21/11/1986 ในช่อง Date of birth แล้ว");
                return true;
            }
            if (field.isEditable() && setNodeTextVerified(field, "21/11/1986")) {
                state().edit().putBoolean("dob_filled", true).apply();
                setStatus("กรอกวันเกิดและตรวจค่าจากช่องแล้ว");
                return true;
            }
            if (!state().getBoolean("dob_picker_opened", false) && clickNodeOrTap(field)) {
                state().edit().putBoolean("dob_picker_opened", true)
                        .putString("status", "เปิดตัวเลือกวันเกิดแล้ว กำลังตรวจปฏิทิน").apply();
                return true;
            }
        }

        if (state().getBoolean("dob_picker_opened", false)) {
            AccessibilityNodeInfo textMode = findText(root,
                    "switch to text input mode", "text input mode", "ป้อนวันที่");
            if (textMode != null && clickNodeOrTap(textMode)) {
                setStatus("เปลี่ยนปฏิทินเป็นช่องป้อนวันที่แล้ว");
                return true;
            }
            AccessibilityNodeInfo dateInput = findDatePickerInput(root);
            if (dateInput != null && setNodeTextVerified(dateInput, "11/21/1986")) {
                if (clickExactAction(root, new String[]{"ok", "done", "confirm", "ตกลง", "ยืนยัน"})) {
                    setStatus("ใส่วันเกิดในปฏิทินแล้ว กำลังตรวจบนแบบฟอร์ม");
                } else {
                    setStatus("ใส่วันเกิดในตัวเลือกแล้ว กำลังรอปุ่มยืนยัน");
                }
                return true;
            }
            setStatus("เปิดตัวเลือกวันเกิดแล้ว แต่ไม่มีช่องป้อนวันที่ที่ระบุได้ — หยุดไว้ ไม่ปัดหรือกรอกช่องอื่น");
            return true;
        }

        AccessibilityNodeInfo control = findControlForLabel(root, labels);
        if (control != null && clickNodeOrTap(control)) {
            state().edit().putBoolean("dob_picker_opened", true)
                    .putString("status", "แตะช่อง Date of birth แล้ว กำลังเปิดปฏิทิน").apply();
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findDatePickerInput(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> fields = editableNodes(root);
        for (AccessibilityNodeInfo field : fields) {
            String descriptor = fieldDescriptor(field).toLowerCase(Locale.ROOT);
            if (descriptor.contains("date") || descriptor.contains("day") ||
                    descriptor.contains("month") || descriptor.contains("year")) return field;
        }
        if (fields.size() == 1 && hasAny(root, "switch to text input mode",
                "select date", "choose date", "เลือกวันที่")) return fields.get(0);
        return null;
    }

    private boolean dateTextMatches(String actual, String expected) {
        String a = actual == null ? "" : actual.trim().toLowerCase(Locale.ROOT);
        String e = expected.trim().toLowerCase(Locale.ROOT);
        if (a.equals(e)) return true;
        String da = a.replaceAll("[^0-9]", "");
        String de = e.replaceAll("[^0-9]", "");
        return !da.isEmpty() && da.equals(de);
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
        if (!state().getBoolean("address_filled", false)) {
            AccessibilityNodeInfo address = findFieldForLabel(root, ruleLabels("addressLabels",
                    "address", "address line 1", "house no", "house number", "ที่อยู่", "บ้านเลขที่"));
            if (address != null) {
                if (setNodeTextVerified(address, "888")) {
                    state().edit().putBoolean("address_filled", true).apply();
                    setStatus("กรอก Address และตรวจค่าจากช่องแล้ว");
                } else setStatus("พบช่อง Address แต่ยืนยันค่าไม่ได้ — ไม่กรอกช่องอื่นแทน");
                return;
            }
            scrollAddressForm(root, "กำลังค้นหาช่อง Address");
            return;
        }

        if (!state().getBoolean("zip_filled", false)) {
            AccessibilityNodeInfo zip = findFieldForLabel(root, ruleLabels("postalCodeLabels",
                    "zip code", "postal code", "postcode", "รหัสไปรษณีย์"));
            if (zip != null) {
                if (setNodeTextVerified(zip, "20000")) {
                    state().edit().putBoolean("zip_filled", true).apply();
                    setStatus("กรอก Zip code และตรวจค่าจากช่องแล้ว");
                } else setStatus("พบช่อง Zip code แต่ยืนยันค่าไม่ได้");
                return;
            }
            scrollAddressForm(root, "กำลังค้นหาช่อง Zip code");
            return;
        }

        if (!state().getBoolean("district_selected", false)) {
            AccessibilityNodeInfo option = findOptionText(root,
                    ruleLabels("districtOptions", "mueang chon buri", "เมืองชลบุรี"));
            if (option != null && clickNodeOrTap(option)) {
                state().edit().putBoolean("district_selected", true)
                        .putString("status", "เลือก District: Mueang Chon Buri แล้ว").apply();
                return;
            }
            AccessibilityNodeInfo control = findControlForLabel(root,
                    ruleLabels("districtLabels", "district", "อำเภอ"));
            int attempts = state().getInt("district_open_attempts", 0);
            if (control != null && attempts < 3 && clickNodeOrTap(control)) {
                state().edit().putInt("district_open_attempts", attempts + 1)
                        .putString("status", "เปิดรายการ District เพื่อเลือก Mueang Chon Buri").apply();
                return;
            }
            scrollAddressForm(root, "กำลังค้นหารายการ District: Mueang Chon Buri");
            return;
        }

        if (!state().getBoolean("subdistrict_selected", false)) {
            AccessibilityNodeInfo option = findOptionText(root,
                    ruleLabels("subdistrictOptions", "bang pla soi", "bang pla soy", "บางปลาสร้อย"));
            if (option != null && clickNodeOrTap(option)) {
                state().edit().putBoolean("subdistrict_selected", true)
                        .putString("status", "เลือก Sub-district: Bang Pla Soi แล้ว").apply();
                return;
            }
            AccessibilityNodeInfo control = findControlForLabel(root,
                    ruleLabels("subdistrictLabels", "sub-district", "subdistrict", "ตำบล"));
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
            scrollAddressForm(root, "กรอกที่อยู่ครบแล้ว กำลังค้นหาปุ่ม Save");
        }
    }

    private void doApply(AccessibilityNodeInfo root) {
        if (hasAny(root, "application submitted", "submitted successfully",
                "ส่งใบสมัครเรียบร้อย", "สมัครสำเร็จ")) {
            stopWith("ส่งใบสมัครแล้ว");
            return;
        }

        if (clickExactAction(root, new String[]{"apply now", "apply", "ยื่นใบสมัคร"})) {
            stopWith("กด Apply แล้ว กรุณาตรวจผลบนหน้าจอ Makro PRO");
            return;
        }

        setStatus("บันทึกที่อยู่แล้ว กำลังรอปุ่ม Apply สีแดง");
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
                if (own.equals(wanted) || own.startsWith(wanted + " ") ||
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
