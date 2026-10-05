package com.limelight.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The dark action panel of the in-game menu, for use anywhere: a small spaced-caps heading and a list of
 * rows, one tap runs the row's action and closes the panel. Built on the framework AlertDialog with
 * MoonlightDialogTheme, so it needs no library.
 */
public final class PanelMenu {

    public static final class Item {
        final String label;
        final Runnable action;

        public Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        /** A row with a check mark after the label when it is the current choice; rows stay aligned */
        public static Item checked(String label, boolean checked, Runnable action) {
            return new Item(checked ? label + "  \u2713" : label, action);
        }
    }

    private static final int WIDTH_DP = 340;

    private PanelMenu() {
    }

    public static AlertDialog show(Activity activity, String title, List<Item> items) {
        Context themed = new ContextThemeWrapper(activity, activity.getApplicationInfo().theme);
        AlertDialog.Builder builder = new AlertDialog.Builder(themed, R.style.MoonlightDialogTheme);

        if (title != null) {
            TextView titleView = (TextView) LayoutInflater.from(builder.getContext()).inflate(R.layout.game_menu_title, null);
            titleView.setText(title);
            builder.setCustomTitle(titleView);
        }

        final ArrayAdapter<String> rows = new ArrayAdapter<>(builder.getContext(), R.layout.game_menu_item);
        final List<Item> all = new ArrayList<>(items);
        all.add(new Item(activity.getString(R.string.game_menu_cancel), null));
        for (Item item : all) {
            rows.add(item.label);
        }

        builder.setAdapter(rows, (dialog, which) -> {
            Item item = all.get(which);
            if (item.action != null) {
                item.action.run();
            }
        });

        AlertDialog dialog = builder.show();

        ListView list = dialog.getListView();
        if (list != null) {
            // The rows draw their own highlight (game_menu_item_bg)
            list.setSelector(android.R.color.transparent);
            list.setDivider(null);
            list.setDividerHeight(0);
            float density = activity.getResources().getDisplayMetrics().density;
            list.setPadding(0, 0, 0, Math.round(10 * density));
        }

        Window window = dialog.getWindow();
        if (window != null) {
            float density = activity.getResources().getDisplayMetrics().density;
            window.setLayout(Math.round(WIDTH_DP * density), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        return dialog;
    }
}
