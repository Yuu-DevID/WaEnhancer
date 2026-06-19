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

            // Resolve the "date" resource ID from WhatsApp's resource table
            dateViewId = application.getResources().getIdentifier("date", "id", application.getPackageName());
            if (dateViewId == 0) {
                // Fallback: try using AndroidAppHelper
                try {
                    android.app.Application hostApp = android.app.AndroidAppHelper.currentApplication();
                    if (hostApp != null) {
                        dateViewId = hostApp.getResources().getIdentifier("date", "id", hostApp.getPackageName());
                    }
                } catch (Throwable ignored) {}
            }
            if (dateViewId == 0) {
                // Final fallback: use android.R.id.list as a hint to find the date view later
                XposedBridge.log("[" + TAG + "] WARNING: Could not resolve R.id.date, will use view hierarchy fallback");
            }

            XposedBridge.log("[" + TAG + "] AntiRevoke metadata ready, dateViewId=" + dateViewId);
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
                        if (activity == null) {
                            XposedBridge.log("[" + TAG + "] setAdapter: currentActivity is null, skipping");
                            return;
                        }

                        String activityName = activity.getClass().getSimpleName();
                        if (!"Conversation".equals(activityName)) {
                            return; // Not a conversation screen, silently skip
                        }

                        ListView listView = (ListView) param.thisObject;
                        if (listView.getId() != android.R.id.list) {
                            XposedBridge.log("[" + TAG + "] setAdapter: listView id=" + listView.getId() + " expected=" + android.R.id.list);
                            return;
                        }

                        ListAdapter adapter = (ListAdapter) param.args[0];
                        if (adapter instanceof HeaderViewListAdapter) {
                            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                        }
                        if (!(adapter instanceof BaseAdapter)) {
                            XposedBridge.log("[" + TAG + "] setAdapter: adapter is not BaseAdapter: " + adapter.getClass().getName());
                            return;
                        }

                        final ListAdapter boundAdapter = adapter;
                        currentConversationAdapter = (BaseAdapter) boundAdapter;

                        XposedBridge.log("[" + TAG + "] Hooking getView on adapter: " + boundAdapter.getClass().getName());

                        try {
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

                                    try {
                                        int position = (Integer) itemParam.args[0];
                                        Object item = boundAdapter.getItem(position);
                                        if (item == null) return;

                                        MessageInfo info = buildMessageInfo(item);
                                        if (info == null) info = buildMessageInfoFromNestedFields(item);
                                        if (info == null) {
                                            // Last resort: search all fields recursively for FMessage
                                            info = buildMessageInfoDeepSearch(item);
                                        }
                                        if (info == null) {
                                            XposedBridge.log("[" + TAG + "] getView pos=" + position + ": could not build message info from item type=" + item.getClass().getName());
                                            return;
                                        }

                                        ViewGroup row = (ViewGroup) itemParam.getResult();
                                        TextView dateTextView = findDateTextView(row);
                                        if (dateTextView == null) {
                                            XposedBridge.log("[" + TAG + "] getView pos=" + position + ": dateTextView is null, dateViewId=" + dateViewId);
                                            return;
                                        }

                                        bindIndicator(info, dateTextView);
                                    } catch (Throwable e) {
                                        XposedBridge.log("[" + TAG + "] getView error: " + e.getMessage());
                                    }
                                }
                            });
                        } catch (NoSuchMethodException e) {
                            XposedBridge.log("[" + TAG + "] Failed to get getView method from " + boundAdapter.getClass().getName() + ": " + e.getMessage());
                        }
                    }
                }
        );
    }

    /**
     * Find the date TextView in a conversation row.
     * First tries by resource ID, then falls back to searching the view hierarchy.
     */
    private TextView findDateTextView(ViewGroup row) {
        // Primary: find by resource ID
        if (dateViewId != 0) {
            TextView tv = row.findViewById(dateViewId);
            if (tv != null) return tv;
        }

        // Fallback: search the view hierarchy for a TextView that could be the date
        return findDateTextViewRecursive(row);
    }

    /**
     * Recursively search for a small TextView that looks like a timestamp.
     * WhatsApp's date TextView is typically small text at the bottom of the message bubble.
     */
    private TextView findDateTextViewRecursive(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                // Match by ID name if possible, or by characteristics
                int id = tv.getId();
                if (id != 0 && id == dateViewId) return tv;
                // Heuristic: date text is typically short, small text size
                CharSequence text = tv.getText();
                if (text != null && text.length() <= 20 && tv.getTextSize() <= 48) {
                    // Could be a timestamp - check if it looks like a time pattern
                    String str = text.toString().trim();
                    if (str.matches("\\d{1,2}:\\d{2}.*") || str.matches(".*\\d{1,2}:\\d{2}") ||
                        str.matches("\\d{1,2}/\\d{1,2}.*") || str.matches(".*\\d{1,2}/\\d{1,2}.*")) {
                        return tv;
                    }
                }
            } else if (child instanceof ViewGroup) {
                TextView result = findDateTextViewRecursive((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
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
                if (info == null) {
                    XposedBridge.log("[" + TAG + "] StatusPlayback: could not build message info");
                    return;
                }

                // Find the date TextView in the status playback view
                TextView dateTextView = findStatusDateTextView(statusView);
                if (dateTextView != null) {
                    bindIndicator(info, dateTextView);
                } else {
                    XposedBridge.log("[" + TAG + "] StatusPlayback: dateTextView not found, dateViewId=" + dateViewId);
                }
            }
        });
    }

    /**
     * Find the date TextView in a status playback view.
     */
    private TextView findStatusDateTextView(Object statusView) {
        // Primary: search by field type and ID
        List<Field> textViewFields = ReflectionUtils.getFieldsByType(statusPlaybackViewClass, TextView.class);
        for (Field field : textViewFields) {
            TextView textView = (TextView) ReflectionUtils.getObjectField(field, statusView);
            if (textView != null) {
                if (dateViewId != 0 && textView.getId() == dateViewId) {
                    return textView;
                }
            }
        }

        // Fallback: try to find any TextView that looks like a date
        for (Field field : textViewFields) {
            TextView textView = (TextView) ReflectionUtils.getObjectField(field, statusView);
            if (textView != null && textView.getId() != 0) {
                // Check if this could be the date view by examining its ID
                String idName = null;
                try {
                    idName = textView.getResources().getResourceEntryName(textView.getId());
                } catch (Throwable ignored) {}
                if ("date".equals(idName)) {
                    return textView;
                }
            }
        }

        // Last resort: use the first small TextView found
        for (Field field : textViewFields) {
            TextView textView = (TextView) ReflectionUtils.getObjectField(field, statusView);
            if (textView != null) {
                return textView;
            }
        }

        return null;
    }

    private void hookCurrentActivityTracker() {
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                currentActivity = (Activity) param.thisObject;
            }
        });
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

            // Set the red cross drawable to the RIGHT of timestamp (for messages)
            // and to the LEFT of timestamp (for statuses - determined by caller context)
            android.graphics.drawable.Drawable deletedDrawable = application.getDrawable(R.drawable.deleted);
            if (deletedDrawable != null) {
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, deletedDrawable, null);
                dateTextView.setCompoundDrawablePadding(dpToPx(4));
            } else {
                XposedBridge.log("[" + TAG + "] R.drawable.deleted is null!");
            }

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

            XposedBridge.log("[" + TAG + "] Bound deleted icon for msg " + info.messageId + " in " + jidKey);
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
        XposedBridge.log("[" + TAG + "] Persisted revocation: msg=" + info.messageId + " jid=" + jidKey);
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
        if (adapter == null || activity == null) {
            XposedBridge.log("[" + TAG + "] notifyConversationChanged: adapter=" + (adapter != null) + " activity=" + (activity != null));
            return;
        }
        MAIN_HANDLER.post(() -> {
            try {
                adapter.notifyDataSetChanged();
                XposedBridge.log("[" + TAG + "] notifyDataSetChanged called");
            } catch (Throwable e) {
                XposedBridge.log("[" + TAG + "] notifyDataSetChanged failed: " + e.getMessage());
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

    /**
     * Deep search: look through all fields recursively for an FMessage instance.
     * This handles cases where the adapter item is a complex wrapper.
     */
    private MessageInfo buildMessageInfoDeepSearch(Object target) {
        if (target == null) return null;
        return buildMessageInfoDeepSearchHelper(target, 0);
    }

    private MessageInfo buildMessageInfoDeepSearchHelper(Object target, int depth) {
        if (target == null || depth > 3) return null; // Limit recursion depth
        if (fMessageClass.isInstance(target)) {
            return buildMessageInfo(target);
        }
        // Search fields of the target
        for (Field field : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null && fMessageClass.isInstance(value)) {
                    return buildMessageInfo(value);
                }
                // Recurse into non-null objects
                if (value != null && !value.getClass().getName().startsWith("java.") &&
                    !value.getClass().getName().startsWith("android.") &&
                    !value.getClass().isPrimitive()) {
                    MessageInfo result = buildMessageInfoDeepSearchHelper(value, depth + 1);
                    if (result != null) return result;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private MessageInfo buildMessageInfo(Object fMessageObject) {
        if (fMessageObject == null || !fMessageClass.isInstance(fMessageObject)) return null;
        try {
            Object keyObject = keyField.get(fMessageObject);
            if (keyObject == null) {
                XposedBridge.log("[" + TAG + "] buildMessageInfo: keyObject is null");
                return null;
            }

            MessageInfo info = new MessageInfo();
            info.fMessage = fMessageObject;
            info.keyObject = keyObject;

            // Try to get messageId from FMessage first, then from Key
            try {
                info.messageId = (String) XposedHelpers.getObjectField(fMessageObject, "A01");
            } catch (Throwable e1) {
                try {
                    info.messageId = (String) XposedHelpers.getObjectField(keyObject, "A01");
                } catch (Throwable e2) {
                    XposedBridge.log("[" + TAG + "] buildMessageInfo: could not get messageId from A01");
                    return null;
                }
            }

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
