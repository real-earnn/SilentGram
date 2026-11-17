package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * SilentGram F6: Chat export (TXT / HTML).
 * Exports the whole chat history from the local database into a file in
 * Downloads — so even if the other side deletes the chat to destroy the
 * evidence, you still have the record.
 *
 * The HTML export is styled to look like Telegram itself: white incoming
 * bubbles, light-green outgoing bubbles (#effdde), round avatars on the
 * left in groups, per-sender name colors, timestamps inside the bubble
 * bottom-right, centered date pills, and reply / forward blocks.
 */
public class SilentChatExport {

    private static class ExportMsg {
        int mid;
        long date;
        boolean out;
        String sender;
        long senderId;
        boolean verified;
        boolean bot;
        String fwdFrom;
        int replyToId;
        String replySender;
        String replyText;
        String text;
    }

    // Telegram-style per-sender name colors (stable per sender id).
    private static final String[] NAME_COLORS = {
            "#e53935", "#fb8c00", "#43a047", "#00acc1", "#1e88e5", "#8e24aa", "#d81b60"
    };

    // Self-contained verified badge: blue circle + white check, no external assets.
    // It lives inside the name row (inline-flex, flex-shrink:0) so it can never
    // wrap onto the next line (G29b).
    private static final String VERIFIED_BADGE = "<span class=\"badge\" title=\"Verified\">"
            + "<svg width=\"16\" height=\"16\" viewBox=\"0 0 16 16\" aria-hidden=\"true\">"
            + "<circle cx=\"8\" cy=\"8\" r=\"8\" fill=\"#229ed9\"/>"
            + "<path d=\"M4.6 8.3l2.2 2.2 4.6-5\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
            + "</svg></span>";

    public static void exportChat(BaseFragment fragment, int account, long dialogId, String chatName, boolean asHtml) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        SilentToast.show(context, LocaleController.getString(R.string.SilentExportWorking), android.widget.Toast.LENGTH_SHORT);
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<ExportMsg> msgs = new ArrayList<>();
            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase()
                        .queryFinalized("SELECT mid, date, data, out FROM messages_v2 WHERE uid = " + dialogId + " ORDER BY date ASC, mid ASC");
                while (cursor.next()) {
                    long date = cursor.longValue(1);
                    int out = cursor.intValue(3);
                    NativeByteBuffer data = cursor.byteBufferValue(2);
                    String text = "";
                    String sender = "";
                    long senderId = 0;
                    boolean verified = false;
                    boolean bot = false;
                    String fwdFrom = null;
                    int replyToId = 0;
                    int mid = cursor.intValue(0);
                    if (data != null) {
                        try {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                mid = message.id;
                                MessageObject obj = new MessageObject(account, message, false, false);
                                if (obj.messageText != null) {
                                    text = obj.messageText.toString();
                                }
                                if (obj.isOutOwner()) {
                                    sender = "You";
                                } else {
                                    long fromId = DialogObject.getPeerDialogId(message.from_id);
                                    senderId = fromId;
                                    if (fromId > 0) {
                                        TLRPC.User u = MessagesController.getInstance(account).getUser(fromId);
                                        if (u != null) {
                                            sender = UserObject.getUserName(u);
                                            verified = u.verified;
                                            bot = u.bot;
                                        }
                                    } else if (fromId < 0) {
                                        TLRPC.Chat c = MessagesController.getInstance(account).getChat(-fromId);
                                        if (c != null) {
                                            sender = c.title;
                                            verified = c.verified;
                                        }
                                    }
                                }
                                if (sender.isEmpty()) {
                                    sender = out == 1 ? "You" : chatName;
                                }
                                if (message.fwd_from != null) {
                                    fwdFrom = resolveFwdFrom(account, message.fwd_from);
                                }
                                if (message.reply_to != null) {
                                    replyToId = message.reply_to.reply_to_msg_id;
                                }
                            }
                        } catch (Exception e) {
                            // Skip unreadable rows.
                        } finally {
                            data.reuse();
                        }
                    }
                    if (!text.isEmpty()) {
                        ExportMsg m = new ExportMsg();
                        m.mid = mid;
                        m.date = date;
                        m.out = out == 1;
                        m.sender = sender;
                        m.senderId = senderId;
                        m.verified = verified;
                        m.bot = bot;
                        m.fwdFrom = fwdFrom;
                        m.replyToId = replyToId;
                        m.text = text;
                        msgs.add(m);
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                // Return what we have.
            }

            // Resolve replies against the messages we just loaded.
            HashMap<Integer, ExportMsg> byId = new HashMap<>();
            for (ExportMsg m : msgs) {
                byId.put(m.mid, m);
            }
            for (ExportMsg m : msgs) {
                if (m.replyToId != 0) {
                    ExportMsg target = byId.get(m.replyToId);
                    if (target != null) {
                        m.replySender = target.sender;
                        m.replyText = target.text;
                    }
                }
            }

            String fileName = "SilentGram-" + sanitize(chatName) + "-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + (asHtml ? ".html" : ".txt");
            String content = asHtml ? buildHtml(chatName, msgs, dialogId < 0) : buildTxt(chatName, msgs);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, fileName);
            boolean ok = false;
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(content.getBytes("UTF-8"));
                fos.flush();
                fos.close();
                ok = true;
            } catch (Exception e) {
                // Write failed.
            }
            final boolean done = ok;
            final File outFile = file;
            AndroidUtilities.runOnUIThread(() -> {
                if (!done) {
                    SilentToast.show(context, LocaleController.getString(R.string.SilentExportFailed), android.widget.Toast.LENGTH_SHORT);
                    return;
                }
                SilentToast.show(context, LocaleController.getString(R.string.SilentExportDone) + " " + fileName, android.widget.Toast.LENGTH_SHORT);
                // Offer to share/open the file.
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(asHtml ? "text/html" : "text/plain");
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                            ApplicationLoader.applicationContext.getPackageName() + ".provider", outFile);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.SilentExportShare)));
                } catch (Exception e) {
                    // Toast is enough.
                }
            });
        });
    }

    private static String resolveFwdFrom(int account, TLRPC.MessageFwdHeader fwd) {
        if (fwd.from_name != null && !fwd.from_name.isEmpty()) {
            return fwd.from_name;
        }
        if (fwd.post_author != null && !fwd.post_author.isEmpty()) {
            return fwd.post_author;
        }
        if (fwd.from_id != null) {
            long fwdId = DialogObject.getPeerDialogId(fwd.from_id);
            if (fwdId > 0) {
                TLRPC.User u = MessagesController.getInstance(account).getUser(fwdId);
                return u != null ? UserObject.getUserName(u) : null;
            } else if (fwdId < 0) {
                TLRPC.Chat c = MessagesController.getInstance(account).getChat(-fwdId);
                return c != null ? c.title : null;
            }
        }
        return null;
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "chat";
        }
        return s.replaceAll("[^a-zA-Z0-9-_]+", "_");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String fmtTime(long seconds) {
        return new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(new Date(seconds * 1000));
    }

    private static String fmtTimeShort(long seconds) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(seconds * 1000));
    }

    private static String fmtDay(long seconds) {
        return new SimpleDateFormat("d MMMM yyyy", Locale.US).format(new Date(seconds * 1000));
    }

    private static String colorFor(long senderId, String sender) {
        long key = senderId != 0 ? senderId : (sender == null ? 0 : sender.hashCode());
        return NAME_COLORS[(int) Math.floorMod(key, NAME_COLORS.length)];
    }

    private static String initials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) {
            sb.append(Character.toUpperCase(parts[1].charAt(0)));
        }
        return sb.toString();
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        if (t.length() > max) {
            t = t.substring(0, max) + "…";
        }
        return t;
    }

    private static String buildTxt(String chatName, ArrayList<ExportMsg> msgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("SilentGram chat export — ").append(chatName).append("\n");
        sb.append("Exported: ").append(new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append("Messages: ").append(msgs.size()).append("\n");
        sb.append("--------------------------------------------------\n\n");
        for (ExportMsg m : msgs) {
            sb.append("[").append(fmtTime(m.date)).append("] ").append(m.sender).append(": ").append(m.text).append("\n");
        }
        return sb.toString();
    }

    private static String buildHtml(String chatName, ArrayList<ExportMsg> msgs, boolean isGroup) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        sb.append("<title>").append(esc(chatName)).append("</title><style>");
        // Telegram classic light theme: pale blue-grey chat background,
        // white incoming bubbles, light-green outgoing bubbles (#effdde).
        sb.append("body{margin:0;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:#e7ebf0;color:#000}");
        sb.append(".header{position:sticky;top:0;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.12);padding:8px 16px;display:flex;align-items:center;gap:12px;z-index:10}");
        sb.append(".header .av{width:42px;height:42px;border-radius:50%;background:#5288c1;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600;font-size:16px;flex-shrink:0}");
        sb.append(".header .t{font-size:16px;font-weight:600;line-height:1.25}");
        sb.append(".header .s{font-size:13px;color:#707579;line-height:1.25}");
        sb.append(".chat{max-width:728px;margin:0 auto;padding:16px 16px 32px}");
        sb.append(".day{text-align:center;margin:18px 0 8px}");
        sb.append(".day span{display:inline-block;background:#fff;color:#5f7a8c;border-radius:14px;padding:4px 12px;font-size:13px;box-shadow:0 1px 2px rgba(0,0,0,.12)}");
        sb.append(".row{display:flex;margin:8px 0;align-items:flex-end}");
        sb.append(".row.out{justify-content:flex-end}");
        sb.append(".avatar{width:42px;height:42px;border-radius:50%;flex-shrink:0;margin-right:8px;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600;font-size:16px}");
        sb.append(".msg{max-width:65%;padding:6px 12px 8px;border-radius:12px;font-size:15px;line-height:1.45;word-wrap:break-word;box-shadow:0 1px 2px rgba(0,0,0,.15)}");
        sb.append(".msg.in{background:#fff;border-bottom-left-radius:4px}");
        sb.append(".msg.out{background:#effdde;border-bottom-right-radius:4px}");
        // Name row (G29b): one single inline-flex line — name + bot tag + verified
        // badge can never wrap; the badge is flex-shrink:0 so it stays glued to
        // the name instead of dropping below it half-hidden.
        sb.append(".name{display:inline-flex;align-items:center;white-space:nowrap;max-width:100%;font-size:14px;font-weight:600;margin-bottom:2px}");
        sb.append(".name .n{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}");
        sb.append(".tag{flex-shrink:0;margin-left:6px;font-size:11px;font-weight:600;color:#229ed9;background:rgba(34,158,217,.12);border-radius:4px;padding:1px 5px;white-space:nowrap}");
        sb.append(".badge{flex-shrink:0;margin-left:4px;width:16px;height:16px;display:inline-flex;align-items:center;flex-shrink:0}");
        sb.append(".badge svg{display:block}");
        sb.append(".fwd{font-size:13px;color:#707579;margin-bottom:2px}");
        sb.append(".fwd b{color:#229ed9;font-weight:600}");
        sb.append(".reply{border-left:2px solid #229ed9;border-radius:2px;background:rgba(0,0,0,.03);padding:3px 8px;margin:2px 0 4px;font-size:14px;overflow:hidden}");
        sb.append(".reply .rn{font-weight:600;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
        sb.append(".reply .rt{color:#707579;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
        sb.append(".time{float:right;font-size:12px;color:#8a97a3;margin:10px 0 0 8px;white-space:nowrap}");
        sb.append(".ticks{color:#53a8e0}");
        sb.append(".foot{text-align:center;font-size:12px;color:#8a97a3;margin-top:28px}");
        sb.append("</style></head><body>");
        sb.append("<div class=\"header\"><div class=\"av\">").append(esc(initials(chatName))).append("</div>");
        sb.append("<div><div class=\"t\">").append(esc(chatName)).append("</div>");
        sb.append("<div class=\"s\">").append(msgs.size()).append(" messages</div></div></div>");
        sb.append("<div class=\"chat\">");
        String lastDay = null;
        for (ExportMsg m : msgs) {
            String day = fmtDay(m.date);
            if (!day.equals(lastDay)) {
                sb.append("<div class=\"day\"><span>").append(esc(day)).append("</span></div>");
                lastDay = day;
            }
            String color = colorFor(m.senderId, m.sender);
            sb.append("<div class=\"row").append(m.out ? " out" : "").append("\">");
            if (!m.out && isGroup) {
                sb.append("<div class=\"avatar\" style=\"background:").append(color).append("\">")
                        .append(esc(initials(m.sender))).append("</div>");
            }
            sb.append("<div class=\"msg").append(m.out ? " out" : " in").append("\">");
            // Telegram shows no sender name on your own bubbles, and none in
            // 1-1 chats — except when the sender is a bot / verified, so the
            // bot tag and verified badge stay visible in the export.
            boolean showName = !m.out && (isGroup || m.verified || m.bot);
            if (showName) {
                sb.append("<div class=\"name\" style=\"color:").append(color).append("\"><span class=\"n\">")
                        .append(esc(m.sender)).append("</span>");
                if (m.bot) {
                    sb.append("<span class=\"tag\">bot</span>");
                }
                if (m.verified) {
                    sb.append(VERIFIED_BADGE);
                }
                sb.append("</div>");
            }
            if (m.fwdFrom != null && !m.fwdFrom.isEmpty()) {
                sb.append("<div class=\"fwd\">Forwarded from <b>").append(esc(m.fwdFrom)).append("</b></div>");
            }
            if (m.replySender != null) {
                sb.append("<div class=\"reply\" style=\"border-left-color:").append(colorFor(0, m.replySender)).append("\">")
                        .append("<div class=\"rn\" style=\"color:").append(colorFor(0, m.replySender)).append("\">")
                        .append(esc(m.replySender)).append("</div>")
                        .append("<div class=\"rt\">").append(esc(oneLine(m.replyText, 90))).append("</div></div>");
            }
            sb.append("<div class=\"text\">").append(esc(m.text).replace("\n", "<br>"));
            sb.append("<span class=\"time\">").append(fmtTimeShort(m.date));
            if (m.out) {
                sb.append(" <span class=\"ticks\">&#10003;&#10003;</span>");
            }
            sb.append("</span></div>");
            sb.append("</div></div>");
        }
        sb.append("</div>");
        sb.append("<div class=\"foot\">Exported from SilentGram &bull; ")
                .append(esc(new SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.US).format(new Date()))).append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }
}
