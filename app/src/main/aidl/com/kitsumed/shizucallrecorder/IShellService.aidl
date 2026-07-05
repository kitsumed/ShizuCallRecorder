package com.kitsumed.shizucallrecorder;

import android.os.ParcelFileDescriptor;
import com.kitsumed.shizucallrecorder.ILogCallback;

interface IShellService {
    /**
     * Set the logger callback for the AppLogger.
     * Every logs message from the service will be sent to this callback.
     * Allow inter-process logging from the service to the app.
     *
     * @param appLoggerCallback The callback interface for logging messages.
     * @param isRedactionEnabled If true, sensitive information in logs will be redacted.
     */
    void setLogCallback(
        ILogCallback appLoggerCallback,
        boolean isRedactionEnabled
    ) = 1;

    /**
     * Starts the audio-capture pipeline.
     *
     * @param audioSource        scrcpy audio_source parameter (e.g. "mic-voice-communication").
     * @param audioCodec         scrcpy audio_codec parameter (e.g. "opus", "aac").
     * @param audioBitRate       scrcpy audio_bit_rate in bps (e.g. 16000 for 16 kbps Opus).
     * @param serverPath      Absolute path to scrcpy-server.jar in shared storage.
     * @param isDebuggingModeEnabled  When true, logs relay throughput every second.
     * @return The read-end [ParcelFileDescriptor] of the audio pipe, or null on failure.
     */
    ParcelFileDescriptor startRecording(
        String audioSource,
        String audioCodec,
        int audioBitRate,
        String serverPath,
        boolean isDebuggingModeEnabled // For debugging purposes, if true, the service will log additional information and change some logging behavior.
    ) = 2;

    /**
     * Installs the bundled scrcpy-server JAR into a path writable/readable by the shell process.
     *
     * @param serverData Read-end of a pipe containing the server JAR bytes.
     * @param serverPath Destination path owned by the shell process.
     * @return True if the destination exists and matches the expected SHA-256.
     */
    boolean installServerFile(
        in ParcelFileDescriptor serverData,
        String serverPath
    ) = 7;

    /**
     * Stops the audio capture pipeline and releases all resources.
     */
    void stopRecording() = 3;

    /**
     * Grants an AppOp permission at the package level for a specific user profile.
     * @param packageName The package name of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission  (e.g. 0 for the primary user)..
     * @return True if the command was successful (exit code 0 and no error output), false otherwise.
    */
    boolean grantAppOpByPackage(String packageName, String opName, int userProfileId) = 4;
    /**
     * Grants an AppOp permission at the app UID level (prioritized over package value) for a specific user profile.
     * @param uid The UID of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantAppOpByUid(int uid, String opName, int userProfileId) = 5;
    /**
     * Grants a role to a package in a specific user profile.
     * @param packageName The package name of the app to grant the role to.
     * @param roleName The name of the role to grant (e.g., "DIALER").
     * @param userProfileId The user ID for which to grant the role.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantRole(String packageName, String roleName, int userProfileId) = 6;

    /**
     * Called by Shizuku when it wants to shut down this user service.
     * MUST call [kotlin.system.exitProcess] so the entire shell process is terminated.
     * This is the special transaction code used by Shizuku to "destroy" the process.
     */
    void destroy() = 16777114;
}
