package org.schabi.newpipe.player.dualsubtitles;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * DualSubtitles 双语字幕配置管理类
 * 继承并融合 YouTube Dual Subs Pro 的核心视觉与语言偏好设定
 */
public final class DualSubtitleConfig {

    public static final String PREF_DUAL_SUBS_ENABLED = "dualsubs_enabled_key";
    public static final String PREF_SECONDARY_LANGUAGE = "dualsubs_secondary_language_key";
    public static final String PREF_PRIMARY_COLOR = "dualsubs_primary_color_key";
    public static final String PREF_SECONDARY_COLOR = "dualsubs_secondary_color_key";
    public static final String PREF_STITCHING_ENABLED = "dualsubs_stitching_enabled_key";

    public static final String DEFAULT_SECONDARY_LANG = "zh-Hans";
    public static final String DEFAULT_PRIMARY_COLOR = "#FFFFFF";
    public static final String DEFAULT_SECONDARY_COLOR = "#FFD166"; // 活力暖金黄

    private DualSubtitleConfig() {
    }

    private static SharedPreferences getPrefs(@NonNull final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isDualSubsEnabled(@NonNull final Context context) {
        return getPrefs(context).getBoolean(PREF_DUAL_SUBS_ENABLED, true);
    }

    public static void setDualSubsEnabled(@NonNull final Context context, final boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_DUAL_SUBS_ENABLED, enabled).apply();
    }

    @NonNull
    public static String getSecondaryLanguage(@NonNull final Context context) {
        return getPrefs(context).getString(PREF_SECONDARY_LANGUAGE, DEFAULT_SECONDARY_LANG);
    }

    public static void setSecondaryLanguage(@NonNull final Context context,
                                            @NonNull final String lang) {
        getPrefs(context).edit().putString(PREF_SECONDARY_LANGUAGE, lang).apply();
    }

    @NonNull
    public static String getPrimaryColor(@NonNull final Context context) {
        return getPrefs(context).getString(PREF_PRIMARY_COLOR, DEFAULT_PRIMARY_COLOR);
    }

    @NonNull
    public static String getSecondaryColor(@NonNull final Context context) {
        return getPrefs(context).getString(PREF_SECONDARY_COLOR, DEFAULT_SECONDARY_COLOR);
    }

    public static boolean isStitchingEnabled(@NonNull final Context context) {
        return getPrefs(context).getBoolean(PREF_STITCHING_ENABLED, true);
    }

    /**
     * 获取常用副语言的友好显示名称
     */
    @NonNull
    public static String getLanguageDisplayName(@NonNull final String langCode) {
        switch (langCode) {
            case "zh-Hans":
            case "zh-CN":
            case "zh":
                return "中文 (简体)";
            case "zh-Hant":
            case "zh-TW":
            case "zh-HK":
                return "中文 (繁体)";
            case "en":
                return "English";
            case "ja":
                return "日本語";
            case "ko":
                return "한국어";
            case "es":
                return "Español";
            case "fr":
                return "Français";
            case "de":
                return "Deutsch";
            case "ru":
                return "Русский";
            case "vi":
                return "Tiếng Việt";
            default:
                return langCode;
        }
    }
}
