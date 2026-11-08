package com.kim.autobuyer;

import android.content.Context;
import android.graphics.Rect;
import android.os.Environment;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Records Accessibility structure for a user-led demonstration without field values. */
public final class LearningRecorder {
    private static final Object LOCK = new Object();
    private static final int MAX_NODES = 260;

    private LearningRecorder() {}

    public static File file(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) base = context.getFilesDir();
        if (!base.exists()) base.mkdirs();
        return new File(base, "kim-auto-buyer-manual-demo.jsonl");
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File f = file(context);
            if (f.exists()) f.delete();
        }
    }

    public static long size(Context context) {
        File f = file(context);
        return f.exists() ? f.length() : 0L;
    }

    public static void record(Context context, AccessibilityEvent event,
                              AccessibilityNodeInfo root) {
        if (root == null) return;
        try {
            JSONObject row = new JSONObject();
            row.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    Locale.US).format(new Date()));
            row.put("eventType", event == null ? "snapshot" : eventType(event));
            row.put("package", value(root.getPackageName()));
            row.put("source", event == null ? new JSONObject() : nodeMeta(event.getSource(), false));
            row.put("nodes", snapshot(root));
            synchronized (LOCK) {
                try (Writer out = new OutputStreamWriter(new FileOutputStream(file(context), true),
                        StandardCharsets.UTF_8)) {
                    out.write(row.toString());
                    out.write("\n");
                }
            }
        } catch (Exception ignored) {
            // Recording must never interrupt the Accessibility service.
        }
    }

    private static JSONArray snapshot(AccessibilityNodeInfo root) {
        JSONArray result = new JSONArray();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        int count = 0;
        for (AccessibilityNodeInfo node : nodes) {
            if (count++ >= MAX_NODES) break;
            try { result.put(nodeMeta(node, true)); } catch (Exception ignored) { }
        }
        return result;
    }

    private static void collect(AccessibilityNodeInfo node,
                                List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }

    private static JSONObject nodeMeta(AccessibilityNodeInfo node, boolean includeText) {
        JSONObject o = new JSONObject();
        try {
            if (node == null) return o;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            o.put("class", value(node.getClassName()));
            o.put("resourceId", value(node.getViewIdResourceName()));
            o.put("contentDescription", value(node.getContentDescription()));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                o.put("hint", value(node.getHintText()));
            }
            // Text is safe only for non-editable controls; never write entered values.
            if (includeText && !node.isEditable()) o.put("text", value(node.getText()));
            if (node.isEditable()) {
                o.put("textMasked", true);
                CharSequence text = node.getText();
                o.put("textLength", text == null ? 0 : text.length());
                o.put("inputType", node.getInputType());
            }
            o.put("left", r.left);
            o.put("top", r.top);
            o.put("right", r.right);
            o.put("bottom", r.bottom);
            o.put("visible", node.isVisibleToUser());
            o.put("enabled", node.isEnabled());
            o.put("clickable", node.isClickable());
            o.put("checkable", node.isCheckable());
            o.put("checked", node.isChecked());
            o.put("focused", node.isFocused());
            o.put("editable", node.isEditable());
        } catch (Exception ignored) { }
        return o;
    }

    private static String eventType(AccessibilityEvent event) {
        return AccessibilityEvent.eventTypeToString(event.getEventType());
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
