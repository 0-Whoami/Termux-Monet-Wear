package com.termux.shared.shell.command.environment;

import android.content.Context;

import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;

import java.util.HashMap;

/**
 * Environment for Unix-like systems.
 * <p><a href="
 * ">* https://manpages.debian.org/testing/manpages/environ.7.en.</a>html<a href="
 * ">* https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap08.</a>html
 */
public abstract class UnixShellEnvironment {

    /**
     * Environment variable for the path of the user's home directory.
     */
    public static final String ENV_HOME = "HOME";
    /**
     * Names for common/supported login shell binaries.
     */
    public static final String[] LOGIN_SHELL_BINARIES = new String[]{"login", "bash", "zsh", "fish", "sh"};
    /**
     * Environment variable for the represent the sequence of directory paths separated with
     * colons ":" that should be searched in for dynamic shared libraries to link programs against.
     */
    protected static final String ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH";
    /**
     * Environment variable for the represent the sequence of directory path prefixes separated with
     * colons ":" that certain functions and utilities apply in searching for an executable file
     * known only by a filename.
     */
    protected static final String ENV_PATH = "PATH";
    /**
     * Environment variable for the path of a directory made available for programs that need a place
     * to create temporary files.
     */
    protected static final String ENV_TMPDIR = "TMPDIR";
    /**
     * Environment variable for the terminal's colour capabilities.
     */
    static final String ENV_COLORTERM = "COLORTERM";
    /**
     * Environment variable for the locale category for native language, local customs, and coded
     * character set in the absence of the LC_ALL and other LC_* environment variables.
     */
    static final String ENV_LANG = "LANG";
    /**
     * Environment variable for the absolute path of the current working directory. It shall not
     * contain any components that are dot or dot-dot. The value is set by the cd utility, and by
     * the sh utility during initialization.
     */
    static final String ENV_PWD = "PWD";
    /**
     * Environment variable for the terminal type for which output is to be prepared. This information
     * is used by utilities and application programs wishing to exploit special capabilities specific
     * to a terminal. The format and allowable values of this environment variable are unspecified.
     */
    static final String ENV_TERM = "TERM";

    public abstract String getDefaultWorkingDirectoryPath();

    public abstract HashMap<String, String> getEnvironment(Context currentPackageContext, boolean isFailSafe);

    public abstract String getDefaultBinPath();

    public abstract HashMap<String, String> setupShellCommandEnvironment(Context currentPackageContext, ExecutionCommand executionCommand);

    public String[] setupShellCommandArguments(String executable, String[] arguments) {
        return ShellUtils.setupShellCommandArguments(executable, arguments);
    }
}
