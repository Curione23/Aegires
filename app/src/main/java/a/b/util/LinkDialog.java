package a.b.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** Asks before leaving the app, so a stray tap on a link doesn't open anything. */
public final class LinkDialog {
    private LinkDialog() {}

    public static void show(final Context ctx, final String url) {
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing()) return;

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Open link?")
            .setMessage(url)
            .setPositiveButton("Open", (d, w) -> open(ctx, url))
            .setNeutralButton("Copy", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", url));
                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void open(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }
}
