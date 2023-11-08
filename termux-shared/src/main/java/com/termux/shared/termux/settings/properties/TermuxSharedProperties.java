package com.termux.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.data.DataUtils;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.settings.properties.SharedPropertiesParser;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public abstract class TermuxSharedProperties {

    protected final Context mContext;

    protected final List<String> mPropertiesFilePaths;

    protected final Set<String> mPropertiesList;

    protected final SharedPropertiesParser mSharedPropertiesParser;

    protected File mPropertiesFile;

    protected SharedProperties mSharedProperties;

    public TermuxSharedProperties(@NonNull Context context, List<String> propertiesFilePaths, @NonNull Set<String> propertiesList, @NonNull SharedPropertiesParser sharedPropertiesParser) {
        mContext = context.getApplicationContext();
        mPropertiesFilePaths = propertiesFilePaths;
        mPropertiesList = propertiesList;
        mSharedPropertiesParser = sharedPropertiesParser;
        loadTermuxPropertiesFromDisk();
    }

    /**
     * Reload the termux properties from disk into an in-memory cache.
     */
    public synchronized void loadTermuxPropertiesFromDisk() {
        // Properties files must be searched everytime since no file may exist when constructor is
        // called or a higher priority file may have been created afterward. Otherwise, if no file
        // was found, then default props would keep loading, since mSharedProperties would be null. #2836
        mPropertiesFile = SharedProperties.getPropertiesFileFromList(mPropertiesFilePaths);
        mSharedProperties = null;
        mSharedProperties = new SharedProperties(mContext, mPropertiesFile, mPropertiesList, mSharedPropertiesParser);
        mSharedProperties.loadPropertiesFromDisk();

    }

    /**
     * Get the internal {@link Object} value for the key passed from the {@link #mPropertiesFile} file.
     * If cache is {@code true}, then value is returned from the {@link HashMap <>} in-memory cache,
     * so a call to {@link #loadTermuxPropertiesFromDisk()} must be made before this.
     *
     * @param key The key to read from the {@link HashMap<>} in-memory cache.
     * @param cached If {@code true}, then the value is returned from the the {@link HashMap <>} in-memory cache,
     *               but if the value is null, then an attempt is made to return the default value.
     *               If {@code false}, then the {@link Properties} object is read directly from the file
     *               and internal value is returned for the property value against the key.
     * @return Returns the {@link Object} object. This will be {@code null} if key is not found or
     * the object stored against the key is {@code null}.
     */
    public Object getInternalPropertyValue(String key, boolean cached) {
        Object value;
        if (cached) {
            value = mSharedProperties.getInternalProperty(key);
            // If the value is not null since key was found or if the value was null since the
            // object stored for the key was itself null, we detect the later by checking if the key
            // exists in the map.
            if (value == null && !mSharedProperties.getInternalProperties().containsKey(key)) {
                // This should not happen normally unless mMap was modified after the
                // {@link #loadTermuxPropertiesFromDisk()} call
                // A null value can still be returned by
                // {@link #getInternalPropertyValueFromValue(Context,String,String)} for some keys
                value = getInternalTermuxPropertyValueFromValue(key, null);
            }
            return value;
        } else {
            // We get the property value directly from file and return its internal value
            return getInternalTermuxPropertyValueFromValue(key, mSharedProperties.getProperty(key, false));
        }
    }

    /**
     * The class that implements the {@link SharedPropertiesParser} interface.
     */
    public static class SharedPropertiesParserClient implements SharedPropertiesParser {


        @NonNull
        @Override
        public Properties preProcessPropertiesOnReadFromDisk( @NonNull Properties properties) {
            return properties;
        }

        /**
         * Override the
         * {@link SharedPropertiesParser#getInternalPropertyValueFromValue(Context,String,String)}
         * interface function.
         */
        @Override
        public Object getInternalPropertyValueFromValue(String key, String value) {
            return getInternalTermuxPropertyValueFromValue(key, value);
        }
    }

    /**
     * A static function that should return the internal termux {@link Object} for a key/value pair
     * read from properties file.
     *
     * @param key The key for which the internal object is required.
     * @param value The literal value for the property found is the properties file.
     * @return Returns the internal termux {@link Object} object.
     */
    public static Object getInternalTermuxPropertyValueFromValue(String key, String value) {
        if (key == null)
            return null;
        /*
          For keys where a MAP_* is checked by respective functions. Note that value to this function
          would actually be the key for the MAP_*:
          - If the value is currently null, then searching MAP_* should also return null and internal default value will be used.
          - If the value is not null and does not exist in MAP_*, then internal default value will be used.
          - If the value is not null and does exist in MAP_*, then internal value returned by map will be used.
         */
        switch(key) {
            /* int */
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR:
                return getBellBehaviourInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
                return getTerminalCursorBlinkRateInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE:
                return getTerminalCursorStyleInternalPropertyValueFromValue(value);
             case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return getTerminalTranscriptRowsInternalPropertyValueFromValue(value);
            /* float */
            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                return getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value);
            /* Integer (may be null) */
             /* String (may be null) */
            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY:
                return getDefaultWorkingDirectoryInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR:
                return getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value);
            default:
                // default false boolean behaviour
                if (TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return SharedProperties.getBooleanValueForStringValue(value, false);
                // default true boolean behaviour
                if (TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return SharedProperties.getBooleanValueForStringValue(value, true);
                else
                    // default inverted false boolean behaviour
                    //else if (TermuxPropertyConstants.TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    //    return (boolean) SharedProperties.getInvertedBooleanValueForStringValue(key, value, false, true, LOG_TAG);
                    // default inverted true boolean behaviour
                    // else if (TermuxPropertyConstants.TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    //    return (boolean) SharedProperties.getInvertedBooleanValueForStringValue(key, value, true, true, LOG_TAG);
                    // just use String object as is (may be null)
                    return value;
        }
    }

    /**
     * Returns the internal value after mapping it based on
     * {@code TermuxPropertyConstants#MAP_BELL_BEHAVIOUR} if the value is not {@code null}
     * and is valid, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_BELL_BEHAVIOUR}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getBellBehaviourInternalPropertyValueFromValue(String value) {
        return (Integer) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT), TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN, TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX, true);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalCursorBlinkRateInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX, true);
    }

    /**
     * Returns the internal value after mapping it based on
     * {@link TermuxPropertyConstants#MAP_TERMINAL_CURSOR_STYLE} if the value is not {@code null}
     * and is valid, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalCursorStyleInternalPropertyValueFromValue(String value) {
        return (Integer) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE);
    }


    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalTranscriptRowsInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS, TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX, true);
    }


    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static float getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(DataUtils.getFloatFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX, true);
    }


    /**
     * Returns the path itself if a directory exists at it and is readable, otherwise returns
     *  {@link TermuxPropertyConstants#DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY}.
     *
     * @param path The {@link String} path to check.
     * @return Returns the internal value for value.
     */
    public static String getDefaultWorkingDirectoryInternalPropertyValueFromValue(String path) {
        if (path == null || path.isEmpty())
            return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY;
        File workDir = new File(path);
        if (!workDir.exists() || !workDir.isDirectory() || !workDir.canRead()) {
            // Fallback to default directory if user configured working directory does not exist,
            // is not a directory or is not readable.
            return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY;
        } else {
            return path;
        }
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(String value) {
        return (String) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR);
    }




    public boolean areTerminalSessionChangeToastsDisabled() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, true);
    }

    public boolean isEnforcingCharBasedInput() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, true);
    }

    public boolean shouldDrawBoldTextWithBrightColors() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DRAW_BOLD_TEXT_WITH_BRIGHT_COLORS, true);
    }


    public boolean shouldRunTermuxAmSocketServer() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, true);
    }

    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN, true);
    }

    public boolean isUsingCtrlSpaceWorkaround() {
        return (Boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_CTRL_SPACE_WORKAROUND, true);
    }

    public int getBellBehaviour() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, true);
    }

    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, true);
    }

    public int getTerminalCursorBlinkRate() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, true);
    }

    public int getTerminalCursorStyle() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, true);
    }

    public int getTerminalTranscriptRows() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, true);
    }


    public String getDefaultWorkingDirectory() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY, true);
    }

}
