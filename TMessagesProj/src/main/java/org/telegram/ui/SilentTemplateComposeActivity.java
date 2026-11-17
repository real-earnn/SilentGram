package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentTemplates;
import org.telegram.messenger.SilentToast;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.InputStream;

/**
 * SilentGram K13: full chat-like compose screen for a Quick Reply Template.
 *
 * Flow: the trigger is asked FIRST (in the templates list), then this
 * screen opens — media preview on top, message box at the bottom with an
 * attach (paperclip) button. Templates store media (photo / video / file)
 * as a copy in the app-private silent_templates dir, not just text.
 * Tapping a saved template opens this same screen prefilled (edit the
 * post AND the trigger).
 */
public class SilentTemplateComposeActivity extends BaseFragment {

    private static final int REQUEST_PICK_MEDIA = 1001;

    private final String editTrigger;
    private final String oldMediaFile;

    private EditText triggerField;
    private EditText messageField;
    private FrameLayout previewContainer;
    private TextView removeMediaView;

    // Pending media state (store file name, or "" when none).
    private String pendingMediaType = "";
    private String pendingMediaFile = "";
    private String pendingMediaName = "";
    private String pendingMediaMime = "";
    private String pendingMediaCache = "";

    public SilentTemplateComposeActivity(Bundle args) {
        super(args);
        editTrigger = args.getString("edit_trigger");
        oldMediaFile = args.getString("media_file", "");
        pendingMediaType = args.getString("media_type", "");
        pendingMediaFile = args.getString("media_file", "");
        pendingMediaName = args.getString("media_name", "");
        pendingMediaMime = args.getString("media_mime", "");
        pendingMediaCache = args.getString("media_cache", "");
    }

    /** New template: trigger already collected by the list screen. */
    public static SilentTemplateComposeActivity createForNew(String trigger) {
        Bundle args = new Bundle();
        args.putString("trigger", trigger == null ? "" : trigger);
        return new SilentTemplateComposeActivity(args);
    }

    /** Edit an existing template (post + trigger). */
    public static SilentTemplateComposeActivity createForEdit(SilentTemplates.Template t) {
        Bundle args = new Bundle();
        args.putString("trigger", t.trigger);
        args.putString("edit_trigger", t.trigger);
        args.putString("text", t.text);
        args.putString("media_type", t.mediaType);
        args.putString("media_file", t.mediaFile);
        args.putString("media_name", t.mediaName);
        args.putString("media_mime", t.mediaMime);
        args.putString("media_cache", t.mediaCache);
        return new SilentTemplateComposeActivity(args);
    }

