package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import org.telegram.sqlite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * SilentGram F6: Chat export (TXT / HTML).
 * Exports the whole chat history from the local database into a file in
 * Downloads — so even if the other side deletes the chat to destroy the
 * evidence, you still have the record.
 */
public class SilentChatExport {

    private static class ExportMsg {
        long date;
        boolean out;
        String sender;
        String text;
    }

    public static void exportChat(BaseFragment fragment, int account, long dialogId, String chatName, boolean asHtml) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        AndroidUtilities.showToast(context, LocaleController.getString(R.string.SilentExportWorking));
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<ExportMsg> msgs = new ArrayList<>();
            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase()
                        .queryFinalized("SELECT date, data, out FROM messages_v2 WHERE uid = " + dialogId + " ORDER BY date ASC, mid ASC");
                while (cursor.next()) {
                    long date = cursor.longValue(0);
                    int out = cursor.intValue(2);
                    NativeByteBuffer data = cursor.byteBufferValue(1);
                    String text = "";
                    String sender = "";
                    if (data != null) {
                        try {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                MessageObject obj = new MessageObject(account, message, false, false);
                                if (obj.messageText != null) {
                                    text = obj.messageText.toString();
                                }
                                if (obj.isOutOwner()) {
                                    sender = "You";
                                } else {
                                    long fromId = DialogObject.getPeerDialogId(message.from_id);
                                    if (fromId > 0) {
                                        TLRPC.User u = MessagesController.getInstance(account).getUser(fromId);
                                        sender = u != null ? UserObject.getUserName(u) : "";
                                    } else if (fromId < 0) {
                                        TLRPC.Chat c = MessagesController.getInstance(account).getChat(-fromId);
                                        sender = c != null ? c.title : "";
                                    }
                                }
                                if (sender.isEmpty()) {
                                    sender = out == 1 ? "You" : chatName;
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
                        m.date = date;
                        m.out = out == 1;
                        m.sender = sender;
                        m.text = text;
                        msgs.add(m);
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                // Return what we have.
            }

            String fileName = "SilentGram-" + sanitize(chatName) + "-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + (asHtml ? ".html" : ".txt");
            String content = asHtml ? buildHtml(chatName, msgs) : buildTxt(chatName, msgs);
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
                    AndroidUtilities.showToast(context, LocaleController.getString(R.string.SilentExportFailed));
                    return;
                }
                AndroidUtilities.showToast(context, LocaleController.getString(R.string.SilentExportDone) + " " + fileName);
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

    private static String buildHtml(String chatName, ArrayList<ExportMsg> msgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(chatName)).append("</title>");
        sb.append("<style>body{font-family:sans-serif;max-width:800px;margin:20px auto;padding:0 16px;background:#0B192F;color:#E8EAF0}");
        sb.append("h1{color:#E8C15A}.msg{margin:10px 0;padding:10px 14px;border-radius:12px;background:#16263F}");
        sb.append(".msg.out{background:#1E3A5F}.meta{font-size:12px;color:#8AA0B8;margin-bottom:4px}</style></head><body>");
        sb.append("<h1>").append(esc(chatName)).append("</h1>");
        sb.append("<p>SilentGram chat export — ").append(msgs.size()).append(" messages</p>");
        for (ExportMsg m : msgs) {
            sb.append("<div class=\"msg").append(m.out ? " out" : "").append("\"><div class=\"meta\">")
                    .append(esc(m.sender)).append(" • ").append(fmtTime(m.date)).append("</div>")
                    .append(esc(m.text).replace("\n", "<br>")).append("</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
