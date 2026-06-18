package com.yusuf.waantidelete;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

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
    private static final String PREFS_NAME = "waantidelete_status";
    private static final String PREFS_FILE = "/data/data/com.yusuf.waantidelete/shared_prefs/" + PREFS_NAME + ".xml";

    private static SharedPreferences statusPrefs;

    private static void writeStatus(String key, String value) {
        try {
            if (statusPrefs != null) {
                statusPrefs.edit().putString(key, value).apply();
            }
        } catch (Throwable e) {
            XposedBridge.log("[WaAntiDelete] Status write failed: " + e.getMessage());
        }
    }

    private static void writeError(String error) {
        writeStatus("last_error", error);
        writeStatus("has_error", "true");
    }

    private static void clearErrors() {
        writeStatus("last_error", "none");
        writeStatus("has_error", "false");
    }

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
                            Application app = (Application) param.args[0];
                            statusPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

                            writeStatus("connection", "connecting");
                            writeStatus("whatsapp_package", packageName);

                            PackageInfo pkgInfo = app.getPackageManager().getPackageInfo(packageName, 0);
                            writeStatus("whatsapp_version", pkgInfo.versionName);
                            XposedBridge.log("[WaAntiDelete] WhatsApp version: " + pkgInfo.versionName);

                            clearErrors();

                            if (!Unobfuscator.init(sourceDir)) {
                                writeError("DexKit init failed");
                                writeStatus("connection", "error");
                                XposedBridge.log("[WaAntiDelete] Failed to init DexKit");
                                return;
                            }
                            writeStatus("dexkit", "ok");
                            XposedBridge.log("[WaAntiDelete] DexKit initialized");

                            int hookedCount = 0;
                            int errorCount = 0;

                            try {
                                new AntiRevoke(loader).hook();
                                hookedCount++;
                                writeStatus("anti_revoke", "ok");
                                XposedBridge.log("[WaAntiDelete] AntiRevoke hooked");
                            } catch (Throwable e) {
                                errorCount++;
                                writeStatus("anti_revoke", "error: " + e.getMessage());
                                XposedBridge.log("[WaAntiDelete] AntiRevoke failed: " + e.getMessage());
                                XposedBridge.log(e);
                            }

                            try {
                                new ViewOnceUnlimited(loader).hook();
                                hookedCount++;
                                writeStatus("view_once", "ok");
                                XposedBridge.log("[WaAntiDelete] ViewOnce hooked");
                            } catch (Throwable e) {
                                errorCount++;
                                writeStatus("view_once", "error: " + e.getMessage());
                                XposedBridge.log("[WaAntiDelete] ViewOnce failed: " + e.getMessage());
                                XposedBridge.log(e);
                            }

                            if (errorCount > 0) {
                                writeStatus("connection", "partial");
                                writeStatus("hooked_count", String.valueOf(hookedCount));
                                writeStatus("error_count", String.valueOf(errorCount));
                            } else {
                                writeStatus("connection", "ok");
                                writeStatus("hooked_count", String.valueOf(hookedCount));
                                writeStatus("error_count", "0");
                            }

                            XposedBridge.log("[WaAntiDelete] Hooked: " + hookedCount + ", Errors: " + errorCount);

                        } catch (Throwable e) {
                            writeError("Critical: " + e.getMessage());
                            writeStatus("connection", "error");
                            XposedBridge.log("[WaAntiDelete] Critical error: " + e.getMessage());
                            XposedBridge.log(e);
                        }
                    }
                });
    }
}
