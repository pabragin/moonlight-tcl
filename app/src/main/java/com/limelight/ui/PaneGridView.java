package com.limelight.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.GridView;

/**
 * The app grid of the main screen. The stock GridView, when focus comes back from above, selects the tile
 * of the top row nearest to the view the focus came from, so after a trip to the PC chips the cursor jumped
 * from the app the user had reached to the leftmost one. Dropping the rect makes the grid resurrect its own
 * last selection instead, the same cure as in PaneListView.
 */
public class PaneGridView extends GridView {
    public PaneGridView(Context context) {
        super(context);
    }

    public PaneGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PaneGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, null);
    }
}
