package com.yusuf.waantidelete.features;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.yusuf.waantidelete.R;
import com.yusuf.waantidelete.data.RevokedMessageStore;
import com.yusuf.waantidelete.hook.ReflectionUtils;
import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class AntiRevoke {

    private static final String TAG = "WaAntiDelete";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, Set<String>> REVOKED_IDS = new ConcurrentHashMap<>();

    private final ClassLoader loader;
    private final Application application;

    private Class<?> fMessageClass;
    private Field keyField;
    private Field deviceJidField;
    private Class<?> statusPlaybackViewClass;
    private Method unknownStatusPlaybackMethod;
    private int dateViewId;

    private static volatile Activity currentActivity;
    private static volatile BaseAdapter currentConversationAdapter;

    public AntiRevoke(ClassLoader loader, Application application) {
        this.loader = loader;
        this.application = application;
    }

    public int hook() {
        initializeMetadata();
        hookCurrentActivityTracker();
        hookConversationRows();
        hookRevokeMessages();
        hookRevokeStatuses();
        hookStatusPlaybackRows();
        return 5;
    }

    private void initializeMetadata() {
        try {
            fMessageClass = Unobfuscator.loadFMessageClass(loader);
            keyField = Unobfuscator.loadMessageKeyField(loader);
            deviceJidField = ReflectionUtils.findFieldIfExists(
                    fMessageClass,
                    field -> field.getType().getName().endsWith("jid.DeviceJid")
            );
            statusPlaybackViewClass = Unobfuscator.loadStatusPlaybackViewClass(loader);
            unknownStatusPlaybackMethod = Unobfuscator.loadUnknownStatusPlaybackMethod(loader);
            dateViewId = application.getResources().getIdentifier("date", "id", application.getPackageName());
            XposedBridge.log("[" + TAG + "] AntiRevoke metadata ready");
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize AntiRevoke metadata", e);
        }
    }

    private void hookRevokeMessages() {
        Method messageMethod = Unobfuscator.loadAntiRevokeMessageMethod(loader);
        if (messageMethod == null) {
            throw new IllegalStateException("Revoke message hook not found");
        }

        XposedBridge.hookMethod(messageMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                MessageInfo info = findMessageInfo(param.args);
                if (info == null || info.messageId == null || info.remoteJid == null) return;

                boolean shouldBlock = info.remoteJid.isGroup ? info.deviceJid != null : !info.isFromMe;
                if (!shouldBlock) return;

                persistRevocation(info);
                param.setResult(true);
            }
        });
    }

    private void hookRevokeStatuses() {
        Method statusMethod = Unobfuscator.loadAntiRevokeFStatusMethod(loader);
        XposedBridge.hookMethod(statusMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                StatusInfo info = findStatusInfo(param.args);
                if (info == null || info.isFromMe || info.messageId == null || info.messageId.isEmpty()) return;

                persistRevocation(info.toMessageInfo());
                setSuccessfulResult(param, statusMethod.getReturnType());
            }
        });
    }

    private void hookConversationRows() {
        XposedHelpers.findAndHookMethod(
                ListView.class,
                "setAdapter",
                ListAdapter.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = currentActivity;
                        if (activity == null || !"Conversation".equals(activity.getClass().getSimpleName())) return;

                        ListView listView = (ListView) param.thisObject;
                        if (listView.getId() != android.R.id.list) return;

                        ListAdapter adapter = (ListAdapter) param.args[0];
                        if (adapter instanceof HeaderViewListAdapter) {
                            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                        }
                        if (!(adapter instanceof BaseAdapter)) return;

                        final ListAdapter boundAdapter = adapter;
                        currentConversationAdapter = (BaseAdapter) boundAdapter;
                        Method getView = boundAdapter.getClass().getDeclaredMethod(
                                "getView",
                                int.class,
                                View.class,
                                ViewGroup.class
                        );
                        XposedBridge.hookMethod(getView, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam itemParam) {
                                if (itemParam.thisObject != boundAdapter) return;
                                if (!(itemParam.getResult() instanceof ViewGroup)) return;

                                int position = (Integer) itemParam.args[0];
                                Object item = boundAdapter.getItem(position);
                                MessageInfo info = buildMessageInfo(item);
                                if (info == null) info = buildMessageInfoFromNestedFields(item);
                                if (info == null) return;

                                ViewGroup row = (ViewGroup) itemParam.getResult();
                                TextView dateTextView = row.findViewById(dateViewId);
                                bindIndicator(info, dateTextView);
                            }
                        });
                    }
                }
        );
    }

    private void hookStatusPlaybackRows() {
        XposedBridge.hookMethod(unknownStatusPlaybackMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object holder = ReflectionUtils.findArg(param.args, param.method.getDeclaringClass());
                if (holder == null) return;

                Field statusField = ReflectionUtils.findFieldIfExists(
                        param.method.getDeclaringClass(),
                        field -> statusPlaybackViewClass.isAssignableFrom(field.getType())
                );
                if (statusField == null) return;

                Object statusView = ReflectionUtils.getObjectField(statusField, holder);
                if (statusView == null) return;

                MessageInfo info = findMessageInfo(param.args);
                if (info == null) return;

                List<Field> textViewFields = ReflectionUtils.getFieldsByType(statusPlaybackViewClass, TextView.class);
                for (Field field : textViewFields) {
                    TextView textView = (TextView) ReflectionUtils.getObjectField(field, statusView);
                    if (textView != null && textView.getId() == dateViewId) {
                        bindIndicator(info, textView);
                        break;
                    }
                }
            }
        });
    }

    private void hookCurrentActivityTracker() {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                currentActivity = (Activity) param.thisObject;
            }
        });
    }

    private void bindIndicator(MessageInfo info, TextView dateTextView) {
        if (dateTextView == null || info.remoteJid == null || info.messageId == null) return;

        String jidKey = info.remoteJid.storageKey();
        Set<String> revokedIds = getRevokedIds(jidKey);
        String originalText = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waad_original_text");

        if (revokedIds.contains(info.messageId)) {
            if (originalText == null) {
                originalText = String.valueOf(dateTextView.getText());
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waad_original_text", originalText);
            }

            dateTextView.setText(originalText);
            dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, application.getDrawable(R.drawable.deleted), null);
            dateTextView.setCompoundDrawablePadding(dpToPx(4));

            TextPaint paint = dateTextView.getPaint();
            paint.setUnderlineText(true);

            long timestamp = RevokedMessageStore.getInstance(application).getTimestamp(jidKey, info.messageId);
            if (timestamp > 0L) {
                String removedAt = DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT,
                        Locale.getDefault()
                ).format(new Date(timestamp));
                dateTextView.setOnClickListener(v -> Toast.makeText(
                        application,
                        application.getString(R.string.message_removed_on, removedAt),
                        Toast.LENGTH_LONG
                ).show());
            }
        } else {
            dateTextView.setCompoundDrawables(null, null, null, null);
            if (originalText != null) {
                dateTextView.setText(originalText);
            }
            dateTextView.getPaint().setUnderlineText(false);
            dateTextView.setOnClickListener(null);
        }
    }

    private void persistRevocation(MessageInfo info) {
        String jidKey = info.remoteJid.storageKey();
        if (jidKey == null || jidKey.isEmpty()) return;

        Set<String> revokedIds = getRevokedIds(jidKey);
        if (revokedIds.contains(info.messageId)) return;

        revokedIds.add(info.messageId);
        RevokedMessageStore.getInstance(application).put(jidKey, info.messageId, System.currentTimeMillis());
        notifyConversationChanged();
    }

    private Set<String> getRevokedIds(String jidKey) {
        return REVOKED_IDS.computeIfAbsent(jidKey, key ->
                Collections.synchronizedSet(new HashSet<>(
                        RevokedMessageStore.getInstance(application).getMessageIdsByJid(key)
                ))
        );
    }

    private void notifyConversationChanged() {
        BaseAdapter adapter = currentConversationAdapter;
        Activity activity = currentActivity;
        if (adapter == null || activity == null) return;

        MAIN_HANDLER.post(() -> {
            try {
                adapter.notifyDataSetChanged();
            } catch (Throwable ignored) {
            }
        });
    }

    private MessageInfo findMessageInfo(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            MessageInfo info = buildMessageInfo(arg);
            if (info != null) return info;
            info = buildMessageInfoFromNestedFields(arg);
            if (info != null) return info;
        }
        return null;
    }

    private MessageInfo buildMessageInfoFromNestedFields(Object target) {
        if (target == null) return null;
        Field nestedField = ReflectionUtils.findFieldIfExists(
                target.getClass(),
                field -> fMessageClass.isAssignableFrom(field.getType())
        );
        if (nestedField == null) return null;
        return buildMessageInfo(ReflectionUtils.getObjectField(nestedField, target));
    }

    private MessageInfo buildMessageInfo(Object fMessageObject) {
        if (fMessageObject == null || !fMessageClass.isInstance(fMessageObject)) return null;
        try {
            Object keyObject = keyField.get(fMessageObject);
            if (keyObject == null) return null;

            MessageInfo info = new MessageInfo();
            info.fMessage = fMessageObject;
            info.keyObject = keyObject;
            info.messageId = (String) XposedHelpers.getObjectField(fMessageObject, "A01");
            info.isFromMe = XposedHelpers.getBooleanField(keyObject, "A02");
            info.remoteJid = new JidInfo(XposedHelpers.getObjectField(keyObject, "A00"));
            info.deviceJid = deviceJidField == null ? null : deviceJidField.get(fMessageObject);
            return info;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] Failed to build message info: " + e.getMessage());
            return null;
        }
    }

    private StatusInfo findStatusInfo(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            StatusInfo info = buildStatusInfo(arg);
            if (info != null) return info;
        }
        return null;
    }

    private StatusInfo buildStatusInfo(Object candidate) {
        if (candidate == null) return null;
        try {
            Field remoteField = candidate.getClass().getDeclaredField("A00");
            Field messageIdField = candidate.getClass().getDeclaredField("A02");
            Field fromMeField = candidate.getClass().getDeclaredField("A03");
            remoteField.setAccessible(true);
            messageIdField.setAccessible(true);
            fromMeField.setAccessible(true);

            Object remoteJid = remoteField.get(candidate);
            Object messageId = messageIdField.get(candidate);
            if (!(messageId instanceof String)) return null;

            StatusInfo info = new StatusInfo();
            info.remoteJid = new JidInfo(remoteJid);
            info.messageId = (String) messageId;
            info.isFromMe = fromMeField.getBoolean(candidate);
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setSuccessfulResult(XC_MethodHook.MethodHookParam param, Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            param.setResult(true);
        } else if (returnType == int.class || returnType == Integer.class) {
            param.setResult(0);
        } else if (!Modifier.isAbstract(param.method.getModifiers())) {
            param.setResult(null);
        }
    }

    private int dpToPx(int dp) {
        return (int) (application.getResources().getDisplayMetrics().density * dp);
    }

    private static final class MessageInfo {
        Object fMessage;
        Object keyObject;
        String messageId;
        boolean isFromMe;
        JidInfo remoteJid;
        Object deviceJid;
    }

    private static final class StatusInfo {
        String messageId;
        boolean isFromMe;
        JidInfo remoteJid;

        MessageInfo toMessageInfo() {
            MessageInfo info = new MessageInfo();
            info.messageId = messageId;
            info.isFromMe = isFromMe;
            info.remoteJid = remoteJid;
            return info;
        }
    }

    private static final class JidInfo {
        final String raw;
        final String phoneNumber;
        final boolean isGroup;

        JidInfo(Object jidObject) {
            String rawValue = null;
            try {
                rawValue = (String) XposedHelpers.callMethod(jidObject, "getRawString");
            } catch (Throwable ignored) {
            }
            raw = sanitize(rawValue);
            phoneNumber = extractPhoneNumber(raw);
            isGroup = raw != null && raw.endsWith("@g.us");
        }

        String storageKey() {
            return phoneNumber != null ? phoneNumber : raw;
        }

        private static String sanitize(String value) {
            if (value == null) return null;
            return value.replaceFirst("\\.[\\d:]+@", "@");
        }

        private static String extractPhoneNumber(String value) {
            if (value == null) return null;
            int atIndex = value.indexOf('@');
            if (atIndex <= 0) return value;
            int dotIndex = value.indexOf('.');
            if (dotIndex > 0 && dotIndex < atIndex) {
                return value.substring(0, dotIndex);
            }
            return value.substring(0, atIndex);
        }
    }
}
