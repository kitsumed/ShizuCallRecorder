package com.kitsumed.shizucallrecorder;

import android.os.ParcelFileDescriptor;
import com.kitsumed.shizucallrecorder.ILogCallback;

interface IShellService {
    ParcelFileDescriptor startRecording(
        String audioSource,
        String audioCodec,
        int audioBitRate,
        String serverPath,
        boolean isDebuggingModeEnabled, // For debugging purposes, if true, the service will log additional information and change some logging behavior.
        ILogCallback appLoggerCallback
    ) = 1;

    void stopRecording() = 2;

    boolean isRecording() = 3;

    /**
     * Executes `appops set` command to grant an appop permission to a package.
     * @param packageName The target package to grant the appop to (e.g. "com.kitsumed.shizucallrecorder").
     * @param opName The appop name to grant (e.g. "MANAGE_ONGOING_CALLS").
     * @param userProfileId The user profile ID to grant the appop for (e.g. 0 for the primary user).
     * @return true if the command executed successfully (exit code 0), false otherwise.
     */
    boolean grantAppOps(String packageName, String opName, int userProfileId) = 4;

    // The special Shizuku transaction code for "destroy" process
    void destroy() = 16777114;
}
