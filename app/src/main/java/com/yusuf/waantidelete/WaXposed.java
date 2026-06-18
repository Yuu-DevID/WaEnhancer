package com.yusuf.waantidelete;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;

import com.yusuf.waantidelete.features.AntiRevoke;
import com.yusuf.waantidelete.features.ViewOnceUnlimited;
import com.yusuf.waantidelete.hook.Unobfuscator;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WaXposed implements IXposedHookLoadPackage {

    private static final String PACKAGE_WPP = "com.whatsapp";
    private static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;
        if (!packageName.equals(PACKAGE_WPP) && !packageName.equals(PACKAGE_BUSINESS)) return;

        final ClassLoader loader = lpparam.classLoader;
        final String sourceDir = lpparam.appInfo.sourceDir;

        XposedHelpers.findAndHookMethod(
                Instrumentation.class, "callApplicationOnCreate", Application.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Unobfuscator.init(sourceDir)) {
                                XposedBridge.log("[WaAntiDelete] Failed to init DexKit");
                                return;
                            }

                            XposedBridge.log("[WaAntiDelete] Loaded on " + packageName);

                            new AntiRevoke(loader).hook();
                            new ViewOnceUnlimited(loader).hook();

                            XposedBridge.log("[WaAntiDelete] All features hooked successfully");

                        } catch (Throwable e) {
                            XposedBridge.log("[WaAntiDelete] Error: " + e.getMessage());
                            XposedBridge.log(e);
                        }
                    }
                });
    }
}
