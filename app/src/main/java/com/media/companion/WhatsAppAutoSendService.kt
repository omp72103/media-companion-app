package com.media.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Completes a WhatsApp auto-reply: when WhatsAppSender has opened a chat with
 * the message pre-filled, this taps the "send" button and returns the phone
 * to the home screen. Scoped (in its XML config) to the WhatsApp packages
 * only, and it acts ONLY while a send is pending — it never touches WhatsApp
 * during normal use.
 */
class WhatsAppAutoSendService : AccessibilityService() {

    companion object {
        /** Live instance — WhatsAppSender launches chats through it (allowed from background). */
        @Volatile var instance: WhatsAppAutoSendService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Anything that queued up while the service was off goes out now.
        WhatsAppSender.process(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!WhatsAppSender.hasPending()) return

        val root = rootInActiveWindow ?: return
        try {
            // Number not on WhatsApp → WhatsApp shows a dialog. Dismiss, skip.
            if (dismissInvalidDialog(root)) {
                WhatsAppSender.onInvalidNumber(this)
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            val send = findSendButton(root, pkg) ?: return
            if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                WhatsAppSender.onSent(this)
                // Return the business phone to idle.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    override fun onInterrupt() {}

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in listOf("$pkg:id/send", "$pkg:id/send_container")) {
            root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let { return if (it.isClickable) it else clickableParent(it) ?: it }
        }
        return firstClickableByDesc(root, listOf("send", "पाठवा", "भेजें"))
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        while (p != null) { if (p.isClickable) return p; p = p.parent }
        return null
    }

    private fun dismissInvalidDialog(root: AccessibilityNodeInfo): Boolean {
        val hit = listOf("isn't on WhatsApp", "not on WhatsApp", "invalid")
            .any { (root.findAccessibilityNodeInfosByText(it) ?: emptyList()).isNotEmpty() }
        if (!hit) return false
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
