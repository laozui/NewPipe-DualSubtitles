package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.Preference;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.image.PreferredImageQuality;

import java.util.Locale;

import android.content.ClipboardManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import org.schabi.newpipe.util.InfoCache;

import coil3.SingletonImageLoader;

public class ContentSettingsFragment extends BasePreferenceFragment {
    private String youtubeRestrictedModeEnabledKey;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled);

        addPreferencesFromResourceRegistry();

        setupAppLanguagePreferences();
        setupImageQualityPref();
        setupYoutubeCookiePreference();
    }

    private void setupAppLanguagePreferences() {
        final Preference appLanguagePref = requirePreference(R.string.app_language_key);
        // Android 13+ allows to set app specific languages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appLanguagePref.setVisible(false);

            final Preference newAppLanguagePref =
                    requirePreference(R.string.app_language_android_13_and_up_key);
            newAppLanguagePref.setSummaryProvider(preference -> {
                final Locale loc = AppCompatDelegate.getApplicationLocales().get(0);
                return loc != null ? loc.getDisplayName() : getString(R.string.systems_language);
            });
            newAppLanguagePref.setOnPreferenceClickListener(preference -> {
                final Intent intent = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                        .setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                startActivity(intent);
                return true;
            });
            newAppLanguagePref.setVisible(true);
            return;
        }

        appLanguagePref.setOnPreferenceChangeListener((preference, newValue) -> {
            final String language = (String) newValue;
            final String systemLang = getString(R.string.default_localization_key);
            final String tag = systemLang.equals(language) ? null : language;
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
            return true;
        });
    }

    private void setupImageQualityPref() {
        requirePreference(R.string.image_quality_key).setOnPreferenceChangeListener(
            (preference, newValue) -> {
                ImageStrategy.setPreferredImageQuality(PreferredImageQuality
                    .fromPreferenceKey(requireContext(), (String) newValue));
                final var loader = SingletonImageLoader.get(preference.getContext());
                loader.getMemoryCache().clear();
                loader.getDiskCache().clear();
                Toast.makeText(preference.getContext(),
                                R.string.thumbnail_cache_wipe_complete_notice, Toast.LENGTH_SHORT)
                        .show();
                return true;
            });
    }

    private void setupYoutubeCookiePreference() {
        final Preference cookiePref = findPreference(getString(R.string.youtube_account_cookie_key));
        if (cookiePref == null) {
            return;
        }

        updateCookieSummary(cookiePref);

        cookiePref.setOnPreferenceClickListener(preference -> {
            showCookieConfigDialog(preference);
            return true;
        });
    }

    private void updateCookieSummary(final Preference preference) {
        final String savedCookie = defaultPreferences.getString(
                getString(R.string.youtube_account_cookie_key), "");
        if (savedCookie != null && !savedCookie.trim().isEmpty()) {
            preference.setSummary(R.string.youtube_cookie_summary_set);
        } else {
            preference.setSummary(R.string.youtube_cookie_summary_empty);
        }
    }

    private void showCookieConfigDialog(final Preference preference) {
        final Context context = requireContext();
        final String currentCookie = defaultPreferences.getString(
                getString(R.string.youtube_account_cookie_key), "");

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (18 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        final EditText editText = new EditText(context);
        editText.setHint("例如: LOGIN_INFO=...; SAPISID=...; SID=...");
        editText.setText(currentCookie);
        editText.setMinLines(4);
        editText.setMaxLines(8);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setTextSize(13f);
        container.addView(editText);

        final Button pasteBtn = new Button(context, null, android.R.attr.borderlessButtonStyle);
        pasteBtn.setText(R.string.youtube_cookie_paste_btn);
        pasteBtn.setOnClickListener(v -> {
            final ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                final CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    editText.setText(text.toString().trim());
                    Toast.makeText(context, R.string.youtube_cookie_paste_btn, Toast.LENGTH_SHORT).show();
                }
            }
        });
        container.addView(pasteBtn);

        new AlertDialog.Builder(context)
                .setTitle(R.string.youtube_cookie_dialog_title)
                .setMessage(R.string.youtube_cookie_dialog_message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String input = editText.getText().toString().trim();
                    defaultPreferences.edit()
                            .putString(getString(R.string.youtube_account_cookie_key), input)
                            .apply();
                    DownloaderImpl.getInstance().updateYoutubeAccountCookie(input);
                    InfoCache.getInstance().clearCache();
                    updateCookieSummary(preference);
                    Toast.makeText(context, R.string.toast_youtube_cookie_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.youtube_cookie_clear_btn, (dialog, which) -> {
                    defaultPreferences.edit()
                            .putString(getString(R.string.youtube_account_cookie_key), "")
                            .apply();
                    DownloaderImpl.getInstance().updateYoutubeAccountCookie("");
                    InfoCache.getInstance().clearCache();
                    updateCookieSummary(preference);
                    Toast.makeText(context, R.string.toast_youtube_cookie_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (preference.getKey().equals(youtubeRestrictedModeEnabledKey)) {
            final Context context = getContext();
            if (context != null) {
                DownloaderImpl.getInstance().updateYoutubeRestrictedModeCookies(context);
            } else {
                Log.w(TAG, "onPreferenceTreeClick: null context");
            }
        }

        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        final Context context = requireContext();
        NewPipe.setupLocalization(
            Localization.getPreferredLocalization(context),
            Localization.getPreferredContentCountry(context));
        PlayerHelper.resetFormat();
    }
}
