package com.yusuf.waantidelete.features;

import android.graphics.drawable.Drawable;
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
        try {
            fMessageClass = Unobfuscator.loadFMessageClass(loader);
            keyField = Unobfuscator.loadMessageKeyField(loader);
            Class<?> keyClass = keyField.getType();
            keyMessageIdField = ReflectionUtils.findField(keyClass, f ->
                    f.getType() == String.class);
            keyIsFromMeField = ReflectionUtils.findField(keyClass, f ->
                    f.getType() == boolean.class);

            hookAntiRevokeMessage();
            hookAntiRevokeStatus();
            hookConversationRow();

            XposedBridge.log("[WaAntiDelete] AntiRevoke hooked");
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] AntiRevoke hook failed: " + e.getMessage());
            XposedBridge.log(e);
        }
    }

    private void hookAntiRevokeMessage() {
        try {
            Method revokeMethod = Unobfuscator.loadAntiRevokeMessageMethod(loader);

            XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object fMessageObj = findFMessageInArgs(param.args);
                        if (fMessageObj == null) return;

                        Object key = keyField.get(fMessageObj);
                        if (key == null) return;

                        String messageId = (String) keyMessageIdField.get(key);
                        boolean isFromMe = keyIsFromMeField.getBoolean(key);

                        if (!isFromMe && messageId != null) {
                            revokedMessages.add(messageId);
                            param.setResult(true);
                            XposedBridge.log("[WaAntiDelete] Blocked message revoke: " + messageId);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[WaAntiDelete] Revoke hook error: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] AntiRevokeMessage hook failed: " + e.getMessage());
        }
    }

    private void hookAntiRevokeStatus() {
        try {
            Method revokeStatusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);

            XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2) return;

                        Object fStatusKey = param.args[1];
                        if (fStatusKey == null) return;

                        boolean isFromMe = XposedHelpers.getBooleanField(fStatusKey, "A03");
                        String messageId = (String) XposedHelpers.getObjectField(fStatusKey, "A02");

                        if (!isFromMe && messageId != null) {
                            revokedMessages.add(messageId);
                            param.setResult(0);
                            XposedBridge.log("[WaAntiDelete] Blocked status revoke: " + messageId);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[WaAntiDelete] Status revoke hook error: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] AntiRevokeStatus hook failed: " + e.getMessage());
        }
    }

    private void hookConversationRow() {
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.AdapterView.class,
                "setAdapter",
                android.widget.ListAdapter.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object adapter = param.args[0];
                            if (adapter == null) return;

                            XposedHelpers.findAndHookMethod(
                                adapter.getClass(),
                                "getView",
                                int.class, View.class, ViewGroup.class,
                                new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        try {
                                            View convertView = (View) param.args[1];
                                            if (convertView == null) return;

                                            TextView dateView = convertView.findViewById(
                                                convertView.getContext().getResources().getIdentifier(
                                                    "date", "id", convertView.getContext().getPackageName()));
                                            if (dateView == null) return;

                                            markRevokedMessages(dateView);
                                        } catch (Throwable ignored) {}
                                    }
                                });
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] ConversationRow hook skipped: " + e.getMessage());
        }
    }

    private void markRevokedMessages(TextView dateView) {
        try {
            if (revokedMessages.isEmpty()) return;

            Drawable deletedIcon = dateView.getContext().getDrawable(android.R.drawable.ic_menu_delete);
            if (deletedIcon != null) {
                int size = (int) (dateView.getTextSize() * 1.2);
                deletedIcon.setBounds(0, 0, size, size);
                dateView.setCompoundDrawables(null, null, deletedIcon, null);
                dateView.setCompoundDrawablePadding(8);
            }
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] markRevoked error: " + e.getMessage());
        }
    }

    private Object findFMessageInArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && fMessageClass.isInstance(arg)) {
                return arg;
            }
        }
        return null;
    }
}
