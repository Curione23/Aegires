package a.b.screen;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import a.b.MainActivity;
import a.b.util.TokenStore;
import a.b.util.Ui;

/**
 * In-app Discord web login. Loads Discord's web client in a WebView and captures the user token
 * once the web client starts making authenticated API requests.
 *
 * <p>This is token capture, not Discord OAuth2: the captured value is the same kind of user token
 * that can be pasted into {@link LoginScreen}.
 */
public class WebLoginScreen implements Screen {
    private static final String LOGIN_URL = "https://discord.com/login";
    private static final String BRIDGE_NAME = "AegiresTokenBridge";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String CAPTURE_JS =
            "(function(){"
                    + "function clean(t){"
                    + "if(!t)return null;"
                    + "t=String(t).trim();"
                    + "if(t.length>1&&t.charAt(0)=='\"'&&t.charAt(t.length-1)=='\"')"
                    + "t=t.substring(1,t.length-1).trim();"
                    + "if(t.length<40||t.indexOf(' ')>=0)return null;"
                    + "var d=0;for(var i=0;i<t.length;i++){if(t.charAt(i)=='.')d++;}"
                    + "return d>=2?t:null;"
                    + "}"
                    + "function report(t){"
                    + "t=clean(t);"
                    + "if(!t)return false;"
                    + "if(window."
                    + BRIDGE_NAME
                    + "&&window."
                    + BRIDGE_NAME
                    + ".onToken){"
                    + "window."
                    + BRIDGE_NAME
                    + ".onToken(t);"
                    + "return true;"
                    + "}"
                    + "return false;"
                    + "}"
                    + "function check(){"
                    + "try{"
                    + "if(report(localStorage.getItem('token')))return;"
                    + "for(var i=0;i<localStorage.length;i++){"
                    + "var k=localStorage.key(i);"
                    + "if(k&&k.toLowerCase().indexOf('token')>=0"
                    + "&&report(localStorage.getItem(k)))return;"
                    + "}"
                    + "}catch(e){}"
                    + "}"
                    + "check();"
                    + "if(!window.__aegiresPoll){window.__aegiresPoll=setInterval(check,1000);}"
                    + "})();";

