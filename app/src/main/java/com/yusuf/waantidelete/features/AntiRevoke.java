package com.yusuf.waantidelete.features;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.yusuf.waantidelete.hook.ReflectionUtils;
import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke {

    private final ClassLoader loader;
    private static final Set<String> revokedMessages = new HashSet<>();
    private static Class<?> fMessageClass;
    private static Field keyField;
    private static Field keyMessageIdField;
    private static Field keyIsFromMeField;

    public AntiRevoke(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        hookAntiRevokeMessage();
        hookAntiRevokeStatus();
    }

    private void hookAntiRevokeMessage() {
        try {
            Method revokeMethod = Unobfuscator.loadAntiRevokeMessageMethod(loader);
            XposedBridge.log("[WaAntiDelete] AntiRevoke: found revoke method -> " + revokeMethod.getName());

            XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object fMessageObj = findFMessageInArgs(param.args);
                        if (fMessageObj == null) return;

                        String messageId = extractMessageId(fMessageObj);
                        boolean isFromMe = extractIsFromMe(fMessageObj);

                        if (!isFromMe && messageId != null && !messageId.isEmpty()) {
                            revokedMessages.add(messageId);
                            param.setResult(param.method.getReturnType() == boolean.class ? true : 0);
                            XposedBridge.log("[WaAntiDelete] Blocked message revoke: " + messageId);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[WaAntiDelete] Revoke hook error: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[WaAntiDelete] AntiRevoke message hook SUCCESS");
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] AntiRevoke message hook FAILED: " + e.getMessage());
            throw new RuntimeException("AntiRevoke message hook failed", e);
        }
    }

    private void hookAntiRevokeStatus() {
        try {
            Method revokeStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);
            XposedBridge.log("[WaAntiDelete] AntiRevokeStatus: found method -> " + revokeStatusMethod.getName());

            XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2) return;

                        Object fStatusKey = param.args[1];
                        if (fStatusKey == null) return;

                        boolean isFromMe = XposedHelpers.getBooleanField(fStatusKey, "A03");
                        String messageId = (String) XposedHelpers.getObjectField(fStatusKey, "A02");

                        if (!isFromMe && messageId != null && !messageId.isEmpty()) {
                            revokedMessages.add(messageId);
                            param.setResult(0);
                            XposedBridge.log("[WaAntiDelete] Blocked status revoke: " + messageId);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[WaAntiDelete] Status revoke hook error: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[WaAntiDelete] AntiRevokeStatus hook SUCCESS");
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] AntiRevokeStatus hook FAILED: " + e.getMessage());
            throw new RuntimeException("AntiRevokeStatus hook failed", e);
        }
    }

    private Object findFMessageInArgs(Object[] args) {
        if (args == null) return null;

        try {
            if (fMessageClass == null) {
                fMessageClass = Unobfuscator.loadFMessageClass(loader);
            }
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] Failed to load FMessage class: " + e.getMessage());
            return null;
        }

        for (Object arg : args) {
            if (arg != null && fMessageClass.isInstance(arg)) {
                return arg;
            }
        }

        for (Object arg : args) {
            if (arg == null) continue;
            try {
                for (Field f : arg.getClass().getDeclaredFields()) {
                    if (fMessageClass.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object inner = f.get(arg);
                        if (inner != null && fMessageClass.isInstance(inner)) {
                            return inner;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private String extractMessageId(Object fMessageObj) {
        try {
            if (keyField == null) {
                keyField = Unobfuscator.loadMessageKeyField(loader);
            }
            Object key = keyField.get(fMessageObj);
            if (key == null) return null;

            if (keyMessageIdField == null) {
                keyMessageIdField = ReflectionUtils.findField(key.getClass(), f ->
                        f.getType() == String.class && f.getName().equals("A01"));
            }
            if (keyMessageIdField == null) {
                keyMessageIdField = ReflectionUtils.findField(key.getClass(), f ->
                        f.getType() == String.class);
            }
            return (String) keyMessageIdField.get(key);
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] extractMessageId error: " + e.getMessage());
            return null;
        }
    }

    private boolean extractIsFromMe(Object fMessageObj) {
        try {
            if (keyField == null) {
                keyField = Unobfuscator.loadMessageKeyField(loader);
            }
            Object key = keyField.get(fMessageObj);
            if (key == null) return false;

            if (keyIsFromMeField == null) {
                keyIsFromMeField = ReflectionUtils.findField(key.getClass(), f ->
                        f.getType() == boolean.class && f.getName().equals("A02"));
            }
            if (keyIsFromMeField == null) {
                keyIsFromMeField = ReflectionUtils.findField(key.getClass(), f ->
                        f.getType() == boolean.class);
            }
            return keyIsFromMeField.getBoolean(key);
        } catch (Throwable e) {
            return false;
        }
    }
}
