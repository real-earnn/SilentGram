package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SilentGram E4: Quick reply templates / snippets.
 *
 * The user saves templates (a trigger like "mywork" + the full text) in
 * Settings. Typing "/mywork" in ANY chat input pops a suggestion box above
 * the input — tap it and the template text is inserted.
 *
 * Stored as JSON in the "silentgram" SharedPreferences (no DB schema).
 */
public class SilentTemplates {

    public static class Template {
        public String trigger;
        public String text;

        public Template(String trigger, String text) {
            this.trigger = trigger;
            this.text = text;
        }
    }

    private static final String KEY = "silent_templates";

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    public static List<Template> getTemplates() {
        ArrayList<Template> out = new ArrayList<>();
        String json = prefs().getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Template(o.optString("trigger"), o.optString("text")));
            }
        } catch (Exception e) {
            // Corrupt JSON — start fresh.
        }
        return out;
    }

    public static void saveTemplates(List<Template> templates) {
        try {
            JSONArray arr = new JSONArray();
            for (Template t : templates) {
                JSONObject o = new JSONObject();
                o.put("trigger", t.trigger);
                o.put("text", t.text);
                arr.put(o);
            }
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    public static void addTemplate(String trigger, String text) {
        List<Template> list = getTemplates();
        for (Template t : list) {
            if (t.trigger.equalsIgnoreCase(trigger)) {
                t.text = text;
                saveTemplates(list);
                return;
            }
        }
        list.add(new Template(trigger, text));
        saveTemplates(list);
    }

    public static void deleteTemplate(String trigger) {
        List<Template> list = getTemplates();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).trigger.equalsIgnoreCase(trigger)) {
                list.remove(i);
                break;
            }
        }
        saveTemplates(list);
    }

    /** Templates whose trigger starts with the given prefix (case-insensitive). */
    public static List<Template> findMatches(String prefix) {
        ArrayList<Template> out = new ArrayList<>();
        if (prefix == null) {
            return out;
        }
        String p = prefix.toLowerCase();
        for (Template t : getTemplates()) {
            if (t.trigger.toLowerCase().startsWith(p)) {
                out.add(t);
            }
        }
        return out;
    }
}
