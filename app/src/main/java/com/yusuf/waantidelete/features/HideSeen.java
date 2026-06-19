package com.yusuf.waantidelete.features;

import android.app.Application;
import android.os.Message;

import com.yusuf.waantidelete.hook.ReflectionUtils;
import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hide Seen Status feature - ported from WaEnhancer.
 * Blocks read receipts, played receipts, and status view receipts.
 */
public class HideSeen {

    private static final String TAG = "WaAntiDelete";
    private final ClassLoader loader;

    // Cached classes/methods
    private Class<?> fMessageClass;
    private Field keyField;
    private Class<?> abstractMediaMessageClass;

    public HideSeen(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        int hookedCount = 0;

        try {
            fMessageClass = Unobfuscator.loadFMessageClass(loader);
            keyField = Unobfuscator.loadMessageKeyField(loader);
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: Failed to load base classes: " + e.getMessage());
            throw new RuntimeException("HideSeen base init failed", e);
        }

        // 1. Hook SendReadReceiptJob - block read receipts
        try {
            hookSendReadReceiptJob();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: SendReadReceiptJob hook failed: " + e.getMessage());
        }

        // 2. Hook receipt method - intercept outgoing receipts
        try {
            hookReceiptMethod();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: Receipt method hook failed: " + e.getMessage());
        }

        // 3. Hook senderPlayed - block played receipts for media
        try {
            hookSenderPlayed();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: SenderPlayed hook failed: " + e.getMessage());
        }

        // 4. Hook senderPlayedBusiness - block played receipts for business media
        try {
            hookSenderPlayedBusiness();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: SenderPlayedBusiness hook failed: " + e.getMessage());
        }

        // 5. Hook onDispatchMessage (type 419) - intercept read receipt dispatch
        try {
            hookOnDispatchMessage();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: onDispatchMessage hook failed: " + e.getMessage());
        }

        XposedBridge.log("[" + TAG + "] HideSeen: hooked " + hookedCount + " methods");
        if (hookedCount == 0) {
            throw new RuntimeException("No HideSeen methods were successfully hooked");
        }
    }

    /**
     * Hook SendReadReceiptJob - when a read receipt job is about to execute, suppress it.
     */
    private void hookSendReadReceiptJob() {
        Method sendReadReceiptJobMethod = Unobfuscator.loadHideViewSendReadJob(loader);
        XposedBridge.hookMethod(sendReadReceiptJobMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object job = param.thisObject;
                    // Check if already has our marker
                    Boolean hasBlueOnReply = (Boolean) XposedHelpers.getAdditionalInstanceField(job, "blue_on_reply");
                    if (hasBlueOnReply != null && hasBlueOnReply) return;

                    // Get the jid field from the job
                    String lid = (String) XposedHelpers.getObjectField(job, "jid");
                    if (lid == null || lid.isEmpty() || lid.contains("lid_me") || lid.contains("status_me")) return;

                    // Block the receipt
                    param.setResult(null);
                    XposedBridge.log("[" + TAG + "] HideSeen: Blocked read receipt for " + lid);
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] HideSeen: SendReadReceiptJob hook error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook the receipt method - intercept outgoing receipts and modify them
     * to prevent read/played status from being sent.
     */
    private void hookReceiptMethod() {
        Method receiptMethod = Unobfuscator.loadReceiptMethod(loader);
        Class<?> receiptMessageInfoClass = Unobfuscator.loadReceiptMessageInfoClass(loader);

        // Hook onDispatchMessage to intercept type 419 (read receipt) messages
        try {
            Method[] onDispatchMethods = Unobfuscator.loadOndispatchMessage(loader);
            for (Method method : onDispatchMethods) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length == 0 || !(param.args[0] instanceof Message)) return;
                            Message message = (Message) param.args[0];
                            int type = message.arg1;
                            // Type 419 = read receipt, type 89 = played receipt
                            if (type != 419 && type != 89) return;
                            Object obj = message.obj;
                            if (!receiptMessageInfoClass.isInstance(obj)) return;

                            // Change message type to -1 to effectively block it
                            message.arg1 = -1;
                            XposedBridge.log("[" + TAG + "] HideSeen: Blocked dispatch message type " + type);
                        } catch (Throwable e) {
                            XposedBridge.log("[" + TAG + "] HideSeen: onDispatchMessage hook error: " + e.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: onDispatchMessage methods not found, skipping: " + e.getMessage());
        }

        // Hook the receipt method itself - after it creates the receipt node, modify it
        XposedBridge.hookMethod(receiptMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (result == null) return;

                    // The result is a ProtocolTreeNode - try to modify its type attribute
                    try {
                        Object typeAttr = XposedHelpers.callMethod(result, "getAttributeValue", "type");
                        if ("read".equals(typeAttr)) {
                            // Remove the read type to hide seen status
                            XposedHelpers.callMethod(result, "removeAttribute", "type");
                            XposedBridge.log("[" + TAG + "] HideSeen: Removed 'read' type from receipt");
                        }
                    } catch (Throwable ignored) {
                        // Method might not exist with this signature
                    }
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] HideSeen: receipt hook error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook senderPlayed method - block played receipts for media messages.
     */
    private void hookSenderPlayed() {
        try {
            Method senderPlayedMethod = Unobfuscator.loadSenderPlayedMethod(loader);
            XposedBridge.hookMethod(senderPlayedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        // Block the played receipt
                        param.setResult(null);
                        XposedBridge.log("[" + TAG + "] HideSeen: Blocked senderPlayed receipt");
                    } catch (Throwable e) {
                        XposedBridge.log("[" + TAG + "] HideSeen: senderPlayed hook error: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: senderPlayed not found: " + e.getMessage());
        }
    }

    /**
     * Hook senderPlayedBusiness method - block played receipts for business media.
     */
    private void hookSenderPlayedBusiness() {
        try {
            Method senderPlayedBusiness = Unobfuscator.loadSenderPlayedBusiness(loader);
            XposedBridge.hookMethod(senderPlayedBusiness, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        if (!(param.args[0] instanceof Set)) return;
                        Set<?> set = (Set<?>) param.args[0];
                        if (set.isEmpty()) return;

                        // Block the played receipt
                        param.setResult(null);
                        XposedBridge.log("[" + TAG + "] HideSeen: Blocked senderPlayedBusiness receipt");
                    } catch (Throwable e) {
                        XposedBridge.log("[" + TAG + "] HideSeen: senderPlayedBusiness hook error: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] HideSeen: senderPlayedBusiness not found: " + e.getMessage());
        }
    }

    /**
     * Hook onDispatchMessage methods for type 419 to track and block read receipt dispatches.
     */
    private void hookOnDispatchMessage() {
        Method[] methods = Unobfuscator.loadOndispatchMessage(loader);
        XposedBridge.log("[" + TAG + "] HideSeen: Found " + methods.length + " onDispatchMessage methods");

        for (Method method : methods) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length == 0 || !(param.args[0] instanceof Message)) return;
                            Message message = (Message) param.args[0];
                            if (message.arg1 != 419) return;

                            // Change the type to block the receipt dispatch
                            message.arg1 = -1;
                            XposedBridge.log("[" + TAG + "] HideSeen: Intercepted dispatch type 419");
                        } catch (Throwable e) {
                            XposedBridge.log("[" + TAG + "] HideSeen: dispatch hook error: " + e.getMessage());
                        }
                    }
                });
            } catch (Throwable e) {
                XposedBridge.log("[" + TAG + "] HideSeen: Failed to hook dispatch method: " + e.getMessage());
            }
        }
    }
}
