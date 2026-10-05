package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ListView;

/**
 * A ListView for a TV pane. Two differences from the stock one:
 * - it keeps its own selection when focus arrives from the side (the stock ListView picks the row level
 *   with the view the focus came from, so moving from a settings row to the categories used to land on
 *   whatever category happened to be at that height and switch the pane under the cursor);
 * - the d-pad moves the cursor with the content gliding under it, so the selected row settles at about a
 *   third of the height, instead of the list standing still and then jumping a whole row when the cursor
 *   hits the bottom edge. The glide is a ValueAnimator driving scrollListBy(), not the list's own fling,
 *   which the pending selection layout used to swallow.
 */
public class PaneListView extends ListView {
    private static final float ANCHOR = 0.35f;
    private static final int SCROLL_MS = 260;

    private ValueAnimator glide;

    public PaneListView(Context context) {
        super(context);
    }

    public PaneListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PaneListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        // No rect: the list resurrects its last selection instead of hunting for the nearest row
        super.onFocusChanged(gainFocus, direction, null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (moveCursor(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGlide();
        super.onDetachedFromWindow();
    }

    /** Selects the next row in place, then glides the content so that row sits at the anchor. */
    private boolean moveCursor(int step) {
        int selected = getSelectedItemPosition();
        int next = selected + step;
        if (selected < 0 || next < 0 || next >= getCount()) {
            // Edges: the stock behaviour (including letting focus leave the list) applies
            return false;
        }
        View child = getChildAt(next - getFirstVisiblePosition());
        if (child == null) {
            return false;
        }
        stopGlide();

        int paddingTop = getListPaddingTop();
        int paddingBottom = getListPaddingBottom();
        // Select without moving anything: the row keeps its current top, laid out right now so the
        // animation below starts from a settled list
        setSelectionFromTop(next, child.getTop() - paddingTop);
        layoutChildren();

        child = getChildAt(next - getFirstVisiblePosition());
        if (child == null) {
            return true;
        }
        int anchorTop = Math.round(getHeight() * ANCHOR);
        int delta = child.getTop() - anchorTop;
        // Never scroll past the first row's top or the last row's bottom
        View first = getChildAt(0);
        View last = getChildAt(getChildCount() - 1);
        if (delta < 0 && getFirstVisiblePosition() == 0 && first != null) {
            delta = Math.max(delta, first.getTop() - paddingTop);
        }
        if (delta > 0 && getLastVisiblePosition() == getCount() - 1 && last != null) {
            delta = Math.min(delta, last.getBottom() - (getHeight() - paddingBottom));
        }
        if (delta != 0) {
            startGlide(delta);
        }
        return true;
    }

    private void startGlide(int distance) {
        final int[] done = { 0 };
        glide = ValueAnimator.ofInt(0, distance);
        glide.setDuration(SCROLL_MS);
        glide.setInterpolator(new DecelerateInterpolator(1.6f));
        glide.addUpdateListener(animation -> {
            int now = (Integer) animation.getAnimatedValue();
            int step = now - done[0];
            done[0] = now;
            if (step != 0) {
                scrollListBy(step);
            }
        });
        glide.start();
    }

    private void stopGlide() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
    }
}
