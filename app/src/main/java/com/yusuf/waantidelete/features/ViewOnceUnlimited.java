package com.yusuf.waantidelete.features;

import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class ViewOnceUnlimited {

    private final ClassLoader loader;

    public ViewOnceUnlimited(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        Method[] methods = Unobfuscator.loadViewOnceMethods(loader);
        XposedBridge.log("[WaAntiDelete] ViewOnce: found " + methods.length + " methods to hook");

        int hooked = 0;
        for (Method method : methods) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                int value = (Integer) param.args[0];
                                if (value == 1) {
                                    param.args[0] = 0;
                                    XposedBridge.log("[WaAntiDelete] ViewOnce: blocked view count increment");
                                }
                            }
                        } catch (Throwable e) {
                            XposedBridge.log("[WaAntiDelete] ViewOnce hook error: " + e.getMessage());
                        }
                    }
                });
                hooked++;
            } catch (Throwable e) {
                XposedBridge.log("[WaAntiDelete] ViewOnce: failed to hook " + method.getName() + ": " + e.getMessage());
            }
        }

        XposedBridge.log("[WaAntiDelete] ViewOnce hook SUCCESS: " + hooked + "/" + methods.length + " methods hooked");

        if (hooked == 0) {
            throw new RuntimeException("No ViewOnce methods were successfully hooked");
        }
    }
}
