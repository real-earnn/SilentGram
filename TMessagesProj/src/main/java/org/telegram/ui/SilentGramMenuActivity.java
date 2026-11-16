package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * SilentGram F7: the SilentGram menu in Settings.
 * One place for every SilentGram feature — Developer, Templates, Download
 * Manager, Bot Tester, Recent Deleted Chats, Deleted & Edited History,
 * Scheduled Messages, Chat Lock, Auto-download Rules, Notes & Reminders.
 */
public class SilentGramMenuActivity extends BaseFragment {

    private static class Row {
        final int id;
        final String title;
        final String subtitle;

        Row(int id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final int ROW_DEVELOPER = 1;
    private static final int ROW_TEMPLATES = 2;
    private static final int ROW_DOWNLOADS = 3;
    private static final int ROW_BOT_TESTER = 4;
    private static final int ROW_DELETED_CHATS = 5;
    private static final int ROW_HISTORY = 6;
    private static final int ROW_SCHEDULED = 7;
    private static final int ROW_CHAT_LOCK = 8;
    private static final int ROW_AUTODL = 9;
    private static final int ROW_NOTES = 10;

    private final Row[] rows = new Row[]{
            new Row(ROW_DEVELOPER, LocaleController.getString(R.string.SilentDeveloperTitle), LocaleController.getString(R.string.SilentMenuDeveloperSub)),
            new Row(ROW_TEMPLATES, LocaleController.getString(R.string.SilentTemplatesTitle), LocaleController.getString(R.string.SilentMenuTemplatesSub)),
            new Row(ROW_DOWNLOADS, LocaleController.getString(R.string.SilentDownloadManagerTitle), LocaleController.getString(R.string.SilentMenuDownloadsSub)),
            new Row(ROW_BOT_TESTER, LocaleController.getString(R.string.SilentBotTesterTitle), LocaleController.getString(R.string.SilentMenuBotTesterSub)),
            new Row(ROW_DELETED_CHATS, LocaleController.getString(R.string.SilentDeletedChatsTitle), LocaleController.getString(R.string.SilentMenuDeletedChatsSub)),
            new Row(ROW_HISTORY, LocaleController.getString(R.string.SilentHistoryTitle), LocaleController.getString(R.string.SilentMenuHistorySub)),
            new Row(ROW_SCHEDULED, LocaleController.getString(R.string.SilentScheduledTitle), LocaleController.getString(R.string.SilentMenuScheduledSub)),
            new Row(ROW_CHAT_LOCK, LocaleController.getString(R.string.SilentChatLockTitle), LocaleController.getString(R.string.SilentMenuChatLockSub)),
            new Row(ROW_AUTODL, LocaleController.getString(R.string.SilentAutoDownloadTitle), LocaleController.getString(R.string.SilentMenuAutoDownloadSub)),
            new Row(ROW_NOTES, LocaleController.getString(R.string.SilentNotesTitle), LocaleController.getString(R.string.SilentMenuNotesSub)),
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("SilentGram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        TextView header = new TextView(context);
        header.setText(LocaleController.getString(R.string.SilentMenuHeader));
        header.setTextSize(13);
        header.setGravity(Gravity.CENTER);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        header.setPadding(0, org.telegram.messenger.AndroidUtilities.dp(16), 0, org.telegram.messenger.AndroidUtilities.dp(8));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public int getItemCount() {
                return rows.length + 1; // +1 for the header
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getAdapterPosition() > 0;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                if (viewType == 0) {
                    return new RecyclerListView.Holder(header);
                }
                TextCell cell = new TextCell(context);
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public int getItemViewType(int position) {
                return position == 0 ? 0 : 1;
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                if (position == 0) {
                    return;
                }
                Row row = rows[position - 1];
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndValue(row.title, row.subtitle, true);
                cell.setOnClickListener(v -> openRow(row.id));
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void openRow(int id) {
        switch (id) {
            case ROW_DEVELOPER:
                presentFragment(new SilentDeveloperActivity());
                break;
            case ROW_TEMPLATES:
                presentFragment(new SilentTemplatesActivity());
                break;
            case ROW_DOWNLOADS:
                presentFragment(new SilentDownloadManagerActivity());
                break;
            case ROW_BOT_TESTER:
                presentFragment(new SilentBotTesterActivity());
                break;
            case ROW_DELETED_CHATS:
                presentFragment(new SilentDeletedChatsActivity());
                break;
            case ROW_HISTORY:
                presentFragment(new SilentAllHistoryActivity());
                break;
            case ROW_SCHEDULED:
                presentFragment(new SilentScheduledActivity());
                break;
            case ROW_CHAT_LOCK:
                presentFragment(new SilentChatLockActivity());
                break;
            case ROW_AUTODL:
                presentFragment(new SilentAutoDownloadActivity());
                break;
            case ROW_NOTES:
                presentFragment(new SilentNotesActivity());
                break;
        }
    }
}
