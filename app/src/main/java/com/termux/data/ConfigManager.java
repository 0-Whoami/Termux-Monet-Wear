package com.termux.data;

import static com.termux.utils.UiElements.paint;
import static java.lang.Math.max;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.KeyEvent;

import com.termux.terminal.TerminalColorScheme;
import com.termux.utils.Theme;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import nyx.constants.Constant;


public final class ConfigManager {
    public static int transcriptRows = 100;
    public static Typeface typeface = Typeface.MONOSPACE;

    public static void loadConfigs() {
        try {
            typeface = Typeface.createFromFile(Constant.EXTRA_FONT);
        } catch (final Throwable ignored) {
        }
        loadProp();
        loadColors();
        paint.setTypeface(typeface);
        try {
            final File file = new File(Constant.EXTRA_KEYS_CONFIG);
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                final FileWriter fw = new FileWriter(file);
                fw.write("⌫ : " + KeyEvent.KEYCODE_DEL);
                fw.close();
            }
        } catch (final IOException ignored) {
        }
    }

    private static void loadProp() {
        final Properties properties = new Properties(Constant.EXTRA_CONFIG);
        transcriptRows = max(50, properties.getInt(Constant.KEY_TRANSCRIPT_ROWS, transcriptRows));
        Theme.setPrimary(properties.get(Constant.KEY_COLOR_PRIMARY), properties.get(Constant.KEY_COLOR_SECONDARY));
    }

    private static void loadColors() {

        new Properties(Constant.EXTRA_COLORS_CONFIG).forEach((i, val) -> {
            try {
                TerminalColorScheme.DEFAULT_COLORSCHEME[Integer.parseInt(i)] = Color.parseColor(val);
            } catch (final Throwable ignored) {
            }
        });

    }

}
