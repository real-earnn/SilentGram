package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * SilentGram first-login folder setup.
 *
 * The first time a user logs in, SilentGram offers — in one tap — to create
 * four chat folders so everything stays separate:
 *   Bots, Users, Groups, Channels.
 *
 * The popup appears only once. Before showing it we check whether the account
 * already has any folders: if folders already exist, the popup is skipped
 * entirely (the user set them up themselves). Add creates the folders via
 * messages.updateDialogFilter; Cancel just dismisses and never asks again.
 */
public class SilentFolderSetup {

    private static final String PREFS = "silentgram";
    private static final String KEY_ASKED = "folders_asked";

    private static final String[] NAMES = {"Bots", "Users", "Groups", "Channels"};
    private static final int[] FLAGS = {
            MessagesController.DIALOG_FILTER_FLAG_BOTS,
            MessagesController.DIALOG_FILTER_FLAG_CONTACTS | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS,
            MessagesController.DIALOG_FILTER_FLAG_GROUPS,
            MessagesController.DIALOG_FILTER_FLAG_CHANNELS
    };
    // Distinct folder colours (0-7).
    private static final int[] COLORS = {5, 0, 2, 6};

    /**
     * Show the one-time folder setup popup if this account has no folders yet
     * and we have never asked. Safe to call repeatedly — it no-ops after the
     * first time. Must be called on the UI thread with a live Activity.
     */
    public static void maybeShow(Activity activity, int account) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED, false)) {
            return;
        }
        MessagesController messagesController = AccountInstance.getInstance(account).getMessagesController();
        if (messagesController == null) {
            return;
        }
        // If the account already has folders (beyond the default "All Chats"),
        // skip the popup entirely.
        if (hasCustomFolders(messagesController)) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply();
            return;
        }
        // Only ask once, even if the user cancels.
        prefs.edit().putBoolean(KEY_ASKED, true).apply();

        StringBuilder message = new StringBuilder();
        message.append("Create these folders in one tap so every chat stays separate?\n\n");
        for (String name : NAMES) {
            message.append("• ").append(name).append("\n");
        }
        message.append("\nYou can rename or delete them later in Settings → Folders.");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Set up chat folders?");
        builder.setMessage(message.toString());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Add), (dialog, which) -> {
            createFolders(account);
        });
        try {
            AlertDialog dialog = builder.create();
            dialog.show();
            // Make the Add button stand out.
            android.widget.TextView button = (android.widget.TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** True if the account already has at least one non-default folder. */
    private static boolean hasCustomFolders(MessagesController messagesController) {
        ArrayList<MessagesController.DialogFilter> filters = messagesController.getDialogFilters();
        if (filters == null) {
            return false;
        }
        for (MessagesController.DialogFilter f : filters) {
            if (f != null && !f.isDefault()) {
                return true;
            }
        }
        return false;
    }

    /** Create the four folders (Bots, Users, Groups, Channels). */
    private static void createFolders(int account) {
        AccountInstance accountInstance = AccountInstance.getInstance(account);
        MessagesController messagesController = accountInstance.getMessagesController();
        ConnectionsManager connectionsManager = accountInstance.getConnectionsManager();
        MessagesStorage messagesStorage = accountInstance.getMessagesStorage();
        if (messagesController == null || connectionsManager == null || messagesStorage == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < NAMES.length; i++) {
                createOneFolder(messagesController, connectionsManager, messagesStorage, NAMES[i], FLAGS[i], COLORS[i]);
            }
        });
    }

    private static void createOneFolder(MessagesController messagesController, ConnectionsManager connectionsManager, MessagesStorage messagesStorage, String name, int flags, int color) {
        // Pick a free filter id (2, 3, 4... — 1 is the default "All Chats").
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = 2;
        while (messagesController.dialogFiltersById.get(filter.id) != null) {
            filter.id++;
        }
        filter.name = name;
        filter.flags = flags;
        filter.color = color;

        // Send to the server.
        TLRPC.TL_messages_updateDialogFilter req = new TLRPC.TL_messages_updateDialogFilter();
        req.id = filter.id;
        req.flags |= 1;
        req.filter = new TLRPC.TL_dialogFilter();
        req.filter.contacts = (flags & MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0;
        req.filter.non_contacts = (flags & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0;
        req.filter.groups = (flags & MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0;
        req.filter.broadcasts = (flags & MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0;
        req.filter.bots = (flags & MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0;
        req.filter.id = filter.id;
        req.filter.title = new TLRPC.TL_textWithEntities();
        req.filter.title.text = name;
        req.filter.color = color;
        req.filter.flags |= 134217728;

        final MessagesController.DialogFilter finalFilter = filter;
        connectionsManager.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            // Add locally so the folders appear right away.
            messagesController.addFilter(finalFilter, false);
            messagesStorage.saveDialogFilter(finalFilter, false, true);
            FileLog.d("SilentFolderSetup: created folder " + name + " (id " + finalFilter.id + ")");
        }));
    }
}
