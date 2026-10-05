package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.preferences.SettingsActivity;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;
import com.limelight.ui.PanelMenu;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings profiles: a list of saved configurations with the active one marked. A row opens the dark
 * action panel (activate or deactivate, edit, delete); editing and creating happen in SettingsActivity
 * over a copy of the profile's options.
 */
public class ProfilesActivity extends Activity implements ProfilesManager.ProfileChangeListener {
    private ListView list;
    private View emptyState;
    private ProfilesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);
        UiHelper.notifyNewRootView(this);

        list = findViewById(R.id.profilesList);
        emptyState = findViewById(R.id.emptyState);
        adapter = new ProfilesAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showMenu(adapter.getItem(position)));

        findViewById(R.id.newProfileButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_NEW_PROFILE, true)));

        ProfilesManager.getInstance().addListener(this);
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProfilesManager.getInstance().removeListener(this);
    }

    @Override
    public void onProfilesChanged() {
        runOnUiThread(this::updateUi);
    }

    private void updateUi() {
        boolean empty = ProfilesManager.getInstance().getProfiles().isEmpty();
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (empty) {
            findViewById(R.id.newProfileButton).requestFocus();
        }
        else if (getCurrentFocus() == null) {
            list.requestFocus();
        }
    }

    private void showMenu(SettingsProfile profile) {
        ProfilesManager manager = ProfilesManager.getInstance();
        SettingsProfile active = manager.getActive();
        boolean isActive = active != null && active.getUuid().equals(profile.getUuid());
        List<PanelMenu.Item> items = new ArrayList<>();
        items.add(new PanelMenu.Item(getString(isActive ? R.string.profiles_deactivate : R.string.profiles_activate), () -> {
            if (isActive) {
                manager.setActive(null);
                Toast.makeText(this, R.string.profile_manager_deactivated_profile, Toast.LENGTH_SHORT).show();
            }
            else {
                manager.setActive(profile.getUuid());
                Toast.makeText(this, getString(R.string.profile_manager_activated_profile, profile.getName()), Toast.LENGTH_SHORT).show();
            }
            manager.save(this);
            updateUi();
        }));
        items.add(new PanelMenu.Item(getString(R.string.profile_manager_edit_profile), () ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_PROFILE_UUID, profile.getUuid().toString()))));
        items.add(new PanelMenu.Item(getString(R.string.profile_manager_delete), () ->
                UiHelper.displayConfirmationDialog(this, getString(R.string.profile_manager_delete_profile),
                        getString(R.string.profile_manager_confirm_profile_deleteion, profile.getName()),
                        getString(R.string.profile_manager_delete), getString(R.string.cancel), () -> {
                            manager.delete(profile.getUuid());
                            manager.save(this);
                            Toast.makeText(this, getString(R.string.profile_manager_profile_deleted, profile.getName()), Toast.LENGTH_SHORT).show();
                            updateUi();
                        }, null)));
        PanelMenu.show(this, profile.getName(), items);
    }

    private final class ProfilesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return ProfilesManager.getInstance().getProfiles().size();
        }

        @Override
        public SettingsProfile getItem(int position) {
            return ProfilesManager.getInstance().getProfiles().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ProfilesActivity.this).inflate(R.layout.row_profile, parent, false);
            }
            SettingsProfile profile = getItem(position);
            SettingsProfile active = ProfilesManager.getInstance().getActive();
            boolean isActive = active != null && active.getUuid().equals(profile.getUuid());

            ((TextView) convertView.findViewById(R.id.profileName)).setText(profile.getName());
            ((TextView) convertView.findViewById(R.id.profileTimestamp)).setText(DateUtils.getRelativeTimeSpanString(
                    profile.getModifiedUtc(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            convertView.findViewById(R.id.profileActive).setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            return convertView;
        }
    }
}
