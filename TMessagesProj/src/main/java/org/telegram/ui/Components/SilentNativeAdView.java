package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SilentAdsManager;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

/**
 * SilentGram v2.0.28: the AdMob Native Advanced card.
 *
 * Style follows the Plus Messenger ad the user screenshotted: a small
 * dark rounded-rectangle card — app icon on the left, title plus a
 * gold star-rating line, an INSTALL-style call-to-action button, a
 * small "Ad" badge and an X close button. It is a NativeAdView, so
 * the SDK tracks impressions/clicks on the registered asset views.
 * The "Ad" badge is mandatory (AdMob policy: the ad must be clearly
 * identifiable, never disguised as a message).
 *
 * Two modes:
 * - MODE_CARD: channel list row (below the last post, between posts).
 * - MODE_STRIP: compact full-width strip for bot chats, just below
 *   the top bar (small patti, poori patti).
 *
 * The close X hides the ad for the current chat open (owner decides
 * what "hide" means: the adapter collapses the row, the bot strip
 * hides itself). It never reports a click to AdMob.
 */
public class SilentNativeAdView extends NativeAdView {

    public static final int MODE_CARD = 0;
    public static final int MODE_STRIP = 1;

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private final int mode;
    private final Theme.ResourcesProvider resourcesProvider;

    private View cardContainer;
    private ImageView iconView;
    private TextView headlineView;
    private TextView ratingView;
    private TextView bodyView;
    private TextView ctaView;
    private TextView adBadgeView;
    private ImageView closeView;

    private NativeAd boundAd;
    private String placement = "";
    private String dialogLabel = "";
    private Runnable onCloseListener;

    public SilentNativeAdView(Context context, int mode, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.mode = mode;
        this.resourcesProvider = resourcesProvider;
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (mode == MODE_STRIP) {
            buildStrip(context);
        } else {
            buildCard(context);
        }
        applyColors();
    }

    public void setPlacement(String placement, String dialogLabel) {
        this.placement = placement == null ? "" : placement;
        this.dialogLabel = dialogLabel == null ? "" : dialogLabel;
    }

    public void setOnCloseListener(Runnable listener) {
        onCloseListener = listener;
    }

    public NativeAd getBoundAd() {
        return boundAd;
    }

    // ---- layout ----

