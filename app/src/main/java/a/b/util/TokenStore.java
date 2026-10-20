package a.b.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class TokenStore {
  private static final String PREFS = "auth";
  private static final String KEY_TOKEN = "token";

  private TokenStore() {}

  public static void save(Context ctx, String token) {
    if (ctx == null || token == null || token.length() == 0) return;
    prefs(ctx).edit().putString(KEY_TOKEN, token).apply();
  }

  public static String load(Context ctx) {
    if (ctx == null) return null;
    String t = prefs(ctx).getString(KEY_TOKEN, null);
    if (t == null || t.length() == 0) return null;
    return t;
  }

  public static void clear(Context ctx) {
    if (ctx == null) return;
    prefs(ctx).edit().remove(KEY_TOKEN).commit();
  }

  private static SharedPreferences prefs(Context ctx) {
    return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}