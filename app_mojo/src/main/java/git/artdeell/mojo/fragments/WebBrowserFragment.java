package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

/**
 * A browser of our own so a page never takes the player out of the launcher.
 * Everything it shows stays inside this screen, the close button walks back out.
 */
public class WebBrowserFragment extends Fragment {

    public static final String TAG = "WebBrowserFragment";
    private static final String ARG_URL = "url";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private TextView mErrorView;
    private TextView mUrlView;
    private String mUrl;

    public static Bundle argsFor(String url) {
        Bundle bundle = new Bundle();
        bundle.putString(ARG_URL, url);
        return bundle;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_web_browser, container, false);
        mUrl = requireArguments().getString(ARG_URL, "");
        mWebView = view.findViewById(R.id.web_browser_view);
        mProgressBar = view.findViewById(R.id.web_browser_progress);
        mErrorView = view.findViewById(R.id.web_browser_error);
        mUrlView = view.findViewById(R.id.web_browser_url);
        view.findViewById(R.id.web_browser_close).setOnClickListener(v ->
                Tools.removeCurrentFragment(requireActivity()));
        // Tapping the failure message tries the page once more
        mErrorView.setOnClickListener(v -> {
            mErrorView.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.VISIBLE);
            mWebView.setVisibility(View.VISIBLE);
            mWebView.reload();
        });
        setupWebView();
        mUrlView.setText(mUrl);
        mProgressBar.setVisibility(View.VISIBLE);
        mWebView.loadUrl(mUrl);
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setDomStorageEnabled(true);
        mWebView.getSettings().setSupportZoom(true);
        mWebView.getSettings().setBuiltInZoomControls(true);
        mWebView.getSettings().setDisplayZoomControls(false);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mUrlView.setText(url);
                mProgressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mUrlView.setText(url);
                if (mErrorView.getVisibility() != View.VISIBLE) {
                    mProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                mWebView.setVisibility(View.INVISIBLE);
                mProgressBar.setVisibility(View.GONE);
                mErrorView.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        mWebView.stopLoading();
        mWebView.setWebViewClient(null);
        mWebView.destroy();
        mWebView = null;
        super.onDestroyView();
    }
}
