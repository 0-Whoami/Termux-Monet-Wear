package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.shell.TermuxShellManager;

import java.util.HashMap;

/**
 * Environment for Termux {@link ExecutionCommand}.
 */
public class TermuxShellCommandShellEnvironment extends ShellCommandShellEnvironment {

    /**
     * Get shell environment containing info for Termux {@link ExecutionCommand}.
     */

    @Override
    public final HashMap<String, String> getEnvironment(Context currentPackageContext, ExecutionCommand executionCommand) {
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, executionCommand);

        if (ExecutionCommand.Runner.APP_SHELL.INSTANCE.getValue().equals(executionCommand.runner)) {
            ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__APP_SHELL_NUMBER_SINCE_APP_START, String.valueOf(TermuxShellManager.getAndIncrementAppShellNumberSinceAppStart()));
        } else if (ExecutionCommand.Runner.TERMINAL_SESSION.INSTANCE.getValue().equals(executionCommand.runner)) {
            ShellEnvironmentUtils.putToEnvIfSet(environment, ENV_SHELL_CMD__TERMINAL_SESSION_NUMBER_SINCE_APP_START, String.valueOf(TermuxShellManager.getAndIncrementTerminalSessionNumberSinceAppStart()));
        } else {
            return environment;
        }
        return environment;
    }
}
