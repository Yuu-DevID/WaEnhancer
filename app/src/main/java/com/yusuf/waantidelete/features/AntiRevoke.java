package com.yusuf.waantidelete.features;

import android.util.Log;

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

    private static final String TAG = "WaAntiDelete";
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
            String fullSig = revokeMethod.getDeclaringClass().getName() + "->" + revokeMethod.getName() + "(";
            Class<?>[] params = revokeMethod.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) fullSig += ", ";
                fullSig += params[i].getSimpleName();
            }
            fullSig += ")";
            Log.i(TAG, "Hooking revoke method: " + fullSig);

            XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, ">>> REVOKE METHOD TRIGGERED! args.length=" + param.args.length);

                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (arg == null) {
                            Log.i(TAG, "  arg[" + i + "] = null");
                        } else {
                            Log.i(TAG, "  arg[" + i + "] = " + arg.getClass().getName() + " @ " + arg.hashCode());
                            if (fMessageClass != null && fMessageClass.isInstance(arg)) {
                                Log.i(TAG, "  >>> This arg IS an FMessage!");
                            }
                        }
                    }

                    try {
                        Object fMessageObj = findFMessageInArgs(param.args);
                        if (fMessageObj == null) {
                            Log.w(TAG, "  No FMessage found in args, checking all args for Key field...");
                            for (Object arg : param.args) {
                                if (arg == null) continue;
                                try {
                                    Field kf = findKeyField(arg.getClass());
                                    if (kf != null) {
                                        Log.i(TAG, "  Found Key field in arg class: " + arg.getClass().getName());
                                        Object key = kf.get(arg);
                                        if (key != null) {
                                            String msgId = extractMessageIdFromKey(key);
                                            boolean fromMe = extractIsFromMeFromKey(key);
                                            Log.i(TAG, "  Key messageID=" + msgId + ", isFromMe=" + fromMe);
                                            if (!fromMe && msgId != null) {
                                                revokedMessages.add(msgId);
                                                Log.i(TAG, "  >>> BLOCKED REVOKE (via Key field): " + msgId);
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                            return;
                        }

                        String messageId = extractMessageId(fMessageObj);
                        boolean isFromMe = extractIsFromMe(fMessageObj);
                        Log.i(TAG, "  FMessage found: messageId=" + messageId + ", isFromMe=" + isFromMe);

                        if (!isFromMe && messageId != null && !messageId.isEmpty()) {
                            revokedMessages.add(messageId);
                            param.setResult(true);
                            Log.i(TAG, "  >>> REVOKE BLOCKED: " + messageId);
                        } else {
                            Log.i(TAG, "  Revoke not blocked (fromMe=" + isFromMe + ")");
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "  Revoke hook processing error: " + e.getMessage(), e);
                    }
                }
            });
            Log.i(TAG, "AntiRevoke message hook attached SUCCESS");
        } catch (Throwable e) {
            Log.e(TAG, "AntiRevoke message hook FAILED: " + e.getMessage(), e);
            throw new RuntimeException("AntiRevoke message hook failed", e);
        }
    }

    private void hookAntiRevokeStatus() {
        try {
            Method revokeStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);
            Log.i(TAG, "Hooking status revoke method: " + revokeStatusMethod.getName());

            XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, ">>> STATUS REVOKE TRIGGERED! args.length=" + param.args.length);
                    try {
                        if (param.args.length < 1) return;
                        Object fStatusKey = param.args[0];
                        if (fStatusKey == null) return;

                        Log.i(TAG, "  FStatusKey class: " + fStatusKey.getClass().getName());

                        boolean isFromMe = XposedHelpers.getBooleanField(fStatusKey, "A03");
                        String messageId = (String) XposedHelpers.getObjectField(fStatusKey, "A02");
                        Log.i(TAG, "  Status messageId=" + messageId + ", isFromMe=" + isFromMe);

                        if (!isFromMe && messageId != null && !messageId.isEmpty()) {
                            revokedMessages.add(messageId);
                            param.setResult(0);
                            Log.i(TAG, "  >>> STATUS REVOKE BLOCKED: " + messageId);
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "  Status revoke error: " + e.getMessage(), e);
                    }
                }
            });
            Log.i(TAG, "AntiRevokeStatus hook attached SUCCESS");
        } catch (Throwable e) {
            Log.w(TAG, "AntiRevokeStatus hook skipped: " + e.getMessage());
        }
    }

    private Object findFMessageInArgs(Object[] args) {
        if (args == null) return null;

        try {
            if (fMessageClass == null) {
                fMessageClass = Unobfuscator.loadFMessageClass(loader);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load FMessage class: " + e.getMessage());
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

    private Field findKeyField(Class<?> clazz) {
        try {
            if (keyField == null) {
                keyField = Unobfuscator.loadMessageKeyField(loader);
            }
            for (Field f : clazz.getDeclaredFields()) {
                if (keyField.getType().isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractMessageId(Object fMessageObj) {
        try {
            if (keyField == null) {
                keyField = Unobfuscator.loadMessageKeyField(loader);
            }
            Object key = keyField.get(fMessageObj);
            if (key == null) return null;
            return extractMessageIdFromKey(key);
        } catch (Throwable e) {
            Log.e(TAG, "extractMessageId error: " + e.getMessage());
            return null;
        }
    }

    private String extractMessageIdFromKey(Object key) {
        try {
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
            return extractIsFromMeFromKey(key);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean extractIsFromMeFromKey(Object key) {
        try {
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
