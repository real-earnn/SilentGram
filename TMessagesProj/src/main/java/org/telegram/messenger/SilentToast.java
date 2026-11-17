package org.telegram.messenger;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.Theme;

/**
 * SilentGram K9: ONE themed-toast helper for all SilentGram custom toasts.
 *
 * Android's stock toast is a plain white bubble whatever the app theme is,
 * which is what the user complained about ("basic white"). This helper builds
 * the toast view itself so the colours follow the current app theme: the
 * background is Theme.key_dialogBackground and the text is
 * Theme.key_dialogTextBlack — dark in a dark theme, light in a light theme —
 * instead of a hardcoded white/black pair.
 *
 * Platform note (verified against the Android Toast docs): custom toast
 * views are deprecated since API 30, but they are only BLOCKED for apps in
 * the background — a foreground app may still show one (getView() on a
 * stock makeText toast, by contrast, returns null when targeting API 30+,
 * so styling the stock view is not an option). Every call site here is
 * foreground tap feedback, and show() still falls back to a stock
 * Toast.makeText if the platform ever rejects the custom view, so a tap
 * handler can never crash on a toast.
 *
 * Scope: only SilentGram's own toasts use this. Telegram's upstream
 * Bulletin / toast usages are untouched.
 */
public class SilentToast {

    /** Short themed toast (Toast.LENGTH_SHORT). */
    public static void show(Context context, CharSequence text) {
        show(context, text, Toast.LENGTH_SHORT);
    }

    /** Themed toast with an explicit duration (Toast.LENGTH_SHORT/LONG). */
    @SuppressWarnings("deprecation")
    public static void show(Context context, CharSequence text, int duration) {
        if (context == null || text == null) {
            return;
        }
        Context toastContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            TextView view = new TextView(toastContext);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            view.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), Theme.getColor(Theme.key_dialogBackground)));
            int padH = AndroidUtilities.dp(16);
            int padV = AndroidUtilities.dp(10);
            view.setPadding(padH, padV, padH, padV);
            view.setGravity(Gravity.CENTER);
            Toast toast = new Toast(toastContext);
            toast.setView(view);
            toast.setDuration(duration);
            toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, AndroidUtilities.dp(64));
            toast.show();
        } catch (Exception e) {
            // Platform refused the custom view (e.g. a future Android
            // restriction) — never let a toast break the tap that asked
            // for it; fall back to the stock toast.
            FileLog.e(e);
            Toast.makeText(toastContext, text, duration).show();
        }
    }
}
