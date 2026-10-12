package com.mifi.helper;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class UssdAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (!pkg.startsWith("com.android.phone") && !pkg.startsWith("com.android.dialer")) return;

        if (HttpApiService.lastUssd.get() != null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        String text = sb.toString().trim();
        if (text.length() > 3 && !"OK".equalsIgnoreCase(text)) {
            HttpApiService.lastUssd.set(text);
        }
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) sb.append(t.toString()).append('\n');
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            collect(node.getChild(i), sb, depth + 1);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
