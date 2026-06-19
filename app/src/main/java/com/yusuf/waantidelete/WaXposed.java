package com.yusuf.waantidelete;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.XModuleResources;
import android.widget.Toast;

import com.yusuf.waantidelete.features.AntiRevoke;
import com.yusuf.waantidelete.features.HideSeen;
import com.yusuf.waantidelete.features.MediaQuality;
import com.yusuf.waantidelete.features.ViewOnceUnlimited;
import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WaXposed implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {

    private static final String PACKAGE_WPP = "com.whatsapp";
    private static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";
    private static final String PREFS_NAME = "waantidelete_status";

    private static SharedPreferences statusPrefs;
    private String modulePath;

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
        if (!lpparam.isFirstApplication) return;

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
                                XposedBridge.log("[WaAntiDelete] Failed to init DexKit from: " + sourceDir);
                                return;
                            }
                            writeStatus("dexkit", "ok");
                            XposedBridge.log("[WaAntiDelete] DexKit initialized from: " + sourceDir);

                            int hookedCount = 0;
                            int errorCount = 0;

                            try {
                                hookedCount += new AntiRevoke(loader, app).hook();
                                writeStatus("anti_revoke", "ok");
                                XposedBridge.log("[WaAntiDelete] AntiRevoke hooked");
                            } catch (Throwable e) {
                                errorCount++;
                                writeStatus("anti_revoke", "error: " + e.getMessage());
                                XposedBridge.log("[WaAntiDelete] AntiRevoke error: " + e.getMessage());
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

                            try {
                                new HideSeen(loader).hook();
                                hookedCount++;
                                writeStatus("hide_seen", "ok");
                                XposedBridge.log("[WaAntiDelete] HideSeen hooked");
                            } catch (Throwable e) {
                                errorCount++;
                                writeStatus("hide_seen", "error: " + e.getMessage());
                                XposedBridge.log("[WaAntiDelete] HideSeen error: " + e.getMessage());
                                XposedBridge.log(e);
                            }

                            try {
                                new MediaQuality(loader).hook();
                                hookedCount++;
                                writeStatus("media_quality", "ok");
                                XposedBridge.log("[WaAntiDelete] MediaQuality hooked");
                            } catch (Throwable e) {
                                errorCount++;
                                writeStatus("media_quality", "error: " + e.getMessage());
                                XposedBridge.log("[WaAntiDelete] MediaQuality error: " + e.getMessage());
                                XposedBridge.log(e);
                            }

                            try {
                                Toast.makeText(
                                        app,
                                        app.getString(R.string.startup_toast),
                                        Toast.LENGTH_LONG
                                ).show();
                            } catch (Throwable toastError) {
                                Toast.makeText(
                                        app,
                                        "WaAntiDelete aktif. Anti revoke pesan dan status siap.",
                                        Toast.LENGTH_LONG
                                ).show();
                            }

                            if (errorCount > 0) {
                                writeStatus("connection", "partial");
                            } else {
                                writeStatus("connection", "ok");
                            }
                            writeStatus("hooked_count", String.valueOf(hookedCount));
                            writeStatus("error_count", String.valueOf(errorCount));

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

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        if (!PACKAGE_WPP.equals(resparam.packageName) && !PACKAGE_BUSINESS.equals(resparam.packageName)) return;
        XModuleResources modRes = XModuleResources.createInstance(modulePath, resparam.res);
        injectResources(R.string.class, modRes, resparam);
        injectResources(R.drawable.class, modRes, resparam);
    }

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    private void injectResources(Class<?> resourceClass, XModuleResources modRes, XC_InitPackageResources.InitPackageResourcesParam resparam) {
        int injected = 0;
        for (Field field : resourceClass.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    int resId = field.getInt(null);
                    if (resId > 0x7f000000) {
                        field.set(null, resparam.res.addResource(modRes, resId));
                        injected++;
                    }
                } else if (field.getType() == int[].class) {
                    int[] values = (int[]) field.get(null);
                    if (values == null) continue;
                    for (int i = 0; i < values.length; i++) {
                        if (values[i] > 0x7f000000) {
                            values[i] = resparam.res.addResource(modRes, values[i]);
                            injected++;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log("[WaAntiDelete] Injected " + injected + " resources for " + resourceClass.getSimpleName());
    }
}
