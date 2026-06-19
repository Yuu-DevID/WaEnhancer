package com.yusuf.waantidelete.features;

import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.RecordingCanvas;

import com.yusuf.waantidelete.hook.ReflectionUtils;
import com.yusuf.waantidelete.hook.Unobfuscator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HD Media Upload feature - ported from WaEnhancer.
 * Overrides video/image quality parameters to send media in higher quality.
 */
public class MediaQuality {

    private static final String TAG = "WaAntiDelete";
    private final ClassLoader loader;

    public MediaQuality(ClassLoader loader) {
        this.loader = loader;
    }

    public void hook() {
        int hookedCount = 0;

        // 1. Hook ProcessVideoQuality constructor - boost video quality
        try {
            hookProcessVideoQuality();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: ProcessVideoQuality hook failed: " + e.getMessage());
        }

        // 2. Hook MediaDataVideoConfiguration - boost video config
        try {
            hookMediaDataVideoConfiguration();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: MediaDataVideoConfiguration hook failed: " + e.getMessage());
        }

        // 3. Hook video transcoder start - maintain real resolution
        try {
            hookVideoTranscoder();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: VideoTranscoder hook failed: " + e.getMessage());
        }

        // 4. Hook getCorrectedResolution - restore original resolution
        try {
            hookCorrectedResolution();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: CorrectedResolution hook failed: " + e.getMessage());
        }

        // 5. Hook ProcessImageQuality constructor - boost image quality
        try {
            hookProcessImageQuality();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: ProcessImageQuality hook failed: " + e.getMessage());
        }

        // 6. Hook media quality selection method - always enable HD
        try {
            hookMediaQualitySelection();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: MediaQualitySelection hook failed: " + e.getMessage());
        }

        // 7. Hook BottomBarConfig to support HD quality toggle
        try {
            hookBottomBarConfig();
            hookedCount++;
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: BottomBarConfig hook failed: " + e.getMessage());
        }

        // 8. Prevent crashes in Media preview (Android Q+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                XposedHelpers.findAndHookMethod(
                        RecordingCanvas.class,
                        "throwIfCannotDraw",
                        Bitmap.class,
                        XC_MethodReplacement.DO_NOTHING
                );
                hookedCount++;
            }
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: RecordingCanvas hook failed: " + e.getMessage());
        }

        XposedBridge.log("[" + TAG + "] MediaQuality: hooked " + hookedCount + " methods");
        if (hookedCount == 0) {
            throw new RuntimeException("No MediaQuality methods were successfully hooked");
        }
    }

    /**
     * Hook ProcessVideoQuality constructor to boost video encoding parameters.
     */
    private void hookProcessVideoQuality() {
        Class<?> processVideoQualityClass = Unobfuscator.loadProcessVideoQualityClass(loader);
        HashMap<String, Field> fields = Unobfuscator.getAllMapFields(processVideoQualityClass);

        XposedBridge.hookAllConstructors(processVideoQualityClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object instance = param.thisObject;

                    // Increase max bitrate significantly (multiply by 8)
                    Field bitrateField = fields.get("videoMaxBitrate");
                    if (bitrateField != null) {
                        int currentBitrate = bitrateField.getInt(instance);
                        if (currentBitrate > 0) {
                            bitrateField.setInt(instance, currentBitrate * 8);
                        } else {
                            // Default to high bitrate: 24 Mbps
                            bitrateField.setInt(instance, 24000 * 1000);
                        }
                    }

                    // Increase max edge (multiply by 10, max 3840 for 4K)
                    Field edgeField = fields.get("videoMaxEdge");
                    if (edgeField != null) {
                        int currentEdge = edgeField.getInt(instance);
                        int newEdge = Math.min(currentEdge * 10, 3840);
                        edgeField.setInt(instance, newEdge);
                    }

                    // Increase file size limit (multiply by 2, min 90 MB)
                    Field limitField = fields.get("videoLimitMb");
                    if (limitField != null) {
                        int currentLimit = limitField.getInt(instance);
                        int newLimit = Math.max(currentLimit * 2, 90);
                        limitField.setInt(instance, newLimit);
                    }

                    XposedBridge.log("[" + TAG + "] MediaQuality: ProcessVideoQuality boosted");
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] MediaQuality: ProcessVideoQuality boost error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook MediaDataVideoConfiguration to boost video configuration parameters.
     */
    private void hookMediaDataVideoConfiguration() {
        try {
            Class<?> configClass = Unobfuscator.loadMediaDataVideoConfigurationClass(loader);
            HashMap<String, Field> fields = Unobfuscator.getAllMapFields(configClass);

            XposedBridge.hookAllConstructors(configClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object instance = param.thisObject;

                        // Set force single transcoding
                        Field forceField = fields.get("forceSingleTranscoding");
                        if (forceField != null) {
                            forceField.setBoolean(instance, true);
                        }

                        // Boost bitrate
                        Field bitrateField = fields.get("bitrate");
                        if (bitrateField != null) {
                            int current = bitrateField.getInt(instance);
                            if (current > 0) {
                                bitrateField.setInt(instance, current * 8);
                            }
                        }

                        // Boost bitrate limit
                        Field bitrateLimitField = fields.get("bitrateLimit");
                        if (bitrateLimitField != null) {
                            int current = bitrateLimitField.getInt(instance);
                            if (current > 0) {
                                bitrateLimitField.setInt(instance, current * 8);
                            }
                        }

                        // Boost edge
                        Field edgeField = fields.get("edge");
                        if (edgeField != null) {
                            int current = edgeField.getInt(instance);
                            if (current > 0) {
                                edgeField.setInt(instance, Math.min(current * 10, 3840));
                            }
                        }

                        // Boost edge limit
                        Field edgeLimitField = fields.get("edgeLimit");
                        if (edgeLimitField != null) {
                            int current = edgeLimitField.getInt(instance);
                            if (current > 0) {
                                edgeLimitField.setInt(instance, Math.min(current * 10, 3840));
                            }
                        }

                        XposedBridge.log("[" + TAG + "] MediaQuality: MediaDataVideoConfiguration boosted");
                    } catch (Throwable e) {
                        XposedBridge.log("[" + TAG + "] MediaQuality: MediaDataVideoConfiguration boost error: " + e.getMessage());
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: MediaDataVideoConfiguration not found, skipping: " + e.getMessage());
        }
    }

    /**
     * Hook video transcoder start to maintain real resolution.
     */
    private void hookVideoTranscoder() {
        Method videoTranscoderStart = Unobfuscator.loadVideoTranscoderStartMethod(loader);

        XposedBridge.hookMethod(videoTranscoderStart, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    Object videoProcessor = param.args[0];

                    // Disable the downscaling boolean flag (set 3rd boolean to false)
                    Field[] booleanFields = ReflectionUtils.getFieldsByType(videoProcessor.getClass(), Boolean.TYPE).toArray(new Field[0]);
                    if (booleanFields.length > 2) {
                        booleanFields[2].setBoolean(videoProcessor, false);
                    }

                    XposedBridge.log("[" + TAG + "] MediaQuality: VideoTranscoder configured for real resolution");
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] MediaQuality: VideoTranscoder hook error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook getCorrectedResolution to restore original resolution instead of downscaling.
     */
    private void hookCorrectedResolution() {
        Method videoMethod = Unobfuscator.loadMediaQualityVideoMethod2(loader);
        HashMap<String, Field> mediaTranscodeParams = Unobfuscator.loadMediaQualityVideoFields(loader);
        HashMap<String, Field> mediaOriginalFields = Unobfuscator.loadMediaQualityOriginalVideoFields(loader);

        XposedBridge.hookMethod(videoMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object resizeVideo = param.getResult();
                    if (resizeVideo == null) return;

                    // Try to get original dimensions and restore them
                    if (!mediaOriginalFields.isEmpty()) {
                        Field widthField = mediaOriginalFields.get("widthPx");
                        Field heightField = mediaOriginalFields.get("heightPx");
                        Field rotationField = mediaOriginalFields.get("rotationAngle");

                        if (widthField != null && heightField != null && rotationField != null) {
                            int width = widthField.getInt(param.args[0]);
                            int height = heightField.getInt(param.args[0]);
                            int rotation = rotationField.getInt(param.args[0]);

                            // Handle rotation
                            boolean inverted = rotation == 90 || rotation == 270;
                            int targetWidth = inverted ? height : width;
                            int targetHeight = inverted ? width : height;

                            // Set target dimensions (keep original, don't downscale)
                            Field targetWidthField = mediaTranscodeParams.get("targetWidth");
                            Field targetHeightField = mediaTranscodeParams.get("targetHeight");

                            if (targetWidthField != null && targetWidth > 0 && targetWidth <= 3840) {
                                targetWidthField.setInt(resizeVideo, targetWidth);
                            }
                            if (targetHeightField != null && targetHeight > 0 && targetHeight <= 3840) {
                                targetHeightField.setInt(resizeVideo, targetHeight);
                            }
                        }
                    }

                    XposedBridge.log("[" + TAG + "] MediaQuality: Resolution restored to original");
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] MediaQuality: CorrectedResolution hook error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook ProcessImageQuality constructor to boost image quality.
     */
    private void hookProcessImageQuality() {
        Class<?> processImageQualityClass = Unobfuscator.loadProcessImageQualityClass(loader);
        HashMap<String, Field> fields = Unobfuscator.getAllMapFields(processImageQualityClass);

        XposedBridge.hookAllConstructors(processImageQualityClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object instance = param.thisObject;

                    // Set quality to maximum (100)
                    Field qualityField = fields.get("quality");
                    if (qualityField != null) {
                        qualityField.setInt(instance, 100);
                    }

                    // Set max edge to 6000
                    Field maxEdgeField = fields.get("maxEdge");
                    if (maxEdgeField != null) {
                        maxEdgeField.setInt(instance, 6000);
                    }

                    // Set max size to 50 MB
                    Field maxSizeField = fields.get("maxKb");
                    if (maxSizeField != null) {
                        maxSizeField.setInt(instance, 50 * 1024);
                    }

                    // Set sampling size to 1 (minimum compression)
                    Field samplingField = fields.get("samplingSize");
                    if (samplingField != null) {
                        samplingField.setInt(instance, 1);
                    }

                    XposedBridge.log("[" + TAG + "] MediaQuality: ProcessImageQuality boosted to max quality");
                } catch (Throwable e) {
                    XposedBridge.log("[" + TAG + "] MediaQuality: ProcessImageQuality boost error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Hook media quality selection method to always return true (enable HD).
     */
    private void hookMediaQualitySelection() {
        Method selectionMethod = Unobfuscator.loadMediaQualitySelectionMethod(loader);
        XposedBridge.hookMethod(selectionMethod, XC_MethodReplacement.returnConstant(true));
        XposedBridge.log("[" + TAG + "] MediaQuality: MediaQualitySelection forced to true");
    }

    /**
     * Hook BottomBarConfig to enable HD quality toggle.
     */
    private void hookBottomBarConfig() {
        try {
            Class<?> bottomBarConfigClass = Unobfuscator.loadBottomBarConfigClass(loader);
            HashMap<String, Field> fields = Unobfuscator.getAllMapFields(bottomBarConfigClass);

            XposedBridge.hookAllConstructors(bottomBarConfigClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object instance = param.thisObject;
                        Field supportsHdField = fields.get("supportsHdQuality");
                        if (supportsHdField != null) {
                            supportsHdField.setBoolean(instance, true);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[" + TAG + "] MediaQuality: BottomBarConfig hook error: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] MediaQuality: BottomBarConfig hooked for HD support");
        } catch (Throwable e) {
            XposedBridge.log("[" + TAG + "] MediaQuality: BottomBarConfig not found, skipping: " + e.getMessage());
        }
    }
}