    @Override
    public View createView(Context context) {
        boolean editing = editTrigger != null && !editTrigger.isEmpty();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(editing ? R.string.SilentTemplateComposeEdit : R.string.SilentTemplateComposeNew));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 2) {
                    deleteTemplate();
                }
            }
        });
        if (editing) {
            ActionBarMenu menu = actionBar.createMenu();
            menu.addItem(2, R.drawable.msg_delete);
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        ScrollView scrollView = new ScrollView(context);
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        triggerField = new EditText(context);
        triggerField.setHint(LocaleController.getString(R.string.SilentTemplateTriggerHint));
        triggerField.setText(getArguments().getString("trigger", ""));
        triggerField.setSingleLine(true);
        triggerField.setTextSize(16);
        triggerField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(triggerField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        previewContainer = new FrameLayout(context);
        content.addView(previewContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        removeMediaView = new TextView(context);
        removeMediaView.setText(LocaleController.getString(R.string.SilentTemplateRemoveMedia));
        removeMediaView.setTextSize(14);
        removeMediaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        removeMediaView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        removeMediaView.setOnClickListener(v -> {
            pendingMediaType = "";
            pendingMediaFile = "";
            pendingMediaName = "";
            pendingMediaMime = "";
            pendingMediaCache = "";
            refreshPreview();
        });
        content.addView(removeMediaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // Bottom bar, chat-like: attach button + message box + Save.
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.BOTTOM);
        bottomBar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        bottomBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(bottomBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView attachButton = new ImageView(context);
        attachButton.setImageResource(R.drawable.msg_input_attach2);
        attachButton.setScaleType(ImageView.ScaleType.CENTER);
        attachButton.setContentDescription(LocaleController.getString(R.string.AccDescrAttachButton));
        attachButton.setOnClickListener(v -> pickMedia());
        bottomBar.addView(attachButton, LayoutHelper.createLinear(AndroidUtilities.dp(44), AndroidUtilities.dp(44)));

        messageField = new EditText(context);
        messageField.setHint(LocaleController.getString(R.string.SilentTemplateTextHint));
        messageField.setText(getArguments().getString("text", ""));
        messageField.setMinLines(3);
        messageField.setMaxLines(6);
        messageField.setGravity(Gravity.TOP);
        messageField.setTextSize(16);
        messageField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bottomBar.addView(messageField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, 4, 0, 4, 0));

        TextView saveButton = new TextView(context);
        saveButton.setText(LocaleController.getString(R.string.Save));
        saveButton.setTextSize(16);
        saveButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        saveButton.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
        saveButton.setOnClickListener(v -> saveTemplate());
        bottomBar.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        refreshPreview();
        return fragmentView;
    }

    private void refreshPreview() {
        if (previewContainer == null) {
            return;
        }
        Context context = getParentActivity();
        previewContainer.removeAllViews();
        boolean hasMedia = pendingMediaFile != null && !pendingMediaFile.isEmpty()
                || pendingMediaCache != null && !pendingMediaCache.isEmpty();
        if (removeMediaView != null) {
            removeMediaView.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        }
        if (!hasMedia) {
            TextView hint = new TextView(context);
            hint.setText(LocaleController.getString(R.string.SilentTemplateNoMedia));
            hint.setTextSize(14);
            hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            previewContainer.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }
        if (SilentTemplates.MEDIA_PHOTO.equals(pendingMediaType)) {
            File file = pendingMediaFile != null && !pendingMediaFile.isEmpty()
                    ? new File(SilentTemplates.getMediaDir(context), pendingMediaFile) : null;
            if (file != null && file.exists()) {
                Bitmap bitmap = decodeSampledBitmap(file.getAbsolutePath(), 1024);
                if (bitmap != null) {
                    ImageView image = new ImageView(context);
                    image.setImageBitmap(bitmap);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setAdjustViewBounds(true);
                    previewContainer.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(220)));
                    return;
                }
            }
            // Photo not on disk (still downloading) — fall through to the file row.
        }
        TextView row = new TextView(context);
        String label = SilentTemplates.MEDIA_VIDEO.equals(pendingMediaType)
                ? LocaleController.getString(R.string.SilentTemplateVideo)
                : SilentTemplates.MEDIA_PHOTO.equals(pendingMediaType)
                        ? LocaleController.getString(R.string.SilentTemplatePhoto)
                        : LocaleController.getString(R.string.SilentTemplateFile);
        String name = pendingMediaName != null && !pendingMediaName.isEmpty() ? pendingMediaName : label;
        String info = label + " · " + name;
        if (pendingMediaFile != null && !pendingMediaFile.isEmpty()) {
            File file = new File(SilentTemplates.getMediaDir(context), pendingMediaFile);
            if (file.exists()) {
                info += " · " + AndroidUtilities.formatFileSize(file.length());
            }
        } else if (pendingMediaCache != null && !pendingMediaCache.isEmpty()) {
            info += " · " + LocaleController.getString(R.string.SilentTemplateMediaPending);
        }
        row.setText(info);
        row.setTextSize(15);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        previewContainer.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    /** Downsampled bitmap so a big picked photo cannot OOM the preview. */
    private static Bitmap decodeSampledBitmap(String path, int maxSize) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(opts.outWidth, opts.outHeight) / sample > maxSize) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private void pickMedia() {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_PICK_MEDIA);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_MEDIA || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null || getParentActivity() == null) {
            return;
        }
        Uri uri = data.getData();
        Context context = getParentActivity();
        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        String displayName = queryDisplayName(context, uri);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            File stored = SilentTemplates.copyStreamIntoStore(context, in, displayName);
            if (stored == null) {
                return;
            }
            if (mime != null && mime.startsWith("image/") && !"image/gif".equals(mime)) {
                pendingMediaType = SilentTemplates.MEDIA_PHOTO;
            } else if (mime != null && mime.startsWith("video/")) {
                pendingMediaType = SilentTemplates.MEDIA_VIDEO;
            } else {
                pendingMediaType = SilentTemplates.MEDIA_DOCUMENT;
            }
            pendingMediaFile = stored.getName();
            pendingMediaName = displayName;
            pendingMediaMime = mime == null ? "" : mime;
            pendingMediaCache = "";
            refreshPreview();
        } catch (Exception e) {
            // Picker read failed — keep the previous state.
        }
    }

    private static String queryDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? "media" : path;
    }

    private void saveTemplate() {
        if (triggerField == null || messageField == null) {
            return;
        }
        Context context = getParentActivity();
        String trigger = triggerField.getText().toString().trim().replaceAll("^/+", "");
        String text = messageField.getText().toString().trim();
        if (trigger.isEmpty()) {
            SilentToast.show(context, LocaleController.getString(R.string.SilentTemplateNeedTrigger), Toast.LENGTH_SHORT);
            return;
        }
        boolean hasMedia = pendingMediaFile != null && !pendingMediaFile.isEmpty()
                || pendingMediaCache != null && !pendingMediaCache.isEmpty();
        if (text.isEmpty() && !hasMedia) {
            SilentToast.show(context, LocaleController.getString(R.string.SilentTemplateNeedContent), Toast.LENGTH_SHORT);
            return;
        }
        SilentTemplates.addTemplate(trigger, text, pendingMediaType, pendingMediaFile,
                pendingMediaName, pendingMediaMime, pendingMediaCache);
        if (editTrigger != null && !editTrigger.isEmpty() && !editTrigger.equalsIgnoreCase(trigger)) {
            // Renamed: drop the old trigger. addTemplate ran first, so a
            // shared media file is still referenced and survives the delete.
            SilentTemplates.deleteTemplate(editTrigger);
        }
        if (oldMediaFile != null && !oldMediaFile.isEmpty() && !oldMediaFile.equals(pendingMediaFile)) {
            // The old stored copy is no longer used by any template — clean it up.
            boolean stillUsed = false;
            for (SilentTemplates.Template t : SilentTemplates.getTemplates()) {
                if (oldMediaFile.equals(t.mediaFile)) {
                    stillUsed = true;
                    break;
                }
            }
            if (!stillUsed && context != null) {
                File old = new File(SilentTemplates.getMediaDir(context), oldMediaFile);
                if (old.exists()) {
                    old.delete();
                }
            }
        }
        SilentToast.show(context, LocaleController.getString(R.string.SilentTemplateSaved), Toast.LENGTH_SHORT);
        finishFragment();
    }

    private void deleteTemplate() {
        if (editTrigger != null && !editTrigger.isEmpty()) {
            SilentTemplates.deleteTemplate(editTrigger);
        }
        finishFragment();
    }
}
