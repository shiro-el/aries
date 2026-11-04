package android.webkit;

/** WebResourceError's constructor is package-private in the Android SDK. */
public final class PreviewWebResourceError extends WebResourceError {
    @Override public int getErrorCode() { return WebViewClient.ERROR_BAD_URL; }
    @Override public CharSequence getDescription() { return "net::ERR_INVALID_HTTP_RESPONSE"; }
}
