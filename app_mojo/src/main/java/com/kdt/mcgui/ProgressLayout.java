package com.kdt.mcgui;


import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.constraintlayout.widget.ConstraintLayout;

import git.artdeell.mojo.R;

import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.TaskCountListener;
import git.artdeell.mojo.utils.DownloadStop;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;


/** Class staring at specific values and automatically show something if the progress is present
 * Since progress is posted in a specific way, The packing/unpacking is handheld by the class
 *
 * This class relies on ExtraCore for its behavior.
 */
public class ProgressLayout extends ConstraintLayout implements View.OnClickListener, TaskCountListener{
    public static final String UNPACK_RUNTIME = "unpack_runtime";
    public static final String DOWNLOAD_GAME = "download_minecraft";
    public static final String DOWNLOAD_VERSION_LIST = "download_verlist";
    public static final String AUTHENTICATE = "authenticate";
    public static final String INSTALL_MODPACK = "install_modpack";
    public static final String EXTRACT_COMPONENTS = "extract_components";
    public static final String EXTRACT_SINGLE_FILES = "extract_single_files";
    public static final String INSTANCE_INSTALL = "instance_install";
    public static final String DATA_MIGRATION = "data_migration";
    public static final String DRIVER_INSTALL = "driver_install";

    private static final AtomicInteger sInstallKeyCounter = new AtomicInteger();

    public ProgressLayout(@NonNull Context context) {
        super(context);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private final HashMap<String, LayoutProgressListener> mRows = new HashMap<>();
    private LinearLayout mLinearLayout;
    private TextView mTaskNumberDisplayer;
    private ImageView mFlipArrow;

    /** Turns every running key into a row of its own and takes it down once the work ends. */
    private final ProgressKeeper.KeyedProgressListener mKeyedListener =
            new ProgressKeeper.KeyedProgressListener() {
        @Override
        public void onKeyStarted(String key) {
            post(() -> {
                LayoutProgressListener row = mRows.get(key);
                if(row == null) mRows.put(key, (row = new LayoutProgressListener(key)));
                row.attach();
            });
        }

        @Override
        public void onKeyUpdated(String key, int progress, int resid, Object... va) {
            post(() -> {
                LayoutProgressListener row = mRows.get(key);
                if(row != null) row.update(progress, resid, va);
            });
        }

        @Override
        public void onKeyEnded(String key) {
            post(() -> {
                LayoutProgressListener row = mRows.remove(key);
                if(row != null) row.detach();
            });
        }
    };

    /** Follow every key there is, so installs running side by side each get a row. */
    public void observeAll() {
        ProgressKeeper.addKeyedListener(mKeyedListener);
    }

    public void cleanUpObservers() {
        ProgressKeeper.removeKeyedListener(mKeyedListener);
    }

    public boolean hasProcesses(){
        return ProgressKeeper.getTaskCount() > 0;
    }


    private void init(){
        inflate(getContext(), R.layout.view_progress, this);
        mLinearLayout = findViewById(R.id.progress_linear_layout);
        mTaskNumberDisplayer = findViewById(R.id.progress_textview);
        mFlipArrow = findViewById(R.id.progress_flip_arrow);
        setBackgroundColor(getResources().getColor(R.color.background_bottom_bar));
        setOnClickListener(this);
    }

    /** A progress key of its own, so installs running side by side never share a row. */
    public static String contentInstallKey() {
        return INSTALL_MODPACK + "@" + sInstallKeyCounter.incrementAndGet();
    }

    /**
     * Whether a stop belongs on the row of the given key. Only pure downloads get one:
     * a mod, pack, shader, world or modpack being installed, or the game itself being
     * downloaded. The rest of the work unpacks and writes files on disk, and ending it
     * midway could leave what it works on half made.
     */
    public static boolean isStoppableKey(String key) {
        return DOWNLOAD_GAME.equals(key) || key.startsWith(INSTALL_MODPACK);
    }


    /** Update the progress bar content */
    public static void setProgress(String progressKey, int progress){
        ProgressKeeper.submitProgress(progressKey, progress, -1, (Object)null);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, @StringRes int resource, Object... message){
        ProgressKeeper.submitProgress(progressKey, progress, resource, message);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, String message){
        setProgress(progressKey,progress, -1, message);
    }

    /** Update the text and progress content */
    public static void clearProgress(String progressKey){
        setProgress(progressKey, -1, -1);
    }

    public static boolean ensureNoOngoingTasks(Context context) {
        if(ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    public static boolean ensureNoOngoingTasks(Context context, String key) {
        if(ProgressKeeper.hasProgressKey(key)) {
            Toast.makeText(context, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @Override
    public void onClick(View v) {
        mLinearLayout.setVisibility(mLinearLayout.getVisibility() == GONE ? VISIBLE : GONE);
        mFlipArrow.setRotation(mLinearLayout.getVisibility() == GONE? 0 : 180);
    }

    @Override
    public boolean onUpdateTaskCount(int tc) {
        post(()->{
            if(tc > 0) {
                mTaskNumberDisplayer.setText(getContext().getString(R.string.progresslayout_tasks_in_progress, tc));
                setVisibility(VISIBLE);
            }else
                setVisibility(GONE);
        });
        return false;
    }

    /** One row of the list: the progress of its key, and a stop when that work may be ended. */
    class LayoutProgressListener {
        final String progressKey;
        final LinearLayout row;
        final TextProgressBar bar;
        final TextView stop;
        boolean isAttached = false;

        LayoutProgressListener(String progressKey) {
            this.progressKey = progressKey;
            int rowHeight = getResources().getDimensionPixelOffset(R.dimen._20sdp);
            row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            bar = new TextProgressBar(getContext());
            bar.setTextPadding(getResources().getDimensionPixelOffset(R.dimen._6sdp));
            row.addView(bar, new LinearLayout.LayoutParams(0, rowHeight, 1f));

            // A stop ends the downloads of this row alone. Work that unpacks and writes
            // files is left without one, ending it midway could leave a mess
            stop = new TextView(getContext());
            stop.setText(R.string.progress_stop);
            stop.setTextColor(Color.WHITE);
            stop.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen._12ssp));
            stop.setPadding(getResources().getDimensionPixelOffset(R.dimen._8sdp), 0,
                    getResources().getDimensionPixelOffset(R.dimen._8sdp), 0);
            // The ripple lives in the theme as an attribute, so it has to be read into
            // a drawable before it can be set as one
            TypedValue ripple = new TypedValue();
            if(getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true)
                    && ripple.resourceId != 0) {
                stop.setBackgroundResource(ripple.resourceId);
            }
            stop.setVisibility(isStoppableKey(progressKey) ? VISIBLE : GONE);
            stop.setOnClickListener(v -> {
                DownloadStop.stopKey(progressKey);
                stop.setEnabled(false);
                Toast.makeText(getContext(), R.string.progress_stopped, Toast.LENGTH_SHORT).show();
            });
            row.addView(stop, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, rowHeight));
        }

        void attach() {
            if(isAttached) return;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    getResources().getDimensionPixelOffset(R.dimen._20sdp));
            params.bottomMargin = getResources().getDimensionPixelOffset(R.dimen._6sdp);
            mLinearLayout.addView(row, params);
            isAttached = true;
        }

        void update(int progress, int resid, Object... va) {
            bar.setProgress(progress);
            if(resid != -1) bar.setText(getContext().getString(resid, va));
            else if(va.length > 0 && va[0] != null) bar.setText((String) va[0]);
            else bar.setText("");
        }

        void detach() {
            mLinearLayout.removeView(row);
            isAttached = false;
        }
    }
}
