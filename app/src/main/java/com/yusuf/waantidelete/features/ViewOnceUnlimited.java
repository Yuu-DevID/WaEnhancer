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
        try {
            Method[] methods = Unobfuscator.loadViewOnceMethods(loader);

            for (Method method : methods) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                int value = (Integer) param.args[0];
                                if (value == 1) {
                                    param.args[0] = 0;
                                    XposedBridge.log("[WaAntiDelete] ViewOnce unlimited: blocked view count");
                                }
                            }
                        } catch (Throwable e) {
                            XposedBridge.log("[WaAntiDelete] ViewOnce hook error: " + e.getMessage());
                        }
                    }
                });
            }

            XposedBridge.log("[WaAntiDelete] ViewOnceUnlimited hooked (" + methods.length + " methods)");
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] ViewOnceUnlimited hook failed: " + e.getMessage());
            XposedBridge.log(e);
        }
    }
}
