package com.yusuf.waantidelete.features;

import android.util.Log;

import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    public AntiRevoke(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        hookAllRevokeCandidates();
    }

    private void hookAllRevokeCandidates() {
        try {
            fMessageClass = Unobfuscator.loadFMessageClass(loader);
            keyField = Unobfuscator.loadMessageKeyField(loader);
            Log.i(TAG, "FMessage: " + fMessageClass.getName() + ", Key field: " + keyField.getName());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init FMessage: " + e.getMessage());
            return;
        }

        // Hook ALL methods that contain revoke-related strings
        String[] searchPatterns = {
            "msgstore/revoke/missing-old-id",
            "msgstore/revoking/has-placeholder",
            "msgstore/revoke: Failed to re-insert",
            "FMessageRevokedFactory/cloneIncomingRevokeMessage"
        };

        int hookedCount = 0;
        for (String pattern : searchPatterns) {
            try {
                var methods = Unobfuscator.findAllMethodsByString(loader, pattern);
                for (Method method : methods) {
                    try {
                        hookRevokeMethod(method, pattern);
                        hookedCount++;
                    } catch (Throwable e) {
                        Log.w(TAG, "Failed to hook " + method.getName() + ": " + e.getMessage());
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "Search failed for '" + pattern + "': " + e.getMessage());
            }
        }

        // Also hook RevokeStatusManager
        try {
            Method statusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);
            hookStatusRevoke(statusMethod);
            hookedCount++;
        } catch (Throwable e) {
            Log.w(TAG, "Status revoke hook skipped: " + e.getMessage());
        }

        Log.i(TAG, "Hooked " + hookedCount + " revoke candidate methods");
    }

    private void hookRevokeMethod(Method method, String searchPattern) {
        String sig = method.getDeclaringClass().getSimpleName() + "->" + method.getName()
                + "(" + paramsSignature(method) + ")";
        Log.i(TAG, "Hooking revoke candidate [" + searchPattern + "]: " + sig);

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Log.i(TAG, ">>> REVOKE CANDIDATE TRIGGERED: " + sig);
                Log.i(TAG, "    this=" + (param.thisObject != null ? param.thisObject.getClass().getName() : "static"));
                Log.i(TAG, "    args.length=" + param.args.length);

                for (int i = 0; i < param.args.length; i++) {
                    Object arg = param.args[i];
                    if (arg == null) {
                        Log.i(TAG, "    arg[" + i + "] = null");
                    } else {
                        Log.i(TAG, "    arg[" + i + "] = " + arg.getClass().getName()
                                + " isFMessage=" + (fMessageClass != null && fMessageClass.isInstance(arg)));
                    }
                }

                // Try to find FMessage in args
                Object fMessageObj = findArgOfType(param.args, fMessageClass);
                if (fMessageObj != null) {
                    try {
                        Object key = keyField.get(fMessageObj);
                        if (key != null) {
                            String msgId = getMessageIdFromKey(key);
                            boolean isFromMe = getIsFromMeFromKey(key);
                            Log.i(TAG, "    FMessage: msgId=" + msgId + ", isFromMe=" + isFromMe);

                            if (!isFromMe && msgId != null && !msgId.isEmpty()) {
                                revokedMessages.add(msgId);
                                if (method.getReturnType() == boolean.class) {
                                    param.setResult(true);
                                } else if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) {
                                    param.setResult(0);
                                }
                                Log.i(TAG, "    >>> REVOKE BLOCKED: " + msgId);
                            }
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "    Error processing FMessage: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "    No FMessage found in args");
                }
            }
        });
        Log.i(TAG, "Hook attached: " + sig);
    }

    private void hookStatusRevoke(Method method) {
        String sig = method.getDeclaringClass().getSimpleName() + "->" + method.getName();
        Log.i(TAG, "Hooking status revoke: " + sig);

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Log.i(TAG, ">>> STATUS REVOKE TRIGGERED: " + sig);
                Log.i(TAG, "    args.length=" + param.args.length);

                for (int i = 0; i < param.args.length; i++) {
                    Object arg = param.args[i];
                    if (arg == null) continue;
                    Log.i(TAG, "    arg[" + i + "] = " + arg.getClass().getName());
                    // Try to extract message info from the arg
                    try {
                        boolean hasA03 = hasField(arg.getClass(), "A03", boolean.class);
                        boolean hasA02 = hasField(arg.getClass(), "A02", String.class);
                        if (hasA03 && hasA02) {
                            boolean isFromMe = XposedHelpers.getBooleanField(arg, "A03");
                            String msgId = (String) XposedHelpers.getObjectField(arg, "A02");
                            Log.i(TAG, "    FStatusKey: msgId=" + msgId + ", isFromMe=" + isFromMe);
                            if (!isFromMe && msgId != null && !msgId.isEmpty()) {
                                revokedMessages.add(msgId);
                                param.setResult(0);
                                Log.i(TAG, "    >>> STATUS REVOKE BLOCKED: " + msgId);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });
        Log.i(TAG, "Status hook attached: " + sig);
    }

    // --- Helpers ---

    private static Object findArgOfType(Object[] args, Class<?> type) {
        if (args == null || type == null) return null;
        for (Object arg : args) {
            if (arg != null && type.isInstance(arg)) return arg;
        }
        return null;
    }

    private static String getMessageIdFromKey(Object key) {
        try {
            for (Field f : key.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    String val = (String) f.get(key);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean getIsFromMeFromKey(Object key) {
        try {
            for (Field f : key.getClass().getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    return f.getBoolean(key);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasField(Class<?> clazz, String name, Class<?> type) {
        try {
            Field f = clazz.getDeclaredField(name);
            return f.getType() == type;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String paramsSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }
}