    private MainActivity activity;
    private View view;
    private WebView webView;
    private ProgressBar progress;
    private View errorView;
    private final AtomicBoolean captured = new AtomicBoolean(false);

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        Ui.AppBar appBar =
                new Ui.AppBar(
                        activity,
                        "Log in with Discord",
                        Ui.BG_SECONDARY,
                        () -> {
                            if (webView != null && webView.canGoBack()) webView.goBack();
                            else activity.goBack();
                        });
        appBar.addAction(Ui.I_CLOSE, v -> activity.goBack());
        root.addView(appBar.view, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout webContainer = new FrameLayout(activity);
        root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1f));

        webView = new WebView(activity);
        webContainer.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.BLURPLE));
        webContainer.addView(
                progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));

        errorView = buildErrorView(activity);
        errorView.setVisibility(View.GONE);
        webContainer.addView(errorView, new FrameLayout.LayoutParams(-1, -1));

        TextView fallback =
                Ui.text(activity, "Trouble signing in? Paste a token instead", Ui.MUTED, 13);
        fallback.setGravity(Gravity.CENTER);
        fallback.setBackgroundColor(Ui.BG_SECONDARY);
        fallback.setClickable(true);
        fallback.setPadding(dp(12), 0, dp(12), 0);
        fallback.setOnClickListener(v -> activity.goBack());
        root.addView(fallback, new LinearLayout.LayoutParams(-1, dp(44)));

        configureWebView();
        webView.loadUrl(LOGIN_URL);

        return view = root;
    }

    private View buildErrorView(final MainActivity activity) {
        LinearLayout error = new LinearLayout(activity);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(Ui.BG);
        error.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView message =
                Ui.text(
                        activity,
                        "Couldn't load Discord.\nCheck your connection and try again.",
                        Ui.MUTED,
                        14);
        message.setGravity(Gravity.CENTER);
        error.addView(message, new LinearLayout.LayoutParams(-1, -2));

        TextView retry = Ui.primaryButton(activity, "Retry");
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(dp(160), dp(44));
        retryLp.topMargin = dp(20);
        retryLp.gravity = Gravity.CENTER_HORIZONTAL;
        error.addView(retry, retryLp);
        retry.setOnClickListener(
                v -> {
                    errorView.setVisibility(View.GONE);
                    progress.setVisibility(View.VISIBLE);
                    webView.loadUrl(LOGIN_URL);
                });
        return error;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(DESKTOP_UA);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new TokenBridge(), BRIDGE_NAME);
        webView.setWebViewClient(new LoginWebViewClient());
        webView.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public void onProgressChanged(WebView view, int newProgress) {
                        progress.setProgress(newProgress);
                        progress.setVisibility(
                                newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
                    }
                });
    }

    private final class LoginWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(
                WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (errorView != null) errorView.setVisibility(View.GONE);
            if (progress != null) progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (progress != null) progress.setVisibility(View.INVISIBLE);
            injectCaptureScript();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return false;
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
            captureFromRequest(request);
            return null;
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request != null && request.isForMainFrame() && errorView != null) {
                progress.setVisibility(View.INVISIBLE);
                errorView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void injectCaptureScript() {
        if (webView == null) return;
        try {
            webView.evaluateJavascript(CAPTURE_JS, null);
        } catch (Exception ignored) {
        }
    }

    private void captureFromRequest(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return;
        Uri uri = request.getUrl();
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null || !path.startsWith("/api/")) return;
        if (!isDiscordHost(host)) return;

        Map<String, String> headers;
        try {
            headers = request.getRequestHeaders();
        } catch (Exception e) {
            return;
        }
        if (headers == null) return;

        String auth = headers.get("Authorization");
        if (auth == null) auth = headers.get("authorization");
        if (auth == null) return;

        auth = auth.trim();
        if (auth.regionMatches(true, 0, "Bot ", 0, 4)) auth = auth.substring(4).trim();
        else if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) auth = auth.substring(7).trim();

        final String candidate = auth;
        if (!isLikelyToken(candidate) || activity == null) return;
        activity.runOnUiThread(() -> handleToken(candidate));
    }

    private final class TokenBridge {
        @JavascriptInterface
        public void onToken(final String token) {
            if (activity == null) return;
            activity.runOnUiThread(() -> handleToken(token));
        }
    }

    private void handleToken(String raw) {
        if (activity == null) return;
        if (!captured.compareAndSet(false, true)) return;

        String token = cleanToken(raw);
        if (token == null) {
            captured.set(false);
            return;
        }
        if (webView != null && !isDiscordUrl(webView.getUrl())) {
            captured.set(false);
            return;
        }

        TokenStore.save(activity, token);
        clearWebSession();
        activity.setToken(token);
        activity.resetTo(new HomeScreen(activity));
    }

    private void clearWebSession() {
        try {
            WebStorage.getInstance().deleteAllData();
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(value -> cookies.flush());
        } catch (Exception ignored) {
        }
    }

    private static String cleanToken(String raw) {
        if (raw == null) return null;
        String token = raw.trim();
        if (token.length() > 1
                && token.charAt(0) == '"'
                && token.charAt(token.length() - 1) == '"') {
            token = token.substring(1, token.length() - 1).trim();
        }
        return isLikelyToken(token) ? token : null;
    }

    private static boolean isLikelyToken(String token) {
        if (token == null) return false;
        if (token.length() < 40 || token.length() > 512) return false;
        if (token.indexOf(' ') >= 0
                || token.indexOf('\n') >= 0
                || token.indexOf('\r') >= 0
                || token.indexOf('\t') >= 0) {
            return false;
        }
        if (token.regionMatches(true, 0, "Bot ", 0, 4)) return false;
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '.') dots++;
        }
        return dots >= 2;
    }

    private static boolean isDiscordUrl(String url) {
        if (url == null) return false;
        try {
            return isDiscordHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDiscordHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return host.equals("discord.com")
                || host.endsWith(".discord.com")
                || host.equals("discordapp.com")
                || host.endsWith(".discordapp.com");
    }

    private int dp(int v) {
        return Ui.dp(activity, v);
    }

    @Override
    public void onResume() {
        if (webView != null) webView.onResume();
    }

    @Override
    public void onPause() {
        if (webView != null) webView.onPause();
    }

    @Override
    public void onDestroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface(BRIDGE_NAME);
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        activity = null;
        view = null;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override
    public View getView() {
        return view;
    }
}
