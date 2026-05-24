package com.visolearn.utils;

/**
 * Design-token constants for colours that Java code must reference directly.
 *
 * <p>These are the <em>only</em> hex strings that should appear in controller
 * source files. All other colour decisions live in {@code styles.css} /
 * {@code styles-light.css} as CSS class rules.</p>
 *
 * <h3>Scope</h3>
 * <p>This class covers three categories:</p>
 * <ol>
 *   <li><b>Surface tokens</b> — background colours for inline-styled nodes
 *       (dialogs, cell factory roots) that cannot be reached by CSS class rules.</li>
 *   <li><b>Text tokens</b> — foreground colours for {@code Label.setStyle()} calls
 *       inside cell factories where the value depends on the active theme.</li>
 *   <li><b>Semantic accent colours</b> — brand/intent colours (emerald, red, amber,
 *       cyan) that are identical in both themes; used in inline badge styles.</li>
 * </ol>
 *
 * <h3>Theme-aware accessors</h3>
 * <p>Pass the result of {@link com.visolearn.utils.SettingsManager#isDarkMode()}
 * to the overloaded methods ({@link #bgBase}, {@link #textPrimary},
 * {@link #textMuted}) to get the correct token for the current theme without
 * writing an {@code if/else} in every call site.</p>
 *
 * <h3>What does NOT belong here</h3>
 * <ul>
 *   <li>Colours that are already covered by a CSS {@code styleClass} — keep those
 *       in CSS only.</li>
 *   <li>{@code MainController.DARK_TO_LIGHT} replacement pairs — that table IS the
 *       theme-swap engine and must exactly mirror the CSS values.</li>
 *   <li>Splash-screen brand colours — the splash is always dark and never
 *       theme-switched.</li>
 * </ul>
 */
public final class UITokens {

    private UITokens() {}   // utility class — no instances

    // ─────────────────────────────────────────────────────────────────────────
    // Surface tokens — dark theme
    // ─────────────────────────────────────────────────────────────────────────

    /** Deepest background layer in dark mode (app root, scan-viewer dialog). */
    public static final String DARK_BG_BASE    = "#1A1A24";

    /** Elevated card/surface background in dark mode. */
    public static final String DARK_BG_SURFACE = "#252533";

    // ─────────────────────────────────────────────────────────────────────────
    // Text tokens — dark theme
    // ─────────────────────────────────────────────────────────────────────────

    /** Primary readable text on dark backgrounds. */
    public static final String DARK_TEXT_PRI = "#F8F9FA";

    /** Muted / secondary text on dark backgrounds (DOB, phone, metadata). */
    public static final String DARK_TEXT_MUT = "#94A3B8";

    // ─────────────────────────────────────────────────────────────────────────
    // Surface tokens — light theme
    // ─────────────────────────────────────────────────────────────────────────

    /** Deepest background layer in light mode. */
    public static final String LIGHT_BG_BASE    = "#F4F7FB";

    /** Elevated card/surface background in light mode. */
    public static final String LIGHT_BG_SURFACE = "#FFFFFF";

    // ─────────────────────────────────────────────────────────────────────────
    // Text tokens — light theme
    // ─────────────────────────────────────────────────────────────────────────

    /** Primary readable text on light backgrounds. */
    public static final String LIGHT_TEXT_PRI = "#0F172A";

    /** Muted / secondary text on light backgrounds. */
    public static final String LIGHT_TEXT_MUT = "#475569";

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic accent colours — theme-invariant
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Primary brand / success accent colour (emerald-500).
     * Used for: selected patient name, high-confidence badge, export button,
     * low-risk badge.
     */
    public static final String EMERALD = "#10B981";

    /**
     * Danger / urgent accent colour (red-500).
     * Used for: urgent-risk badge, delete action button.
     */
    public static final String RED     = "#EF4444";

    /**
     * Warning / moderate accent colour (amber-500).
     * Used for: moderate-risk badge, medium-confidence badge.
     */
    public static final String AMBER   = "#F59E0B";

    /**
     * Informational accent colour (cyan-400).
     * Used for: view/scan action button.
     */
    public static final String CYAN    = "#00B4D8";

    // ─────────────────────────────────────────────────────────────────────────
    // Theme-aware accessors
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the deepest background colour for the active theme.
     *
     * @param dark {@code true} for dark mode, {@code false} for light mode.
     * @return hex colour string suitable for use in {@code -fx-background-color}.
     */
    public static String bgBase(boolean dark) {
        return dark ? DARK_BG_BASE : LIGHT_BG_BASE;
    }

    /**
     * Returns the elevated surface colour for the active theme.
     *
     * @param dark {@code true} for dark mode, {@code false} for light mode.
     * @return hex colour string suitable for use in {@code -fx-background-color}.
     */
    public static String bgSurface(boolean dark) {
        return dark ? DARK_BG_SURFACE : LIGHT_BG_SURFACE;
    }

    /**
     * Returns the primary text colour for the active theme.
     *
     * @param dark {@code true} for dark mode, {@code false} for light mode.
     * @return hex colour string suitable for use in {@code -fx-text-fill}.
     */
    public static String textPrimary(boolean dark) {
        return dark ? DARK_TEXT_PRI : LIGHT_TEXT_PRI;
    }

    /**
     * Returns the muted / secondary text colour for the active theme.
     * Used for metadata labels (DOB, phone number, skin type).
     *
     * @param dark {@code true} for dark mode, {@code false} for light mode.
     * @return hex colour string suitable for use in {@code -fx-text-fill}.
     */
    public static String textMuted(boolean dark) {
        return dark ? DARK_TEXT_MUT : LIGHT_TEXT_MUT;
    }
}