    private void buildCard(Context context) {
        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
        // Side margins like a message bubble; the holder is full width.
        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.leftMargin = dp(8);
        cardLp.rightMargin = dp(8);
        cardLp.topMargin = dp(4);
        cardLp.bottomMargin = dp(4);
        card.setLayoutParams(cardLp);
        cardContainer = card;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconView.setClipToOutline(true);
        iconView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        topRow.addView(iconView, LayoutHelper.createLinear(dp(48), dp(48), 0, 0, dp(10), 0));

        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(LinearLayout.VERTICAL);
        topRow.addView(middle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        headlineView = new TextView(context);
        headlineView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        headlineView.setTypeface(Typeface.DEFAULT_BOLD);
        headlineView.setSingleLine(true);
        headlineView.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(headlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ratingView = new TextView(context);
        ratingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        middle.addView(ratingView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2, 0));

        closeView = new ImageView(context);
        closeView.setImageResource(R.drawable.msg_cancel);
        closeView.setScaleType(ImageView.ScaleType.CENTER);
        closeView.setPadding(dp(4), dp(4), dp(4), dp(4));
        closeView.setOnClickListener(v -> {
            if (onCloseListener != null) {
                onCloseListener.run();
            }
        });
        topRow.addView(closeView, LayoutHelper.createLinear(dp(28), dp(28)));

        bodyView = new TextView(context);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        bodyView.setMaxLines(2);
        bodyView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6, 0));

        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(bottomRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        adBadgeView = new TextView(context);
        adBadgeView.setText("Ad");
        adBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        adBadgeView.setPadding(dp(6), dp(2), dp(6), dp(2));
        bottomRow.addView(adBadgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        View spacer = new View(context);
        bottomRow.addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

        ctaView = new TextView(context);
        ctaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        ctaView.setTypeface(Typeface.DEFAULT_BOLD);
        ctaView.setPadding(dp(8), dp(4), dp(8), dp(4));
        bottomRow.addView(ctaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        addView(card);
    }

    private void buildStrip(Context context) {
        FrameLayout strip = new FrameLayout(context);
        LayoutParams stripLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        stripLp.topMargin = dp(2);
        stripLp.bottomMargin = dp(2);
        strip.setLayoutParams(stripLp);
        cardContainer = strip;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        strip.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconView.setClipToOutline(true);
        iconView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(6));
            }
        });
        row.addView(iconView, LayoutHelper.createLinear(dp(36), dp(36), 0, 0, dp(10), 0));

        headlineView = new TextView(context);
        headlineView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        headlineView.setTypeface(Typeface.DEFAULT_BOLD);
        headlineView.setSingleLine(true);
        headlineView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(headlineView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ctaView = new TextView(context);
        ctaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        ctaView.setTypeface(Typeface.DEFAULT_BOLD);
        ctaView.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.addView(ctaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, dp(8), 0, 0));

        adBadgeView = new TextView(context);
        adBadgeView.setText("Ad");
        adBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        adBadgeView.setPadding(dp(5), dp(2), dp(5), dp(2));
        row.addView(adBadgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, dp(6), 0, 0));

        closeView = new ImageView(context);
        closeView.setImageResource(R.drawable.msg_cancel);
        closeView.setScaleType(ImageView.ScaleType.CENTER);
        closeView.setPadding(dp(2), dp(2), dp(2), dp(2));
        closeView.setOnClickListener(v -> {
            if (onCloseListener != null) {
                onCloseListener.run();
            }
        });
        row.addView(closeView, LayoutHelper.createLinear(dp(24), dp(24)));

        // Strip has no body / rating line.
        bodyView = null;
        ratingView = null;

        addView(strip);
    }

    /** Re-apply theme colors (called on build and on every bind, so a theme change refreshes the card). */
    private void applyColors() {
        int bg = Theme.getColor(Theme.key_chat_inBubble, resourcesProvider);
        int text = Theme.getColor(Theme.key_chat_messageTextIn, resourcesProvider);
        int link = Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider);
        if (cardContainer != null) {
            cardContainer.setBackground(Theme.createRoundRectDrawable(dp(12), bg));
        }
        if (headlineView != null) {
            headlineView.setTextColor(text);
        }
        if (bodyView != null) {
            bodyView.setTextColor(Theme.multAlpha(text, 0.85f));
        }
        if (ratingView != null) {
            ratingView.setTextColor(0xFFFFB300);
        }
        if (ctaView != null) {
            ctaView.setTextColor(link);
        }
        if (adBadgeView != null) {
            adBadgeView.setTextColor(Theme.multAlpha(text, 0.7f));
            adBadgeView.setBackground(Theme.createRoundRectDrawable(dp(4), Theme.multAlpha(text, 0.12f)));
        }
        if (closeView != null) {
            closeView.setColorFilter(Theme.multAlpha(text, 0.6f));
        }
    }

    // ---- binding ----

    /**
     * Bind a loaded NativeAd to this view. The SDK fills the
     * registered asset views (headline, body, icon, call-to-action)
     * from the ad; the star line is filled here because it is a
     * TextView, not a RatingBar.
     */
    public void bindAd(NativeAd ad) {
        if (ad == null) {
            unbindAd();
            return;
        }
        applyColors();
        boundAd = ad;
        setHeadlineView(headlineView);
        if (bodyView != null) {
            setBodyView(bodyView);
        }
        setIconView(iconView);
        setCallToActionView(ctaView);
        setNativeAd(ad);
        // Asset visibility: the SDK leaves an empty view when the
        // asset is missing, so hide those views ourselves.
        if (ad.getIcon() == null) {
            iconView.setVisibility(GONE);
        } else {
            iconView.setVisibility(VISIBLE);
        }
        if (bodyView != null) {
            if (ad.getBody() == null || TextUtils.isEmpty(ad.getBody())) {
                bodyView.setVisibility(GONE);
            } else {
                bodyView.setVisibility(VISIBLE);
            }
        }
        if (ratingView != null) {
            Double stars = ad.getStarRating();
            if (stars == null) {
                ratingView.setVisibility(GONE);
            } else {
                ratingView.setVisibility(VISIBLE);
                ratingView.setText(formatStars(stars));
            }
        }
        setVisibility(VISIBLE);
        SilentAdsManager.getInstance().recordShown(placement, dialogLabel);
    }

    /** Unbind the current ad (no click/impression tracking afterwards). Does not destroy the ad. */
    public void unbindAd() {
        boundAd = null;
        try {
            setNativeAd(null);
        } catch (Throwable ignored) {
        }
    }

    /** Destroy the bound ad and unbind it (call when the view is going away for good). */
    public void destroyAd() {
        if (boundAd != null) {
            try {
                boundAd.destroy();
            } catch (Throwable ignored) {
            }
            boundAd = null;
        }
        unbindAd();
    }

    private static String formatStars(double rating) {
        int filled = (int) Math.round(Math.max(0, Math.min(5, rating)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < filled ? '\u2605' : '\u2606');
        }
        sb.append(String.format(Locale.US, " (%.1f/5.0)", rating));
        return sb.toString();
    }
}
