package com.wmods.wppenhacer.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class DefaultSettingsLoader {

    private static final String DEFAULT_SETTINGS_FILE = "default_settings.json";

    public static void applyDefaults(Context context) {
        try {
            String json = loadJsonFromAssets(context);
            if (json == null) return;

            JSONObject settings = new JSONObject(json);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            boolean modified = false;

            java.util.Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (prefs.contains(key)) continue;

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
                modified = true;
            }

            if (modified) {
                editor.apply();
            }
        } catch (JSONException | IOException e) {
            e.printStackTrace();
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
