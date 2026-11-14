package com.media.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Completes a WhatsApp auto-reply: when WhatsAppSender has opened a chat with
 * the message pre-filled, this taps the "send" button. Scoped (in its XML
 * config) to the WhatsApp packages only, and it acts ONLY while a send is
 * pending — it never touches WhatsApp during normal use.
 */
class WhatsAppAutoSendService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!WhatsAppSender.hasPending()) return

        val root = rootInActiveWindow ?: return
        try {
            // If the number isn't on WhatsApp, WhatsApp shows an "invalid
            // number" dialog — dismiss it and give up rather than hang.
            if (dismissInvalidDialog(root)) {
                WhatsAppSender.clearPending()
                return
            }
            val send = findSendButton(root, pkg) ?: return
            if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                WhatsAppSender.onSent()
                // Leave the chat so the business phone returns to idle.
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    override fun onInterrupt() {}

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        // Most reliable: the send FAB's view id.
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            ?.firstOrNull { it.isVisibleToUser }
            ?.let { return it }
        // Fallback: a clickable node described as "Send".
        return firstClickableByDesc(root, listOf("send"))
    }

    private fun dismissInvalidDialog(root: AccessibilityNodeInfo): Boolean {
        val textNodes = root.findAccessibilityNodeInfosByText("invalid") ?: return false
        if (textNodes.isEmpty()) return false
        // Tap the dialog's OK/positive button if present.
        root.findAccessibilityNodeInfosByViewId("android:id/button1")
            ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
    }

    private fun firstClickableByDesc(node: AccessibilityNodeInfo?, descs: List<String>): AccessibilityNodeInfo? {
        node ?: return null
        val d = node.contentDescription?.toString()?.lowercase()
        if (d != null && descs.any { d.contains(it) } && node.isClickable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val hit = firstClickableByDesc(node.getChild(i), descs)
            if (hit != null) return hit
        }
        return null
    }
}
