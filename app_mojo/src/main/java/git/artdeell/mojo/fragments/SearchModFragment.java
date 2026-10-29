package git.artdeell.mojo.fragments;

import static git.artdeell.mojo.Tools.runOnUiThread;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.ModItemAdapter;
import git.artdeell.mojo.modding.modpacks.api.CommonApi;
import git.artdeell.mojo.modding.modpacks.api.ContentInstaller;
import git.artdeell.mojo.modding.modpacks.api.InstalledContent;
import git.artdeell.mojo.modding.modpacks.api.ModpackApi;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.SearchCategory;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchLoader;
import git.artdeell.mojo.modding.modpacks.models.SearchSort;
import git.artdeell.mojo.utils.DownloadStop;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.profiles.VersionSelectorDialog;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.progresskeeper.TaskCountListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;


public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";
    private View mOverlay;
    private float mOverlayTopCache; // Padding cache reduce resource lookup

    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private ColorStateList mDefaultTextColor;
    private ModpackApi modpackApi;

    /** Argument carrying the ContentKind this screen opens on. */
    public static final String ARG_KIND = "kind";
    /** Argument carrying the Minecraft version to filter by, usually the instance's own. */
    public static final String ARG_MC_VERSION = "mc_version";
    /** Argument carrying the loader to filter by, usually the instance's own. */
    public static final String ARG_LOADER = "loader";

    private final SearchFilters mSearchFilters;
    private TextView[] mTabViews;

    private Button mImportButton;
    private TaskCountListener mTaskCountListener;
    /** The kind the open picker is bringing in for, read back once the files arrive. */
    private ContentKind mPendingImportKind;

    ActivityResultLauncher<String[]> mImportLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

    /** Put the picked files to work, each batch landing as the kind it was picked for. */
    private void onFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        Context context = getContext();
        if (context == null) return;
        // The kind is kept from the moment the picker was opened, the screen
        // may be on another tab by the time the files are in hand
        ContentKind kind = mPendingImportKind != null ? mPendingImportKind : mSearchFilters.kind;
        mPendingImportKind = null;
        Context appContext = context.getApplicationContext();
        MojoApplication.sExecutorService.execute(() ->
                performLocalInstall(new ArrayList<>(uris), kind, appContext));
    }

    /**
     * Open the file picker for one kind, keeping what the picked files are for until
     * they arrive. Several files can be picked at once, they all land together.
     */
    private void startImport(ContentKind kind) {
        if(!isAdded()) return;
        mPendingImportKind = kind;
        mImportLauncher.launch(new String[]{"*/*"});
    }

    /**
     * Bring the picked files into the game. A modpack makes an instance of its own for
     * every archive, while the other kinds are placed into one instance asked for once
     * for the whole batch.
     */
    private void performLocalInstall(List<Uri> uris, ContentKind kind, Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        if (kind == ContentKind.MODPACK) {
            for (Uri uri : uris) {
                if (!installLocalModpack(uri, context, contentResolver)) break;
            }
            return;
        }
        ArrayList<ContentInstaller.PickedFile> picked = new ArrayList<>();
        for (Uri uri : uris) {
            String fileName = Tools.getFileName(context, uri);
            if (fileName != null) picked.add(new ContentInstaller.PickedFile(uri, fileName));
        }
        if (picked.isEmpty()) return;
        ContentInstaller.handleLocalFiles(context, kind, picked);
    }

    /**
     * Unpack one modpack archive into an instance the installer creates for it.
     *
     * @return false when the rest of the batch should be left alone
     */
    private boolean installLocalModpack(Uri uri, Context context, ContentResolver contentResolver) {
        String fileName = Tools.getFileName(context, uri);
        if (fileName == null) return true;
        DownloadStop.beginWork();
        File outFile = new File(Tools.DIR_CACHE, fileName + ".cf");
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.content_install_copying);
        try (InputStream inputStream = contentResolver.openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(outFile)) {
            if (inputStream == null) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                return true;
            }
            DownloadUtils.copyCancellable(inputStream, outputStream);
            outputStream.flush();
        } catch (IOException e) {
            outFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            Tools.showErrorRemote("Error", e);
            return !DownloadStop.isStopped(e);
        }
        boolean keepGoing = true;
        try {
            modpackApi.installLocalModpack(fileName, outFile, null);
        } catch (IOException e) {
            Tools.showErrorRemote("Error", e);
            keepGoing = !DownloadStop.isStopped(e);
        } finally {
            outFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
        return keepGoing;
    }

    public SearchModFragment(){
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.kind = ContentKind.MODPACK;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        modpackApi = new CommonApi(context.getString(R.string.curseforge_api_key));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // You can only access resources after attaching to current context
        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.search_mod_overlay);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mFilterButton = view.findViewById(R.id.search_mod_filter);

        mDefaultTextColor = mStatusTextView.getTextColors();

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);

        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mOverlay.post(()->{
           int overlayHeight = mOverlay.getHeight();
           mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                   mRecyclerview.getPaddingTop() + overlayHeight,
                   mRecyclerview.getPaddingRight(),
                   mRecyclerview.getPaddingBottom());
        });
        mFilterButton.setOnClickListener(v -> displayFilterDialog());
        mImportButton = view.findViewById(R.id.mineButton_import_local_modpack);
        mImportButton.setOnClickListener(v -> startImport(mSearchFilters.kind));
        view.findViewById(R.id.content_manage_button).setOnClickListener(v ->
                InstalledContent.handleManage(requireContext().getApplicationContext(), kind ->
                        startImport(kind != null ? kind : mSearchFilters.kind)));
        mTaskCountListener = taskCount -> {
            runOnUiThread(() -> mImportButton.setEnabled(taskCount == 0));
            return false;
        };
        ProgressKeeper.addTaskCountListener(mTaskCountListener);
        applyKindArgument(view);
        refreshFilterButton();

        searchMods(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
        if (mTaskCountListener != null) { ProgressKeeper.removeTaskCountListener(mTaskCountListener); }
    }

    @Override
    public void onSearchFinished() {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    /**
     * Take the kind this screen was opened on from the fragment arguments, then bind
     * every tab to the kind it stands for.
     */
    private void applyKindArgument(View view) {
        Bundle arguments = getArguments();
        if(arguments != null) {
            String kindName = arguments.getString(ARG_KIND);
            if(kindName != null) {
                try {
                    mSearchFilters.kind = ContentKind.valueOf(kindName);
                }catch (IllegalArgumentException ignored) {
                    // A kind that no longer exists leaves the default one in place
                }
            }
            String mcVersion = arguments.getString(ARG_MC_VERSION);
            if(mcVersion != null && !mcVersion.isEmpty()) mSearchFilters.mcVersion = mcVersion;
            String loader = arguments.getString(ARG_LOADER);
            if(loader != null) mSearchFilters.loader = SearchLoader.fromName(loader).name();
        }
        mTabViews = new TextView[] {
                bindTab(view, R.id.search_tab_mod, ContentKind.MOD),
                bindTab(view, R.id.search_tab_resource_pack, ContentKind.RESOURCE_PACK),
                bindTab(view, R.id.search_tab_shader, ContentKind.SHADER),
                bindTab(view, R.id.search_tab_world, ContentKind.WORLD),
                bindTab(view, R.id.search_tab_modpack, ContentKind.MODPACK)
        };
        refreshTabs();
        updateImportButton();
    }

    private TextView bindTab(View view, int viewId, ContentKind kind) {
        TextView tab = view.findViewById(viewId);
        tab.setTag(kind);
        tab.setOnClickListener(this::onTabClicked);
        return tab;
    }

    private void onTabClicked(View view) {
        ContentKind kind = (ContentKind) view.getTag();
        if(kind == null || kind == mSearchFilters.kind) return;
        mSearchFilters.kind = kind;
        // Every kind is grouped in its own way, so the picked categories belong to
        // the tab that was showing when they were chosen and start over here
        mSearchFilters.clearCategories();
        refreshTabs();
        updateImportButton();
        refreshFilterButton();
        searchMods(mSearchEditText.getText().toString());
    }

    /** Marks the filter button in the accent colour while the search stays narrowed. */
    private void refreshFilterButton() {
        Context context = getContext();
        if(mFilterButton == null || context == null) return;
        mFilterButton.setColorFilter(ContextCompat.getColor(context, mSearchFilters.isFiltered()
                ? R.color.minebutton_color : R.color.secondary_text));
    }

    private void refreshTabs() {
        if(mTabViews == null) return;
        Context context = getContext();
        if(context == null) return;
        int selectedColor = ContextCompat.getColor(context, R.color.minebutton_color);
        int idleColor = ContextCompat.getColor(context, R.color.secondary_text);
        for(TextView tab : mTabViews) {
            ContentKind kind = (ContentKind) tab.getTag();
            boolean selected = kind == mSearchFilters.kind;
            tab.setTextColor(selected ? selectedColor : idleColor);
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /**
     * Every kind of content can be brought in from the device, so the button stays on
     * and names what the current tab will take.
     */
    private void updateImportButton() {
        if(mImportButton == null) return;
        ContentKind kind = mSearchFilters.kind;
        if(kind == ContentKind.MODPACK) mImportButton.setText(R.string.import_local_modpack);
        else if(kind == ContentKind.WORLD) mImportButton.setText(R.string.content_import_local_world);
        else if(kind == ContentKind.MOD) mImportButton.setText(R.string.content_import_local_mod);
        else if(kind == ContentKind.RESOURCE_PACK) mImportButton.setText(R.string.content_import_local_resource_pack);
        else if(kind == ContentKind.SHADER) mImportButton.setText(R.string.content_import_local_shader);
        else mImportButton.setText(R.string.content_import_local_file);
        mImportButton.setVisibility(View.VISIBLE);
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        // setup the view behavior
        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);
            Spinner mSortSpinner = dialog.findViewById(R.id.search_mod_sort_spinner);
            Button mCategoriesButton = dialog.findViewById(R.id.search_mod_categories_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mLoaderSpinner != null;
            assert mSortSpinner != null;
            assert mCategoriesButton != null;
            assert mApplyButton != null;

            // The labels sit in the same order the enums are declared in, so the
            // position in the spinner is the value the search will be given
            mLoaderSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                    R.layout.item_simple_list_1, getResources().getStringArray(R.array.content_filter_loaders)));
            mSortSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                    R.layout.item_simple_list_1, getResources().getStringArray(R.array.content_filter_sorts)));
            mLoaderSpinner.setSelection(SearchLoader.fromName(mSearchFilters.loader).ordinal());
            mSortSpinner.setSelection(SearchSort.fromValue(mSearchFilters.sort).ordinal());

            // The categories are held back until Apply, so the list can be opened and
            // changed without the search running on every tick
            ArrayList<SearchCategory> pickedCategories = new ArrayList<>(mSearchFilters.categories);
            updateCategoryLabel(mCategoriesButton, pickedCategories);
            ContentKind dialogKind = mSearchFilters.kind;

            // Setup the expendable list behavior
            mSelectVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true, (id, snapshot)-> mSelectedVersion.setText(id)));

            mCategoriesButton.setOnClickListener(v ->
                    openCategoryPicker(v.getContext(), dialogKind, pickedCategories, mCategoriesButton));

            // Apply visually all the current settings
            mSelectedVersion.setText(mSearchFilters.mcVersion);

            // Apply the new settings
            mApplyButton.setOnClickListener(v -> {
                mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                int loaderPosition = mLoaderSpinner.getSelectedItemPosition();
                int sortPosition = mSortSpinner.getSelectedItemPosition();
                mSearchFilters.loader = loaderPosition >= 0 && loaderPosition < SearchLoader.values().length
                        ? SearchLoader.values()[loaderPosition].name() : SearchLoader.ANY.name();
                mSearchFilters.sort = sortPosition >= 0 && sortPosition < SearchSort.values().length
                        ? SearchSort.values()[sortPosition].name() : SearchSort.RELEVANCE.name();
                mSearchFilters.clearCategories();
                mSearchFilters.categories.addAll(pickedCategories);
                refreshFilterButton();
                searchMods(mSearchEditText.getText().toString());
                dialogInterface.dismiss();
            });
        });

        dialog.show();
    }

    /** Worded summary of the chosen categories, standing for the whole selection. */
    private void updateCategoryLabel(Button button, List<SearchCategory> picked) {
        if(picked.isEmpty()) {
            button.setText(R.string.content_filter_any);
        }else if(picked.size() == 1) {
            button.setText(picked.get(0).label());
        }else {
            button.setText(button.getContext().getString(R.string.content_filter_categories_count, picked.size()));
        }
    }

    /**
     * Fetch the categories of the kind the dialog was opened on and let the reader
     * tick the ones to narrow the search to. A category carried by one host only
     * narrows the search down to that host, which is why the labels say who they
     * belong to.
     *
     * @param context  where the list and any message are shown
     * @param kind     the content the categories are fetched for
     * @param picked   the selection so far, rewritten once the list is confirmed
     * @param label    the button carrying the summary of the selection
     */
    private void openCategoryPicker(Context context, ContentKind kind,
                                    ArrayList<SearchCategory> picked, Button label) {
        if(modpackApi == null) return;
        MojoApplication.sExecutorService.execute(() -> {
            List<SearchCategory> available;
            try {
                available = modpackApi.listCategories(kind);
            }catch (RuntimeException e) {
                Log.e(TAG, "Could not list the categories of " + kind, e);
                runOnUiThread(() -> Toast.makeText(context,
                        R.string.content_filter_categories_error, Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> {
                if(available.isEmpty()) {
                    Toast.makeText(context, R.string.content_filter_categories_none,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[available.size()];
                boolean[] checked = new boolean[available.size()];
                for(int i = 0; i < available.size(); i++) {
                    labels[i] = available.get(i).label();
                    checked[i] = holds(picked, available.get(i));
                }
                new AlertDialog.Builder(context)
                        .setTitle(R.string.content_filter_categories)
                        .setMultiChoiceItems(labels, checked,
                                (dialog, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            picked.clear();
                            for(int i = 0; i < available.size(); i++) {
                                if(checked[i]) picked.add(available.get(i));
                            }
                            updateCategoryLabel(label, picked);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** Whether a category already sits in the selection, matched on the name alone. */
    private static boolean holds(List<SearchCategory> picked, SearchCategory category) {
        String wanted = SearchCategory.normalize(category.name);
        for(SearchCategory selected : picked) {
            if(wanted.equals(SearchCategory.normalize(selected.name))) return true;
        }
        return false;
    }
}
