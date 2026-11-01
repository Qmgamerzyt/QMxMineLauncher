package git.artdeell.mojo.modding.modpacks;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The list of versions a project was published in. Each row may carry a mark saying
 * it was built for the game and loader the instance runs, so the files that will not
 * work are told apart at a glance without opening them.
 */
public class VersionEntryAdapter extends BaseAdapter {
    private final LayoutInflater mInflater;
    private List<String> mVersions = new ArrayList<>();
    /** Whether the row at the same index was built for the instance, null when nothing is known. */
    private boolean[] mFits;

    public VersionEntryAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    /** The versions to list, with the mark of each one built from the same list. */
    public void setVersions(@Nullable List<String> versions, @Nullable boolean[] fits) {
        mVersions = versions == null ? new ArrayList<>() : versions;
        mFits = fits;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mVersions.size();
    }

    @Override
    public String getItem(int position) {
        return mVersions.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if(view == null) view = mInflater.inflate(R.layout.view_version_entry, parent, false);
        TextView name = view.findViewById(R.id.version_entry_name);
        TextView compat = view.findViewById(R.id.version_entry_compat);
        name.setText(mVersions.get(position));
        // The mark is only shown when the host said what the file was built for
        boolean fits = mFits != null && position < mFits.length && mFits[position];
        compat.setVisibility(fits ? View.VISIBLE : View.GONE);
        return view;
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // The list that drops down is the one that carries the marks
        return getView(position, convertView, parent);
    }
}
