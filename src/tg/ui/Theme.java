package tg.ui;

/** Immutable colour palette shared by every application-drawn screen. */
public final class Theme
{
    public static final int LIGHT = 0;
    public static final int DARK = 1;
    public static final int HIGH_CONTRAST = 2;
    public static final int COUNT = 3;

    public final int id;
    public final String name;
    public final int background;
    public final int surface;
    public final int text;
    public final int secondaryText;
    public final int accent;
    public final int accentText;
    public final int selection;
    public final int selectionText;
    public final int outgoingText;
    public final int border;
    public final int badge;
    public final int badgeText;
    public final int photoBackground;

    private static final Theme[] PRESETS = new Theme[] {
        // ResyGram's accent is the blue from the logo: #007DAC. The orange
        // dot from the same mark is the unread badge, so the two colours on
        // screen belong to one identity instead of being borrowed from Telegram.
        new Theme(LIGHT, "Light",
                0xFFFFFF, 0xF2F7FA, 0x000000, 0x5C6B76,
                0x007DAC, 0xFFFFFF, 0xE1F1F8, 0x000000,
                0x00638A, 0xC7D7E0, 0xF39D12, 0xFFFFFF, 0x101010),
        new Theme(DARK, "Dark",
                0x0E1419, 0x15202A, 0xF2F5F7, 0xA7B6C2,
                0x1E8EC7, 0xFFFFFF, 0x193747, 0xFFFFFF,
                0x62B6DF, 0x2C414F, 0xF3A325, 0x071018, 0x080A0C),
        new Theme(HIGH_CONTRAST, "High contrast",
                0x000000, 0x000000, 0xFFFFFF, 0xFFFFFF,
                0xFFFFFF, 0x000000, 0xFFFF00, 0x000000,
                0x00FFFF, 0xFFFFFF, 0xFFFF00, 0x000000, 0x000000)
    };

    private Theme(int id, String name, int background, int surface,
                  int text, int secondaryText, int accent, int accentText,
                  int selection, int selectionText, int outgoingText,
                  int border, int badge, int badgeText, int photoBackground)
    {
        this.id = id;
        this.name = name;
        this.background = background;
        this.surface = surface;
        this.text = text;
        this.secondaryText = secondaryText;
        this.accent = accent;
        this.accentText = accentText;
        this.selection = selection;
        this.selectionText = selectionText;
        this.outgoingText = outgoingText;
        this.border = border;
        this.badge = badge;
        this.badgeText = badgeText;
        this.photoBackground = photoBackground;
    }

    public static Theme byId(int id)
    {
        return id >= 0 && id < PRESETS.length ? PRESETS[id] : PRESETS[LIGHT];
    }

    public static String[] names()
    {
        String[] out = new String[PRESETS.length];
        for (int i = 0; i < out.length; i++) { out[i] = PRESETS[i].name; }
        return out;
    }

    public static boolean isValid(int id)
    {
        return id >= 0 && id < PRESETS.length;
    }
}
