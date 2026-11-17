package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentTemplates;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * SilentGram E4 / K13: Quick reply templates — Settings page.
 * Add / edit / delete templates. Each template is a slash trigger
 * (e.g. "mywork") + the full post (text + optional media) that gets SENT
 * when you type "/mywork" in any chat input.
 *
 * Flow (K13): "+" asks for the trigger FIRST, then opens the full
 * chat-like compose screen (SilentTemplateComposeActivity). Tapping a
 * saved template opens the same screen prefilled (edit post + trigger).
 */
public class SilentTemplatesActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<SilentTemplates.Template> templates;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SilentTemplatesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    showTriggerDialog();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.menu_topic_add_30);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        templates = SilentTemplates.getTemplates();

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.SilentTemplatesEmpty));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        updateEmpty();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from the compose screen — refresh the list.
        reload();
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        boolean empty = templates == null || templates.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) {
            listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void reload() {
        templates = SilentTemplates.getTemplates();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmpty();
    }

    /**
     * K13 creation flow, step 1: ask for the trigger/shortcut FIRST.
     * Step 2 (the full chat-like compose screen) opens from the dialog.
     */
    private void showTriggerDialog() {
        Context context = getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        final EditText triggerField = new EditText(context);
        triggerField.setHint(LocaleController.getString(R.string.SilentTemplateTriggerHint));
        triggerField.setSingleLine(true);
        layout.addView(triggerField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.SilentTemplateTriggerTitle));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Next), (d, w) -> {
            String trigger = triggerField.getText().toString().trim().replaceAll("^/+", "");
            if (!trigger.isEmpty()) {
                presentFragment(SilentTemplateComposeActivity.createForNew(trigger));
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return templates == null ? 0 : templates.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SilentTemplates.Template t = templates.get(position);
            TextCell cell = (TextCell) holder.itemView;
            String preview = t.text.length() > 60 ? t.text.substring(0, 60) + "…" : t.text;
            if (t.hasMedia()) {
                String label = SilentTemplates.MEDIA_VIDEO.equals(t.mediaType)
                        ? LocaleController.getString(R.string.SilentTemplateVideo)
                        : SilentTemplates.MEDIA_PHOTO.equals(t.mediaType)
                                ? LocaleController.getString(R.string.SilentTemplatePhoto)
                                : LocaleController.getString(R.string.SilentTemplateFile);
                preview = "[" + label + "]" + (preview.isEmpty() ? "" : " " + preview);
            }
            cell.setTextAndValue("/" + t.trigger, preview, true);
            // K13 (b): tapping a saved template opens the compose screen
            // prefilled — edit the post and the trigger there.
            cell.setOnClickListener(v -> presentFragment(SilentTemplateComposeActivity.createForEdit(t)));
        }
    }
}
