package org.telegram.messenger;

import android.util.Base64;
import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
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
 * the input — tap it and the whole post is SENT.
 *
 * Link-based (Batch V): a template stores the post's exact t.me link
 * (Telegram's own Copy Link), not a copy of the post. Sending resolves
 * the LIVE post from that link and forwards Telegram's exact forward
 * with the sender hidden — a rich post keeps its design (no re-made
 * file, no lost premium emoji, and rich messages work at all). If the
 * post is gone, an error is shown and nothing is sent. Templates with
 * no link (a normal user's DM) fetch the original post and copy it
 * Telegram's own way (server file references, full entities).
 *
 * Templates are PER-ACCOUNT: the storage key carries the account number,
 * so one account's templates never appear or work on another account.
 * Templates saved before this change live under the old global key and
 * are left orphaned (not migrated).
 *
 * Stored as JSON in the "silentgram" SharedPreferences (no DB schema).
 * Hand-composed templates (SilentTemplateComposeActivity) have no source
 * post: they still use the media store below (app-private files dir
 * "silent_templates/") and send the stored text/media as before.
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
         * premium/custom emoji, mentions, ...), or "" for none. */
        public String entities;
        /** Exact t.me post link ("" when the post has no link, e.g. a DM).
         * The link is the primary reference: sending resolves the live
         * post from it and forwards Telegram's exact forward, so rich
         * posts keep their design (no re-made copy). */
        public String link;
        /** Account the template was created on (per-account storage). */
        public int account;
        /** Source post location (the original post), used when there is no
         * link (DM): the original is fetched live and copied Telegram's
         * own way. 0 when the template was composed by hand. */
        public long sourceDialogId;
        public int sourceMessageId;

        public Template(String trigger, String text) {
            this(trigger, text, "", "", "", "", "", "");
        }

        public Template(String trigger, String text, String mediaType, String mediaFile,
                        String mediaName, String mediaMime, String mediaCache) {
            this(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, "");
        }

        public Template(String trigger, String text, String mediaType, String mediaFile,
                        String mediaName, String mediaMime, String mediaCache, String entities) {
            this(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, entities, "", UserConfig.selectedAccount, 0, 0);
        }

        /** Full constructor (link-based templates). */
        public Template(String trigger, String text, String mediaType, String mediaFile,
                        String mediaName, String mediaMime, String mediaCache, String entities,
                        String link, int account, long sourceDialogId, int sourceMessageId) {
            this.trigger = trigger;
            this.text = text;
            this.mediaType = mediaType == null ? "" : mediaType;
            this.mediaFile = mediaFile == null ? "" : mediaFile;
            this.mediaName = mediaName == null ? "" : mediaName;
            this.mediaMime = mediaMime == null ? "" : mediaMime;
            this.mediaCache = mediaCache == null ? "" : mediaCache;
            this.entities = entities == null ? "" : entities;
            this.link = link == null ? "" : link;
            this.account = account;
            this.sourceDialogId = sourceDialogId;
            this.sourceMessageId = sourceMessageId;
        }

        /** True when this template carries media (stored copy or pending download). */
        public boolean hasMedia() {
            return (mediaFile != null && !mediaFile.isEmpty())
                    || (mediaCache != null && !mediaCache.isEmpty());
        }

        /** True when this template references a live post via its link. */
        public boolean hasLink() {
            return link != null && !link.isEmpty();
        }
    }

    private static final String MEDIA_DIR = "silent_templates";

    /** Per-account storage key — one account's templates never leak into
     * another account's list. The old global key is left orphaned. */
    private static String keyForAccount(int account) {
        return "silent_templates_" + account;
    }

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("silentgram", android.content.Context.MODE_PRIVATE);
    }

    /** Templates of the given account only. */
    public static List<Template> getTemplates(int account) {
        ArrayList<Template> out = new ArrayList<>();
        String json = prefs().getString(keyForAccount(account), "[]");
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
                        o.optString("entities"),
                        o.optString("link"),
                        o.optInt("account", account),
                        o.optLong("dialog_id"),
                        o.optInt("message_id")));
            }
        } catch (Exception e) {
            // Corrupt JSON — start fresh.
        }
        return out;
    }

    /** Templates of the currently selected account. */
    public static List<Template> getTemplates() {
        return getTemplates(UserConfig.selectedAccount);
    }

    public static void saveTemplates(int account, List<Template> templates) {
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
                o.put("link", t.link);
                o.put("account", t.account);
                o.put("dialog_id", t.sourceDialogId);
                o.put("message_id", t.sourceMessageId);
                arr.put(o);
            }
            prefs().edit().putString(keyForAccount(account), arr.toString()).apply();
        } catch (Exception e) {
            // Never crash on a save failure.
        }
    }

    public static void saveTemplates(List<Template> templates) {
        saveTemplates(UserConfig.selectedAccount, templates);
    }

    public static void addTemplate(String trigger, String text) {
        addTemplate(trigger, text, "", "", "", "", "");
    }

    /** Upsert by trigger (case-insensitive); replaces text AND media fields. */
    public static void addTemplate(String trigger, String text, String mediaType, String mediaFile,
                                   String mediaName, String mediaMime, String mediaCache) {
        addTemplate(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, "");
    }

    /** Upsert by trigger (case-insensitive); replaces text, media AND entities.
     * A null link means "not specified" — an existing template keeps its
     * link and source post (hand-composed templates have none). */
    public static void addTemplate(String trigger, String text, String mediaType, String mediaFile,
                                   String mediaName, String mediaMime, String mediaCache, String entities) {
        addTemplate(UserConfig.selectedAccount, trigger, text, null, 0, 0, mediaType, mediaFile, mediaName, mediaMime, mediaCache, entities);
    }

    /**
     * Upsert a link-based template on the given account. The link is the
     * primary reference; mediaType is only a preview label (no stored
     * file is made for post-based templates).
     */
    public static void addTemplate(int account, String trigger, String text, String link,
                                   long sourceDialogId, int sourceMessageId,
                                   String mediaType, String entities) {
        addTemplate(account, trigger, text, link, sourceDialogId, sourceMessageId, mediaType, "", "", "", "", entities);
    }

    /** Full upsert (all fields). A null link preserves an existing
     * template's link and source post; a non-null link ("" allowed)
     * replaces them. */
    public static void addTemplate(int account, String trigger, String text, String link,
                                   long sourceDialogId, int sourceMessageId, String mediaType,
                                   String mediaFile, String mediaName, String mediaMime,
                                   String mediaCache, String entities) {
        List<Template> list = getTemplates(account);
        for (Template t : list) {
            if (t.trigger.equalsIgnoreCase(trigger)) {
                t.text = text;
                if (link != null) {
                    t.link = link;
                    t.account = account;
                    t.sourceDialogId = sourceDialogId;
                    t.sourceMessageId = sourceMessageId;
                }
                t.mediaType = mediaType == null ? "" : mediaType;
                t.mediaFile = mediaFile == null ? "" : mediaFile;
                t.mediaName = mediaName == null ? "" : mediaName;
                t.mediaMime = mediaMime == null ? "" : mediaMime;
                t.mediaCache = mediaCache == null ? "" : mediaCache;
                t.entities = entities == null ? "" : entities;
                saveTemplates(account, list);
                return;
            }
        }
        list.add(new Template(trigger, text, mediaType, mediaFile, mediaName, mediaMime, mediaCache, entities,
                link == null ? "" : link, account, sourceDialogId, sourceMessageId));
        saveTemplates(account, list);
    }

    public static void deleteTemplate(int account, String trigger) {
        List<Template> list = getTemplates(account);
        Template removed = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).trigger.equalsIgnoreCase(trigger)) {
                removed = list.remove(i);
                break;
            }
        }
        saveTemplates(account, list);
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

    public static void deleteTemplate(String trigger) {
        deleteTemplate(UserConfig.selectedAccount, trigger);
    }

    /** Templates of the given account whose trigger starts with the given
     * prefix (case-insensitive). */
    public static List<Template> findMatches(int account, String prefix) {
        ArrayList<Template> out = new ArrayList<>();
        if (prefix == null) {
            return out;
        }
        String p = prefix.toLowerCase();
        for (Template t : getTemplates(account)) {
            if (t.trigger.toLowerCase().startsWith(p)) {
                out.add(t);
            }
        }
        return out;
    }

    public static List<Template> findMatches(String prefix) {
        return findMatches(UserConfig.selectedAccount, prefix);
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
                    List<Template> list = getTemplates(t.account);
                    for (Template other : list) {
                        if (other.trigger.equalsIgnoreCase(t.trigger)) {
                            other.mediaFile = t.mediaFile;
                            other.mediaCache = "";
                            break;
                        }
                    }
                    saveTemplates(t.account, list);
                    return copied;
                }
                return cached;
            }
        }
        return null;
    }

    // ---- post links: resolve the live post, forward Telegram's exact forward ----

    /** Resolve/fetch failed: the post is gone (deleted / no access / bad link). */
    public static final int RESOLVE_GONE = 1;
    /** Resolve/fetch failed: the post is in a private channel/group the target
     * account has not joined, so it cannot be fetched there. */
    public static final int RESOLVE_PRIVATE = 2;

    /** Result of resolving a post link (or a stored source post). */
    public interface PostResolveCallback {
        /** The real post, built for the target account — ready to forward. */
        void onPost(MessageObject post);
        /** The post could not be fetched (RESOLVE_GONE / RESOLVE_PRIVATE). */
        void onError(int reason);
    }

    /** Parsed t.me post link. */
    private static class ParsedLink {
        boolean isPrivate;
        String username;   // public channel/group with a username
        long channelId;    // private channel/group (bare id, as in /c/<id>/<post>)
        int postId;
    }

    /** Parse a t.me post link (https://t.me/<username>/<id> or
     * https://t.me/c/<channelId>/<id>). Null when it is not a post link. */
    private static ParsedLink parsePostLink(String link) {
        if (link == null) {
            return null;
        }
        String s = link.trim();
        int idx = s.indexOf("telegram.me/");
        String host;
        if (idx >= 0) {
            host = "telegram.me/";
        } else {
            idx = s.indexOf("t.me/");
            host = "t.me/";
        }
        if (idx < 0) {
            return null;
        }
        s = s.substring(idx + host.length());
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] parts = s.split("/");
        try {
            int off = 0;
            if (parts.length > 0 && "s".equals(parts[0])) {
                off = 1; // t.me/s/<username>/<id> (embedded view) — same post
            }
            if (parts.length - off >= 3 && "c".equals(parts[off])) {
                ParsedLink p = new ParsedLink();
                p.isPrivate = true;
                p.channelId = Long.parseLong(parts[off + 1]);
                p.postId = Integer.parseInt(parts[off + 2]);
                return p.channelId != 0 && p.postId != 0 ? p : null;
            } else if (parts.length - off >= 2) {
                ParsedLink p = new ParsedLink();
                p.isPrivate = false;
                p.username = parts[off];
                p.postId = Integer.parseInt(parts[off + 1]);
                return p.username != null && !p.username.isEmpty() && p.postId != 0 ? p : null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /**
     * Export the post's exact link the way Telegram's own "Copy Link" does
     * (channels.exportMessageLink, same request ChatActivity's OPTION_COPY_LINK
     * sends). Callback runs on the UI thread with the link, or null when the
     * post has no link (DM / basic group / failure).
     */
    public static void exportMessageLink(int account, MessageObject message, Utilities.Callback<String> callback) {
        if (message == null) {
            callback.run(null);
            return;
        }
        long dialogId = message.getDialogId();
        TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(account).getChat(-dialogId) : null;
        if (chat == null || !ChatObject.isChannel(chat)) {
            callback.run(null);
            return;
        }
        TLRPC.TL_channels_exportMessageLink req = new TLRPC.TL_channels_exportMessageLink();
        req.id = message.getId();
        req.channel = MessagesController.getInputChannel(chat);
        req.thread = false;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_exportedMessageLink) {
                callback.run(((TLRPC.TL_exportedMessageLink) response).link);
            } else {
                callback.run(null);
            }
        }));
    }

    /**
     * Resolve a stored t.me post link IN the target account and fetch the
     * real post. A private channel/group the account has not joined reports
     * RESOLVE_PRIVATE; anything else missing reports RESOLVE_GONE.
     */
    public static void resolvePostLink(int targetAccount, String link, PostResolveCallback cb) {
        ParsedLink p = parsePostLink(link);
        if (p == null || p.postId == 0) {
            cb.onError(RESOLVE_GONE);
            return;
        }
        if (p.isPrivate) {
            TLRPC.Chat chat = MessagesController.getInstance(targetAccount).getChat(p.channelId);
            if (chat == null) {
                // The target account does not know this private channel at
                // all (never joined) — the post cannot be fetched there.
                cb.onError(RESOLVE_PRIVATE);
                return;
            }
            fetchChannelPost(targetAccount, chat, p.postId, cb);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = p.username;
        ConnectionsManager.getInstance(targetAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                cb.onError(RESOLVE_GONE);
                return;
            }
            TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController.getInstance(targetAccount).putUsers(res.users, false);
            MessagesController.getInstance(targetAccount).putChats(res.chats, false);
            if (res.peer instanceof TLRPC.TL_peerChannel) {
                long channelId = res.peer.channel_id;
                TLRPC.Chat channel = null;
                for (TLRPC.Chat c : res.chats) {
                    if (c != null && c.id == channelId) {
                        channel = c;
                        break;
                    }
                }
                if (channel == null) {
                    channel = MessagesController.getInstance(targetAccount).getChat(channelId);
                }
                if (channel == null) {
                    cb.onError(RESOLVE_GONE);
                    return;
                }
                fetchChannelPost(targetAccount, channel, p.postId, cb);
            } else {
                long dialogId = MessageObject.getPeerId(res.peer);
                fetchPostByIds(targetAccount, dialogId, p.postId, cb);
            }
        }));
    }

    /**
     * Fetch the original post by its source location (used when there is no
     * link, e.g. a DM). Same fetch as the link path; a channel the account
     * does not know reports RESOLVE_PRIVATE, anything else missing reports
     * RESOLVE_GONE.
     */
    public static void resolvePostByIds(int account, long dialogId, int messageId, PostResolveCallback cb) {
        if (messageId == 0) {
            cb.onError(RESOLVE_GONE);
            return;
        }
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null && ChatObject.isChannel(chat)) {
                fetchChannelPost(account, chat, messageId, cb);
                return;
            }
            if (chat == null) {
                cb.onError(RESOLVE_PRIVATE);
                return;
            }
        }
        fetchPostByIds(account, dialogId, messageId, cb);
    }

    /** Forward the post a link points at, with the sender hidden
     * (drop_author) — Telegram's exact forward, nothing re-made. */
    public static void forwardPostFromLink(int targetAccount, String link, long peer, PostResolveCallback cb) {
        resolvePostLink(targetAccount, link, new PostResolveCallback() {
            @Override
            public void onPost(MessageObject post) {
                ArrayList<MessageObject> list = new ArrayList<>();
                list.add(post);
                SendMessagesHelper.getInstance(targetAccount).sendMessage(list, peer, true, false, true, 0, 0);
                cb.onPost(post);
            }

            @Override
            public void onError(int reason) {
                cb.onError(reason);
            }
        });
    }

    /**
     * Copy the original post Telegram's own way (processForwardFromMyName:
     * server file references, full entities — no re-upload, no re-made
     * file). Only for posts with no link (e.g. a DM).
     */
    public static void copyOriginalPost(int account, long sourceDialogId, int sourceMessageId, long peer,
                                        long monoForumPeerId, MessageSuggestionParams suggestionParams,
                                        PostResolveCallback cb) {
        resolvePostByIds(account, sourceDialogId, sourceMessageId, new PostResolveCallback() {
            @Override
            public void onPost(MessageObject post) {
                SendMessagesHelper.getInstance(account).processForwardFromMyName(post, peer, 0, monoForumPeerId, suggestionParams, true, 0, 0);
                cb.onPost(post);
            }

            @Override
            public void onError(int reason) {
                cb.onError(reason);
            }
        });
    }

    private static void fetchChannelPost(int account, TLRPC.Chat channel, int postId, PostResolveCallback cb) {
        TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = MessagesController.getInputChannel(channel);
        req.id.add(postId);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                cb.onError(error.text != null && error.text.contains("PRIVATE") ? RESOLVE_PRIVATE : RESOLVE_GONE);
                return;
            }
            handleFetchResponse(account, -channel.id, postId, response, cb);
        }));
    }

    private static void fetchPostByIds(int account, long dialogId, int postId, PostResolveCallback cb) {
        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add(postId);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                cb.onError(RESOLVE_GONE);
                return;
            }
            handleFetchResponse(account, dialogId, postId, response, cb);
        }));
    }

    private static void handleFetchResponse(int account, long dialogId, int postId, TLObject response, PostResolveCallback cb) {
        if (!(response instanceof TLRPC.messages_Messages)) {
            cb.onError(RESOLVE_GONE);
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        MessagesController.getInstance(account).putUsers(res.users, false);
        MessagesController.getInstance(account).putChats(res.chats, false);
        TLRPC.Message found = null;
        for (TLRPC.Message m : res.messages) {
            if (m != null && m.id == postId) {
                found = m;
                break;
            }
        }
        if (found == null) {
            cb.onError(RESOLVE_GONE);
            return;
        }
        LongSparseArray<TLRPC.User> usersLocal = new LongSparseArray<>();
        for (TLRPC.User u : res.users) {
            usersLocal.put(u.id, u);
        }
        LongSparseArray<TLRPC.Chat> chatsLocal = new LongSparseArray<>();
        for (TLRPC.Chat c : res.chats) {
            chatsLocal.put(c.id, c);
        }
        found.dialog_id = dialogId;
        MessageObject obj = new MessageObject(account, found, usersLocal, chatsLocal, true, true);
        cb.onPost(obj);
    }
}
