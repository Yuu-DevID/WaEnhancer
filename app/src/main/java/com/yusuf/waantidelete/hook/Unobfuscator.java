package com.yusuf.waantidelete.hook;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class Unobfuscator {

    private static DexKitBridge bridge;

    static {
        System.loadLibrary("dexkit");
    }

    public static boolean init(String path) {
        try {
            bridge = DexKitBridge.create(path);
            return true;
        } catch (Exception e) {
            XposedBridge.log("[WaAntiDelete] DexKit init failed: " + e.getMessage());
            return false;
        }
    }

    public static Class<?> loadFMessageClass(ClassLoader loader) {
        var result = bridge.findClass(query ->
            query.addUsingString("FMessage/getSenderUserJid/key.id")
        );
        if (!result.isEmpty()) return result.get(0).getInstance(loader);
        throw new RuntimeException("FMessage class not found");
    }

    public static Method loadAntiRevokeMessageMethod(ClassLoader loader) {
        for (String s : Arrays.asList("msgstore/edit/revoke", "msgstore/revoking/")) {
            Method m = findMethodByString(loader, s);
            if (m != null) return m;
        }
        throw new RuntimeException("AntiRevoke message method not found");
    }

    public static Method loadAntiRevokeFStatusMethod(ClassLoader loader) {
        Class<?> fStatusKeyClass = loadFStatusKeyClass(loader);
        var result = bridge.findMethod(query ->
            query.addUsingString("RevokeStatusManager/failed")
        );
        if (result.isEmpty()) throw new RuntimeException("AntiRevoke FStatus method not found");

        for (MethodData m : result) {
            try {
                Method method = m.getMethodInstance(loader);
                if (method.getParameterCount() > 0 &&
                        fStatusKeyClass.isAssignableFrom(method.getParameterTypes()[0])) {
                    return method;
                }
            } catch (Throwable ignored) {}
        }
        throw new RuntimeException("AntiRevoke FStatus method not found");
    }

    public static Class<?> loadFStatusKeyClass(ClassLoader loader) {
        var result = bridge.findClass(query -> {
            query.addUsingString("Key(id=");
            query.addUsingString("senderJid");
        });
        if (!result.isEmpty()) return result.get(0).getInstance(loader);
        throw new RuntimeException("FStatusKey class not found");
    }

    public static Field loadMessageKeyField(ClassLoader loader) {
        Class<?> fMessageClass = loadFMessageClass(loader);
        var result = bridge.findClass(query ->
            query.addUsingString("Key")
        );
        if (result.isEmpty()) throw new RuntimeException("MessageKey class not found");

        for (var classData : result) {
            Class<?> keyClass = classData.getInstance(loader);
            for (Field f : fMessageClass.getDeclaredFields()) {
                if (keyClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new RuntimeException("MessageKey field not found");
    }

    public static Method[] loadViewOnceMethods(ClassLoader loader) {
        var result = bridge.findMethod(query ->
            query.addUsingString("INSERT_VIEW_ONCE_SQL")
        );
        if (result.isEmpty()) throw new RuntimeException("ViewOnce SQL method not found");

        MethodData methodData = result.get(0);
        var invokes = methodData.getInvokes();
        List<Method> methods = new ArrayList<>();

        for (MethodData m : invokes) {
            try {
                Method method = m.getMethodInstance(loader);
                if (method.getDeclaringClass().isInterface() &&
                        method.getDeclaringClass().getDeclaredMethods().length == 2) {
                    Class<?> iface = method.getDeclaringClass();
                    var implementingClasses = bridge.findClass(query ->
                        query.addInterface(iface.getName())
                    );
                    for (var c : implementingClasses) {
                        Class<?> clazz = c.getInstance(loader);
                        for (Method m2 : clazz.getDeclaredMethods()) {
                            if (m2.getParameterCount() == 1 &&
                                    m2.getParameterTypes()[0] == int.class &&
                                    m2.getReturnType() == void.class) {
                                methods.add(m2);
                            }
                        }
                    }
                    if (!methods.isEmpty()) return methods.toArray(new Method[0]);
                }
            } catch (Throwable ignored) {}
        }
        throw new RuntimeException("ViewOnce methods not found");
    }

    private static Method findMethodByString(ClassLoader loader, String searchString) {
        var result = bridge.findMethod(query ->
            query.addUsingString(searchString)
        );
        if (result.isEmpty()) return null;
        for (MethodData m : result) {
            try {
                return m.getMethodInstance(loader);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
