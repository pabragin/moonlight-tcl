package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The poster grid of one PC (main_app_tile): box art through the cached asset loader, a "Running" badge on
 * the app the PC is streaming, hidden apps dimmed or left out. The selected card scales up with a white ring.
 */
public final class AppTileAdapter extends BaseAdapter {
    /** Box art the host serves is 600x800; the tile is 160 dp wide */
    private static final int ART_WIDTH_PX = 300;
    private static final int TILE_WIDTH_DP = 160;
    private static final float SELECTED_SCALE = 1.07f;

    public static final class Entry {
        public final NvApp app;
        public boolean running;
        public boolean hidden;

        Entry(NvApp app) {
            this.app = app;
        }
    }

    /** Which position the grid has under the cursor right now */
    public interface Highlight {
        boolean isHighlighted(int position);
    }

    private final LayoutInflater inflater;
    private final CachedAppAssetLoader loader;
    private final Highlight highlight;
    private final ArrayList<Entry> all = new ArrayList<>();
    private final ArrayList<Entry> shown = new ArrayList<>();
    private final Set<Integer> hiddenIds = new HashSet<>();
    private boolean showHidden;

    public AppTileAdapter(Context context, ComputerDetails computer, String uniqueId, boolean showHidden, Highlight highlight) {
        this.inflater = LayoutInflater.from(context);
        this.showHidden = showHidden;
        this.highlight = highlight;

        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        double scalingDivisor = ART_WIDTH_PX / (TILE_WIDTH_DP * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);
        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));
    }

    public boolean isShowingHidden() {
        return showHidden;
    }

    public void setShowHidden(boolean showHidden) {
        this.showHidden = showHidden;
        rebuild();
    }

    public void updateHiddenApps(Set<Integer> newHiddenIds, boolean hideImmediately) {
        hiddenIds.clear();
        hiddenIds.addAll(newHiddenIds);
        for (Entry entry : all) {
            entry.hidden = hiddenIds.contains(entry.app.getAppId());
        }
        if (hideImmediately) {
            rebuild();
        }
        else {
            notifyDataSetChanged();
        }
    }

    private void rebuild() {
        shown.clear();
        for (Entry entry : all) {
            if (showHidden || !entry.hidden) {
                shown.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    private static void sort(List<Entry> list) {
        Collections.sort(list, (lhs, rhs) -> {
            int l = lhs.app.getAppIndex();
            int r = rhs.app.getAppIndex();
            if (l == r) {
                return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
            }
            return l - r;
        });
    }

    public Entry findByAppId(int appId) {
        for (Entry entry : all) {
            if (entry.app.getAppId() == appId) {
                return entry;
            }
        }
        return null;
    }

    public List<Entry> getAll() {
        return all;
    }

    public void addApp(NvApp app) {
        Entry entry = new Entry(app);
        entry.hidden = hiddenIds.contains(app.getAppId());
        all.add(entry);
        sort(all);
        if (showHidden || !entry.hidden) {
            loader.queueCacheLoad(app);
            shown.add(entry);
            sort(shown);
        }
    }

    public void removeApp(Entry entry) {
        shown.remove(entry);
        all.remove(entry);
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    @Override
    public int getCount() {
        return shown.size();
    }

    @Override
    public Entry getItem(int position) {
        return shown.get(position);
    }

    @Override
    public long getItemId(int position) {
        return shown.get(position).app.getAppId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.main_app_tile, parent, false);
        }
        Entry entry = shown.get(position);
        ImageView image = convertView.findViewById(R.id.grid_image);
        TextView name = convertView.findViewById(R.id.grid_text);
        loader.populateImageView(entry.app, image, name);

        convertView.findViewById(R.id.runningBadge).setVisibility(entry.running ? View.VISIBLE : View.GONE);
        convertView.setAlpha(entry.hidden ? 0.4f : 1f);

        boolean selected = highlight != null && highlight.isHighlighted(position);
        setSelected(convertView, selected, false);
        return convertView;
    }

    /** The white ring and the slight zoom of the card under the cursor */
    public static void setSelected(View tile, boolean selected, boolean animate) {
        View card = tile.findViewById(R.id.tileCard);
        if (card != null) {
            card.setActivated(selected);
        }
        float scale = selected ? SELECTED_SCALE : 1f;
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        }
        else {
            tile.animate().cancel();
            tile.setScaleX(scale);
            tile.setScaleY(scale);
        }
    }
}
