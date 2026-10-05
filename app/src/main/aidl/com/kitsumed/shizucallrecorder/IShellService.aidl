package com.kitsumed.shizucallrecorder;

import android.os.ParcelFileDescriptor;
import com.kitsumed.shizucallrecorder.ILogCallback;
import com.kitsumed.shizucallrecorder.IRecordingCallback;
import com.kitsumed.shizucallrecorder.data.recording.RecordingPlan;

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
     * Starts a recording session.
     *
     * The application provides the already-open output file descriptor,
     * the shell process owns the capture/processing/encoding/
     * output pipeline for the lifetime of the recording.
     *
     * @param outputFile The writable descriptor for the recording file.
     * @param plan The recording configuration.
     * @param callback The callback interface for recording events.
     */
    void startRecording(
        in ParcelFileDescriptor outputFile,
        in RecordingPlan plan,
        in IRecordingCallback callback
    ) = 2;

    /**
     * Stops the active recording session and finalizes the output.
    */
    void stopRecording() = 3;

    /**
     * Pauses the active recording session.
    */
    void pauseRecording() = 4;

    /**
     * Resumes the active recording session.
    */
    void resumeRecording() = 5;

    /**
     * Grants an AppOp permission at the package level for a specific user profile.
     * @param packageName The package name of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission  (e.g. 0 for the primary user)..
     * @return True if the command was successful (exit code 0 and no error output), false otherwise.
    */
    boolean grantAppOpByPackage(String packageName, String opName, int userProfileId) = 6;
    /**
     * Grants an AppOp permission at the app UID level (prioritized over package value) for a specific user profile.
     * @param uid The UID of the app to grant the permission to.
     * @param opName The AppOp operation name (e.g., "READ_PHONE_STATE").
     * @param userProfileId The user ID for which to grant the permission.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantAppOpByUid(int uid, String opName, int userProfileId) = 7;
    /**
     * Grants a role to a package in a specific user profile.
     * @param packageName The package name of the app to grant the role to.
     * @param roleName The name of the role to grant (e.g., "DIALER").
     * @param userProfileId The user ID for which to grant the role.
     * @return True if the command was successful, false otherwise.
    */
    boolean grantRole(String packageName, String roleName, int userProfileId) = 8;

    /**
     * Called by Shizuku when it wants to shut down this user service.
     * MUST call [kotlin.system.exitProcess] so the entire shell process is terminated.
     * This is the special transaction code used by Shizuku to "destroy" the process.
     */
    void destroy() = 16777114;
}
