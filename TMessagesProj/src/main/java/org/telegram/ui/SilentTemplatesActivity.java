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
 * SilentGram E4: Quick reply templates — Settings page.
 * Add / edit / delete templates. Each template is a slash trigger
 * (e.g. "mywork") + the full text that gets inserted when you type
 * "/mywork" in any chat input.
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
                    showEditDialog(null);
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

    /** Add a new template, or edit an existing one (null = new). */
    private void showEditDialog(SilentTemplates.Template existing) {
        Context context = getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        final EditText triggerField = new EditText(context);
        triggerField.setHint(LocaleController.getString(R.string.SilentTemplateTriggerHint));
        triggerField.setText(existing != null ? existing.trigger : "");
        triggerField.setSingleLine(true);
        layout.addView(triggerField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final EditText textField = new EditText(context);
        textField.setHint(LocaleController.getString(R.string.SilentTemplateTextHint));
        textField.setText(existing != null ? existing.text : "");
        textField.setMinLines(4);
        textField.setGravity(Gravity.TOP);
        layout.addView(textField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 16, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(existing != null ? LocaleController.getString(R.string.SilentTemplateEdit) : LocaleController.getString(R.string.SilentTemplateAdd));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
            String trigger = triggerField.getText().toString().trim().replaceAll("^/+", "");
            String text = textField.getText().toString().trim();
            if (!trigger.isEmpty() && !text.isEmpty()) {
                SilentTemplates.addTemplate(trigger, text);
                reload();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                SilentTemplates.deleteTemplate(existing.trigger);
                reload();
            });
        }
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
            cell.setTextAndValue("/" + t.trigger, preview, true);
            cell.setOnClickListener(v -> showEditDialog(t));
        }
    }
}
