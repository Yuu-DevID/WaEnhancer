package com.wmods.wppenhacer.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class DefaultSettingsLoader {

    private static final String DEFAULT_SETTINGS_FILE = "default_settings.json";
    private static final String TAG = "DefaultSettingsLoader";

    public static void applyDefaults(Context context) {
        try {
            String json = loadJsonFromAssets(context);
            if (json == null) return;

            JSONObject settings = new JSONObject(json);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();

            Set<String> jsonKeys = new HashSet<>();
            java.util.Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                jsonKeys.add(key);

                JSONObject entry = settings.getJSONObject(key);
                String type = entry.getString("type");
                Object value = entry.get("value");

                switch (type) {
                    case "Boolean":
                        editor.putBoolean(key, (Boolean) value);
                        break;
                    case "String":
                        editor.putString(key, (String) value);
                        break;
                    case "Integer":
                        editor.putInt(key, (Integer) value);
                        break;
                    case "Float":
                        editor.putFloat(key, ((Number) value).floatValue());
                        break;
                    case "Long":
                        editor.putLong(key, ((Number) value).longValue());
                        break;
                }
            }

            for (String key : prefs.getAll().keySet()) {
                if (!jsonKeys.contains(key) && prefs.getAll().get(key) instanceof Boolean) {
                    editor.putBoolean(key, false);
                }
            }

            editor.apply();
            Log.i(TAG, "Applied " + jsonKeys.size() + " default settings, disabled " +
                    (prefs.getAll().size() - jsonKeys.size()) + " non-JSON boolean keys");
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to apply defaults", e);
        }
    }

    private static String loadJsonFromAssets(Context context) throws IOException {
        try (InputStream is = context.getAssets().open(DEFAULT_SETTINGS_FILE)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
