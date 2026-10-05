package com.limelight;

import android.app.AlertDialog;
import android.net.Uri;
import android.net.http.SslError;
import android.text.InputType;
import android.webkit.HttpAuthHandler;
import android.webkit.WebBackForwardList;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.limelight.utils.Dialog;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import android.app.Activity;

import com.limelight.utils.SpinnerDialog;

public class HelpActivity extends Activity {
    /** Boolean extra: the page belongs to the user's own PC, so its self-signed certificate is accepted. */
    public static final String EXTRA_TRUST_HOST = "trustHost";


    private SpinnerDialog loadingDialog;
    private WebView webView;
    private String trustedHost;

    private boolean backCallbackRegistered;
    private OnBackInvokedCallback onBackInvokedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        onBackInvokedCallback = new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                goBackOrClose();
            }
        };

        webView = new WebView(this);
        setContentView(webView);

        // These allow the user to zoom the page
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        // This sets the view to display the whole page by default
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);

        // This allows the links to places on the same page to work
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        trustedHost = getIntent().getBooleanExtra(EXTRA_TRUST_HOST, false) ? getIntent().getData().getHost() : null;

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (loadingDialog == null) {
                    loadingDialog = SpinnerDialog.displayDialog(HelpActivity.this,
                            getResources().getString(R.string.help_loading_title),
                            getResources().getString(R.string.help_loading_msg), false);
                }

                refreshBackDispatchState();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                dismissLoading();
                refreshBackDispatchState();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    dismissLoading();
                    Toast.makeText(HelpActivity.this, error.getDescription(), Toast.LENGTH_LONG).show();
                }
            }

            // Sunshine and Apollo serve their web UI with a self-signed certificate: accept it for the PC we
            // were sent to, never for anything else
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                String host = Uri.parse(error.getUrl()).getHost();
                if (trustedHost != null && trustedHost.equals(host)) {
                    handler.proceed();
                }
                else {
                    handler.cancel();
                    dismissLoading();
                    Toast.makeText(HelpActivity.this, R.string.help_ssl_error, Toast.LENGTH_LONG).show();
                }
            }

            // The web UI asks for its username and password with HTTP basic auth
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                dismissLoading();
                float density = getResources().getDisplayMetrics().density;
                LinearLayout layout = new LinearLayout(HelpActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = Math.round(20 * density);
                layout.setPadding(pad, Math.round(8 * density), pad, 0);
                EditText user = new EditText(HelpActivity.this);
                user.setHint(R.string.help_auth_username);
                user.setSingleLine(true);
                user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                EditText password = new EditText(HelpActivity.this);
                password.setHint(R.string.help_auth_password);
                password.setSingleLine(true);
                password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                layout.addView(user);
                layout.addView(password);
                AlertDialog dialog = new AlertDialog.Builder(HelpActivity.this)
                        .setTitle(getString(R.string.help_auth_title, host))
                        .setView(layout)
                        .setPositiveButton(android.R.string.ok, (d, which) -> handler.proceed(user.getText().toString(), password.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, which) -> handler.cancel())
                        .setOnCancelListener(d -> handler.cancel())
                        .create();
                dialog.setOnShowListener(d -> user.requestFocus());
                dialog.show();
                Dialog.compact(dialog);
            }
        });

        webView.loadUrl(getIntent().getData().toString());
    }

    private void dismissLoading() {
        if (loadingDialog != null) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    /**
     * Back on a TV remote must always do something visible. The PC's web UI is a single-page app whose
     * history is full of entries for the same address, so there Back simply closes the page; a documentation
     * page steps back through its history while the previous entry is really another page, then closes.
     */
    private void goBackOrClose() {
        if (trustedHost == null && webView.canGoBack()) {
            WebBackForwardList history = webView.copyBackForwardList();
            int index = history.getCurrentIndex();
            String previous = index > 0 ? history.getItemAtIndex(index - 1).getUrl() : null;
            if (previous != null && !samePage(previous, webView.getUrl())) {
                webView.goBack();
                return;
            }
        }
        finish();
    }

    private static boolean samePage(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        int ha = a.indexOf('#');
        int hb = b.indexOf('#');
        return (ha < 0 ? a : a.substring(0, ha)).equals(hb < 0 ? b : b.substring(0, hb));
    }

    private void refreshBackDispatchState() {
        // Always ours: the decision whether to step back or close is made in goBackOrClose()
        if (!backCallbackRegistered) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBackInvokedCallback);
            backCallbackRegistered = true;
        }
    }

    @Override
    protected void onDestroy() {
        if (backCallbackRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(onBackInvokedCallback);
        }

        super.onDestroy();
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        goBackOrClose();
    }
}
