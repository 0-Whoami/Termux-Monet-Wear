package com.termux.shared.android;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.reflection.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;

public class PackageUtils {


    /**
     * Get the {@link Context} for the package name with {@link Context#CONTEXT_RESTRICTED} flags.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the {@code packageName}.
     * @param packageName The package name whose {@link Context} to get.
     * @return Returns the {@link Context}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackage(@NonNull final Context context, String packageName) {
        return getContextForPackage(context, packageName, Context.CONTEXT_RESTRICTED);
    }

    /**
     * Get the {@link Context} for the package name.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the {@code packageName}.
     * @param packageName The package name whose {@link Context} to get.
     * @param flags The flags for {@link Context} type.
     * @return Returns the {@link Context}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackage(@NonNull final Context context, String packageName, int flags) {
        try {
            return context.createPackageContext(packageName, flags);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the {@link Context} for a package name.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the {@code packageName}.
     * @param packageName The package name whose {@link Context} to get.
     * @return Returns the {@link Context}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackageOrExitApp(@NonNull Context context, String packageName) {
        return getContextForPackage(context, packageName);
    }

    /**
     * Get the {@link PackageInfo} for the package associated with the {@code packageName}.
     *
     * @param context The {@link Context} for operations.
     * @param packageName The package name of the package.
     * @return Returns the {@link PackageInfo}. This will be {@code null} if an exception is raised.
     */
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getPackageInfoForPackage(context, packageName, 0);
    }

    /**
     * Get the {@link PackageInfo} for the package associated with the {@code packageName}.
     * <p>
     * Also check {isAppInstalled(Context, String, String) if targetting targeting sdk
     * `30` (android `11`) since {@link PackageManager.NameNotFoundException} may be thrown.
     * <p>
     * @param context The {@link Context} for operations.
     * @param packageName The package name of the package.
     * @param flags The flags to pass to {@link PackageManager#getPackageInfo(String, int)}.
     * @return Returns the {@link PackageInfo}. This will be {@code null} if an exception is raised.
     */
    @Nullable
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context, @NonNull final String packageName, final int flags) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, flags);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Get the {@link ApplicationInfo} for the {@code packageName}.
     *
     * @param context The {@link Context} for operations.
     * @param packageName The package name of the package.
     * @return Returns the {@link ApplicationInfo}. This will be {@code null} if an exception is raised.
     */
    @Nullable
    public static ApplicationInfo getApplicationInfoForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getApplicationInfoForPackage(context, packageName, 0);
    }

    /**
     * Get the {@link ApplicationInfo} for the {@code packageName}.
     * <p>
     * Also check {isAppInstalled(Context, String, String) if targetting targeting sdk
     * `30` (android `11`) since {@link PackageManager.NameNotFoundException} may be thrown.
     * <p>
     * @param context The {@link Context} for operations.
     * @param packageName The package name of the package.
     * @param flags The flags to pass to {@link PackageManager#getApplicationInfo(String, int)}.
     * @return Returns the {@link ApplicationInfo}. This will be {@code null} if an exception is raised.
     */
    @Nullable
    public static ApplicationInfo getApplicationInfoForPackage(@NonNull final Context context, @NonNull final String packageName, final int flags) {
        try {
            return context.getPackageManager().getApplicationInfo(packageName, flags);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Get the {@code seInfo} {@link Field} of the {@link ApplicationInfo} class.
     * <p>
     * String retrieved from the seinfo tag found in selinux policy. This value can be set through
     * the mac_permissions.xml policy construct. This value is used for setting an SELinux security
     * context on the process as well as its data directory.
     * <p><a href="
     ">* https://cs.android.com/android/platform/superproject/+/android-7.1.0_r1:frameworks/base/core/java/android/content/pm/ApplicationInfo.ja</a>va;l=609<a href="
     ">* https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/content/pm/ApplicationInfo.ja</a>va;l=981<a href="
     ">* https://cs.android.com/android/platform/superproject/+/android-7.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.ja</a>va;l=282<a href="
     ">* https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.ja</a>va;l=375<a href="
     ">* https://cs.android.com/android/_/android/platform/frameworks/base/+/be0b8896d1bc385d4c8fb54c21929745</a>935dcbea
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns the selinux info or {@code null} if an exception was raised.
     */
    @Nullable
    public static String getApplicationInfoSeInfoForPackage(@NonNull final ApplicationInfo applicationInfo) {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions();
        try {
            return (String) ReflectionUtils.invokeField(ApplicationInfo.class, "seInfo", applicationInfo).value;
        } catch (Exception e) {
            // ClassCastException may be thrown
            return null;
        }
    }

    /**
     * Get the {@code seInfoUser} {@link Field} of the {@link ApplicationInfo} class.
     * <p>
     * Also check {@link #getApplicationInfoSeInfoForPackage(ApplicationInfo)}.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns the selinux info user or {@code null} if an exception was raised.
     */
    @Nullable
    public static String getApplicationInfoSeInfoUserForPackage(@NonNull final ApplicationInfo applicationInfo) {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions();
        try {
            return (String) ReflectionUtils.invokeField(ApplicationInfo.class, "seInfoUser", applicationInfo).value;
        } catch (Exception e) {
            // ClassCastException may be thrown
            return null;
        }
    }

    /**
     * Get the uid for the package associated with the {@code context}.
     *
     * @param context The {@link Context} for the package.
     * @return Returns the uid.
     */
    public static int getUidForPackage(@NonNull final Context context) {
        return getUidForPackage(context.getApplicationInfo());
    }

    /**
     * Get the uid for the package associated with the {@code applicationInfo}.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns the uid.
     */
    public static int getUidForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.uid;
    }

    /**
     * Get the {@code targetSdkVersion} for the package associated with the {@code applicationInfo}.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns the {@code targetSdkVersion}.
     */
    public static int getTargetSDKForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.targetSdkVersion;
    }

    /**
     * Get the base apk path for the package associated with the {@code applicationInfo}.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns the base apk path.
     */
    public static String getBaseAPKPathForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.publicSourceDir;
    }

    /**
     * Check if the app associated with the {@code applicationInfo} has {@link ApplicationInfo#FLAG_DEBUGGABLE}
     * set.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns {@code true} if app is debuggable, otherwise {@code false}.
     */
    public static boolean isAppForPackageADebuggableBuild(@NonNull final ApplicationInfo applicationInfo) {
        return (0 != (applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE));
    }

    /**
     * Check if the app associated with the {@code applicationInfo} has {@link ApplicationInfo#FLAG_EXTERNAL_STORAGE}
     * set.
     *
     * @param applicationInfo The {@link ApplicationInfo} for the package.
     * @return Returns {@code true} if app is installed on external storage, otherwise {@code false}.
     */
    public static boolean isAppInstalledOnExternalStorage(@NonNull final ApplicationInfo applicationInfo) {
        return (0 != (applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE));
    }

    /**
     * Get the {@code versionCode} for the {@code packageName}.
     *
     * @param packageInfo The {@link PackageInfo} for the package.
     * @return Returns the {@code versionCode}. This will be {@code null} if an exception is raised.
     */
    @Nullable
    public static Integer getVersionCodeForPackage(@Nullable final PackageInfo packageInfo) {
        return packageInfo != null ? (int) packageInfo.getLongVersionCode() : null;
    }

    /**
     * Get the {@code versionName} for the {@code packageName}.
     *
     * @param packageInfo The {@link PackageInfo} for the package.
     * @return Returns the {@code versionName}. This will be {@code null} if an {@code packageInfo}
     * is {@code null}.
     */
    @Nullable
    public static String getVersionNameForPackage(@Nullable final PackageInfo packageInfo) {
        return packageInfo != null ? packageInfo.versionName : null;
    }



    /**
     * Get the serial number for the user for the package associated with the {@code context}.
     *
     * @param context The {@link Context} for the package.
     * @return Returns the serial number. This will be {@code null} if failed to get it.
     */
    @Nullable
    public static Long getUserIdForPackage(@NonNull Context context) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null)
            return null;
        return userManager.getSerialNumberForUser(UserHandle.getUserHandleForUid(getUidForPackage(context)));
    }

    /**
     * Get the profile owner package name for the current user.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the profile owner package name. This will be {@code null} if failed to get it
     * or no profile owner for the current user.
     */
    @Nullable
    public static String getProfileOwnerPackageNameForUser(@NonNull Context context) {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (devicePolicyManager == null)
            return null;
        List<ComponentName> activeAdmins = devicePolicyManager.getActiveAdmins();
        if (activeAdmins != null) {
            for (ComponentName admin : activeAdmins) {
                String packageName = admin.getPackageName();
                if (devicePolicyManager.isProfileOwnerApp(packageName))
                    return packageName;
            }
        }
        return null;
    }

    /**
     * Get the process id of the main app process of a package. This will work for sharedUserId. Note
     * that some apps have multiple processes for the app like with `android:process=":background"`
     * attribute in AndroidManifest.xml.
     *
     * @param context The {@link Context} for operations.
     * @param packageName The package name of the process.
     * @return Returns the process if found and running, otherwise {@code null}.
     */
    @Nullable
    public static String getPackagePID(final Context context, String packageName) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            List<ActivityManager.RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
            if (processInfos != null) {
                ActivityManager.RunningAppProcessInfo processInfo;
                for (int i = 0; i < processInfos.size(); i++) {
                    processInfo = processInfos.get(i);
                    if (processInfo.processName.equals(packageName))
                        return String.valueOf(processInfo.pid);
                }
            }
        }
        return null;
    }

}
