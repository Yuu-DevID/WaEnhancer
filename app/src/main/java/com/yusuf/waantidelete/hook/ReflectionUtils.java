package com.yusuf.waantidelete.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ReflectionUtils {

    public static Method findMethod(Class<?> clazz, Predicate<Method> predicate) {
        Class<?> current = clazz;
        do {
            var results = Arrays.stream(current.getDeclaredMethods()).filter(predicate).findFirst();
            if (results.isPresent()) return results.get();
        } while ((current = current.getSuperclass()) != null);
        throw new RuntimeException("Method not found in " + clazz.getName());
    }

    public static Field findField(Class<?> clazz, Predicate<Field> predicate) {
        Class<?> current = clazz;
        do {
            var results = Arrays.stream(current.getDeclaredFields()).filter(predicate).findFirst();
            if (results.isPresent()) return results.get();
        } while ((current = current.getSuperclass()) != null);
        throw new RuntimeException("Field not found in " + clazz.getName());
    }

    public static Field findFieldIfExists(Class<?> clazz, Predicate<Field> predicate) {
        Class<?> current = clazz;
        do {
            var results = Arrays.stream(current.getDeclaredFields()).filter(predicate).findFirst();
            if (results.isPresent()) return results.get();
        } while ((current = current.getSuperclass()) != null);
        return null;
    }

    public static List<Field> getFieldsByType(Class<?> cls, Class<?> type) {
        return Arrays.stream(cls.getDeclaredFields()).filter(f -> type == f.getType()).collect(Collectors.toList());
    }

    public static Object getObjectField(Field field, Object obj) {
        try {
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            XposedBridge.log(e);
            return null;
        }
    }
}
