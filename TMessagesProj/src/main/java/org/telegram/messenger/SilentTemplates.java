package org.telegram.messenger;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SilentGram E4 / K13: Quick reply templates / snippets.
 *
 * The user saves templates (a trigger like "mywork" + the full post) in
 * Settings. Typing "/mywork" in ANY chat input pops a suggestion box above
 * the input — tap it and the whole post (text + media) is SENT.
 *
 * Stored as JSON in the "silentgram" SharedPreferences (no DB schema).
 * Media lives as a copy in the app's private files dir
 * ("silent_templates/") so it survives the original chat/gallery going
 * away; {@link #mediaCache} optionally remembers the Telegram cache path
 * of a file that was still downloading when the template was saved, so a
 * later send can pick it up once the download lands.
 *
 * Old (text-only) templates have no media keys at all — they keep working
 * unchanged, because every media field is read with optString().
 */
public class SilentTemplates {

    public static final String MEDIA_PHOTO = "photo";
    public static final String MEDIA_VIDEO = "video";
    public static final String MEDIA_DOCUMENT = "document";

    public static class Template {
        public String trigger;
        public String text;
        /** "photo" / "video" / "document", or "" for text-only. */
        public String mediaType;
        /** File name inside the silent_templates store dir, or "". */
        public String mediaFile;
        /** Original display name (photo/video/file name), or "". */
        public String mediaName;
        /** MIME type (documents), or "". */
        public String mediaMime;
        /** Telegram cache path of a not-yet-downloaded file, or "". */
        public String mediaCache;
        /** Base64 of the serialized text/caption entities (bold, links,
         * premium/custom emoji, mentions, ...), or "" for none. This is
         * what keeps a premium emoji alive in a template (Batch U). */
        public String entities;

        public Template(String trigger, String text) {
            this(trigger, text, "", "", "", "", "", "");
        }

        public Template(String trigger, String text, String mediaType, String mediaFile,
                        String mediaName, String mediaMime, String mediaCache) {
            this(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, "");
        }

        public Template(String trigger, String text, String mediaType, String mediaFile,
                        String mediaName, String mediaMime, String mediaCache, String entities) {
            this.trigger = trigger;
            this.text = text;
            this.mediaType = mediaType == null ? "" : mediaType;
            this.mediaFile = mediaFile == null ? "" : mediaFile;
            this.mediaName = mediaName == null ? "" : mediaName;
            this.mediaMime = mediaMime == null ? "" : mediaMime;
            this.mediaCache = mediaCache == null ? "" : mediaCache;
            this.entities = entities == null ? "" : entities;
        }

        /** True when this template carries media (stored copy or pending download). */
        public boolean hasMedia() {
            return (mediaFile != null && !mediaFile.isEmpty())
                    || (mediaCache != null && !mediaCache.isEmpty());
        }
    }

    private static final String KEY = "silent_templates";
    private static final String MEDIA_DIR = "silent_templates";

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
                out.add(new Template(
                        o.optString("trigger"),
                        o.optString("text"),
                        o.optString("media_type"),
                        o.optString("media_file"),
                        o.optString("media_name"),
                        o.optString("media_mime"),
                        o.optString("media_cache"),
                        o.optString("entities")));
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
                o.put("media_type", t.mediaType);
                o.put("media_file", t.mediaFile);
                o.put("media_name", t.mediaName);
                o.put("media_mime", t.mediaMime);
                o.put("media_cache", t.mediaCache);
                o.put("entities", t.entities);
                arr.put(o);
            }
            prefs().edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    public static void addTemplate(String trigger, String text) {
        addTemplate(trigger, text, "", "", "", "", "");
    }

    /** Upsert by trigger (case-insensitive); replaces text AND media fields. */
    public static void addTemplate(String trigger, String text, String mediaType, String mediaFile,
                                   String mediaName, String mediaMime, String mediaCache) {
        addTemplate(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, "");
    }

    /** Upsert by trigger (case-insensitive); replaces text, media AND entities. */
    public static void addTemplate(String trigger, String text, String mediaType, String mediaFile,
                                   String mediaName, String mediaMime, String mediaCache, String entities) {
        List<Template> list = getTemplates();
        for (Template t : list) {
            if (t.trigger.equalsIgnoreCase(trigger)) {
                t.text = text;
                t.mediaType = mediaType == null ? "" : mediaType;
                t.mediaFile = mediaFile == null ? "" : mediaFile;
                t.mediaName = mediaName == null ? "" : mediaName;
                t.mediaMime = mediaMime == null ? "" : mediaMime;
                t.mediaCache = mediaCache == null ? "" : mediaCache;
                t.entities = entities == null ? "" : entities;
                saveTemplates(list);
                return;
            }
        }
        list.add(new Template(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, entities));
        saveTemplates(list);
    }

    public static void deleteTemplate(String trigger) {
        List<Template> list = getTemplates();
        Template removed = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).trigger.equalsIgnoreCase(trigger)) {
                removed = list.remove(i);
                break;
            }
        }
        saveTemplates(list);
        // Drop the stored media copy too, unless another template still uses it.
        if (removed != null && removed.mediaFile != null && !removed.mediaFile.isEmpty()) {
            boolean stillUsed = false;
            for (Template t : list) {
                if (removed.mediaFile.equals(t.mediaFile)) {
                    stillUsed = true;
                    break;
                }
            }
            if (!stillUsed) {
                try {
                    File f = new File(getMediaDir(ApplicationLoader.applicationContext), removed.mediaFile);
                    if (f.exists()) {
                        f.delete();
                    }
                } catch (Exception ignored) {
                }
            }
        }
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

    // ---- entities (keeps bold / links / premium emoji alive) ----

    /** Serialize entities to a Base64 string ("" when there are none). */
    public static String entitiesToBase64(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        try {
            SerializedData calc = new SerializedData(true);
            calc.writeInt32(entities.size());
            for (TLRPC.MessageEntity e : entities) {
                e.serializeToStream(calc);
            }
            SerializedData data = new SerializedData(calc.length());
            data.writeInt32(entities.size());
            for (TLRPC.MessageEntity e : entities) {
                e.serializeToStream(data);
            }
            String out = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
            data.cleanup();
            calc.cleanup();
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** Deserialize entities from {@link #entitiesToBase64} (null when none / corrupt). */
    public static ArrayList<TLRPC.MessageEntity> entitiesFromBase64(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(Base64.decode(b64, Base64.DEFAULT));
            int count = data.readInt32(false);
            ArrayList<TLRPC.MessageEntity> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int constructor = data.readInt32(false);
                TLRPC.MessageEntity e = TLRPC.MessageEntity.TLdeserialize(data, constructor, false);
                if (e != null) {
                    out.add(e);
                }
            }
            data.cleanup();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- media store (app-private files dir / silent_templates) ----

    public static File getMediaDir(android.content.Context context) {
        File dir = new File(context.getFilesDir(), MEDIA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** The stored media copy for this template, or null if there is none / it is gone. */
    public static File getMediaFile(android.content.Context context, Template t) {
        if (t == null || t.mediaFile == null || t.mediaFile.isEmpty()) {
            return null;
        }
        File f = new File(getMediaDir(context), t.mediaFile);
        return f.exists() ? f : null;
    }

    /** File name safe for the store dir: strip path separators and odd chars. */
    private static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) {
            return "media";
        }
        String clean = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (clean.length() > 80) {
            clean = clean.substring(clean.length() - 80);
        }
        return clean;
    }

    /** Copy a local file into the store dir under a unique name. Null on failure. */
    public static File copyMediaIntoStore(android.content.Context context, File src, String origName) {
        if (src == null || !src.exists()) {
            return null;
        }
        try {
            File dir = getMediaDir(context);
            String name = System.currentTimeMillis() + "_" + sanitizeName(origName);
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return dst;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stream (e.g. from a content:// picker Uri) into the store dir. Null on failure. */
    public static File copyStreamIntoStore(android.content.Context context, InputStream in, String origName) {
        if (in == null) {
            return null;
        }
        try {
            File dir = getMediaDir(context);
            String name = System.currentTimeMillis() + "_" + sanitizeName(origName);
            File dst = new File(dir, name);
            try (InputStream input = in; OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return dst;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The file to send for this template, or null for text-only.
     * Prefers the stored copy; if a download that was pending at save time
     * has since landed in the Telegram cache, it is copied into the store
     * (and the template record updated) and then used.
     */
    public static File resolveMediaFile(android.content.Context context, Template t) {
        if (t == null) {
            return null;
        }
        File stored = getMediaFile(context, t);
        if (stored != null) {
            return stored;
        }
        if (t.mediaCache != null && !t.mediaCache.isEmpty()) {
            File cached = new File(t.mediaCache);
            if (cached.exists()) {
                File copied = copyMediaIntoStore(context, cached, t.mediaName);
                if (copied != null) {
                    t.mediaFile = copied.getName();
                    t.mediaCache = "";
                    // Persist the migration so the next send uses the copy directly.
                    List<Template> list = getTemplates();
                    for (Template other : list) {
                        if (other.trigger.equalsIgnoreCase(t.trigger)) {
                            other.mediaFile = t.mediaFile;
                            other.mediaCache = "";
                            break;
                        }
                    }
                    saveTemplates(list);
                    return copied;
                }
                return cached;
            }
        }
        return null;
    }
}
