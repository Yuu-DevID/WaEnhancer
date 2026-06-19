package com.yusuf.waantidelete.features;

import android.util.Log;

import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke {

    private static final String TAG = "WaAntiDelete";
    private final ClassLoader loader;
    private static final Set<String> revokedMessages = new HashSet<>();

    private static Class<?> fMessageClass;
    private static Field fMessageKeyField;
    private static Field keyMessageIdField;
    private static Field keyIsFromMeField;
    private static Field keyRemoteJidField;
    private static Field fMessageDeviceJidField;

    public AntiRevoke(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        initFMessageFields();
        hookAntiRevokeMessage();
        hookAntiRevokeStatus();
    }

    private void initFMessageFields() {
        try {
            fMessageClass = Unobfuscator.loadFMessageClass(loader);
            Log.i(TAG, "FMessage class: " + fMessageClass.getName());

            fMessageKeyField = Unobfuscator.loadMessageKeyField(loader);
            Class<?> keyClass = fMessageKeyField.getType();
            Log.i(TAG, "Key class: " + keyClass.getName());

            keyMessageIdField = findFieldInClass(keyClass, String.class);
            keyIsFromMeField = findFieldInClass(keyClass, boolean.class);
            keyRemoteJidField = findFirstObjectField(keyClass);

            fMessageDeviceJidField = findDeviceJidField(fMessageClass);

            Log.i(TAG, "Fields: messageId=" + fieldName(keyMessageIdField)
                    + ", isFromMe=" + fieldName(keyIsFromMeField)
                    + ", remoteJid=" + fieldName(keyRemoteJidField)
                    + ", deviceJid=" + fieldName(fMessageDeviceJidField));

            Log.i(TAG, "FMessage fields:");
            for (Field f : fMessageClass.getDeclaredFields()) {
                Log.i(TAG, "  " + f.getName() + " -> " + f.getType().getSimpleName());
            }
            Log.i(TAG, "Key fields:");
            for (Field f : keyClass.getDeclaredFields()) {
                Log.i(TAG, "  " + f.getName() + " -> " + f.getType().getSimpleName());
            }
        } catch (Throwable e) {
            Log.e(TAG, "initFMessageFields failed", e);
        }
    }

    private void hookAntiRevokeMessage() {
        try {
            Method revokeMethod = Unobfuscator.loadAntiRevokeMessageMethod(loader);
            Log.i(TAG, "Revoke method: " + revokeMethod.getDeclaringClass().getName()
                    + "->" + revokeMethod.getName());

            XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object fMessageObj = findArgOfType(param.args, fMessageClass);
                        if (fMessageObj == null) return;

                        Object key = fMessageKeyField.get(fMessageObj);
                        if (key == null) return;

                        String messageId = (String) keyMessageIdField.get(key);
                        boolean isFromMe = keyIsFromMeField.getBoolean(key);
                        Object deviceJid = fMessageDeviceJidField != null
                                ? fMessageDeviceJidField.get(fMessageObj) : null;

                        boolean isGroup = isJidGroup(keyRemoteJidField.get(key));

                        if (isGroup) {
                            if (deviceJid != null && !isFromMe && messageId != null) {
                                revokedMessages.add(messageId);
                                param.setResult(true);
                                Log.i(TAG, "BLOCKED group revoke: " + messageId);
                            }
                        } else {
                            if (!isFromMe && messageId != null) {
                                revokedMessages.add(messageId);
                                param.setResult(true);
                                Log.i(TAG, "BLOCKED private revoke: " + messageId);
                            }
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "Revoke hook error: " + e.getMessage(), e);
                    }
                }
            });
            Log.i(TAG, "AntiRevoke message hook SUCCESS");
        } catch (Throwable e) {
            Log.e(TAG, "AntiRevoke message hook FAILED: " + e.getMessage(), e);
            throw new RuntimeException("AntiRevoke message hook failed", e);
        }
    }

    private void hookAntiRevokeStatus() {
        try {
            Method revokeStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);
            Log.i(TAG, "Status revoke method: " + revokeStatusMethod.getName());

            XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 1) return;
                        Object fStatusKey = param.args[0];
                        if (fStatusKey == null) return;

                        boolean isFromMe = XposedHelpers.getBooleanField(fStatusKey, "A03");
                        String messageId = (String) XposedHelpers.getObjectField(fStatusKey, "A02");

                        if (!isFromMe && messageId != null && !messageId.isEmpty()) {
                            revokedMessages.add(messageId);
                            param.setResult(0);
                            Log.i(TAG, "BLOCKED status revoke: " + messageId);
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "Status revoke error: " + e.getMessage(), e);
                    }
                }
            });
            Log.i(TAG, "AntiRevokeStatus hook SUCCESS");
        } catch (Throwable e) {
            Log.w(TAG, "AntiRevokeStatus hook skipped: " + e.getMessage());
        }
    }

    private static Object findArgOfType(Object[] args, Class<?> type) {
        if (args == null || type == null) return null;
        for (Object arg : args) {
            if (arg != null && type.isInstance(arg)) return arg;
        }
        return null;
    }

    private static boolean isJidGroup(Object remoteJid) {
        if (remoteJid == null) return false;
        try {
            String raw = (String) XposedHelpers.callMethod(remoteJid, "getRawString");
            return raw != null && raw.endsWith("@g.us");
        } catch (Throwable e) {
            return false;
        }
    }

    private static Field findFieldInClass(Class<?> clazz, Class<?> type) {
        if (clazz == null) return null;
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == type) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Field findFirstObjectField(Class<?> clazz) {
        if (clazz == null) return null;
        for (Field f : clazz.getDeclaredFields()) {
            if (!f.getType().isPrimitive() && f.getType() != String.class) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Field findDeviceJidField(Class<?> fMessageClass) {
        if (fMessageClass == null) return null;
        try {
            Class<?> deviceJidClass = Class.forName("com.whatsapp.jid.DeviceJid", false, loader);
            for (Field f : fMessageClass.getDeclaredFields()) {
                if (deviceJidClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable e) {
            for (Field f : fMessageClass.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.contains("devicejid") || name.contains("device")) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private static String fieldName(Field f) {
        return f != null ? f.getName() : "null";
    }
}
