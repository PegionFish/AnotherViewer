package com.hippo.anotherviewer.ui.fragment;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.anotherviewer.Analytics;
import com.hippo.anotherviewer.R;
import com.hippo.anotherviewer.Settings;
import com.hippo.anotherviewer.event.PrivacyMaskChanged;

import org.greenrobot.eventbus.EventBus;

/**
 * Created by Mo10 on 2018/2/10.
 */

public class PrivacyFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_PATTERN_PROTECTION = "pattern_protection";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.privacy_settings);
        Preference enableAnalytics = findPreference(Settings.KEY_ENABLE_ANALYTICS);

        enableAnalytics.setOnPreferenceChangeListener(this);

        Preference privacyMask = findPreference(Settings.KEY_PRIVACY_MASK);
        privacyMask.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        Preference patternProtection = findPreference(KEY_PATTERN_PROTECTION);
        patternProtection.setSummary(TextUtils.isEmpty(Settings.getSecurity()) ?
                R.string.settings_privacy_pattern_protection_not_set :
                R.string.settings_privacy_pattern_protection_set);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (Settings.KEY_ENABLE_ANALYTICS.equals(key)) {
            if (newValue instanceof Boolean && (Boolean) newValue) {
                Analytics.start(getActivity());
            }
            return true;
        }
        if (Settings.KEY_PRIVACY_MASK.equals(key)) {
            EventBus.getDefault().post(new PrivacyMaskChanged());
            return true;
        }
        return true;
    }
}
