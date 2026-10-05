package com.kitsumed.shizucallrecorder;
import com.kitsumed.shizucallrecorder.data.recording.RecordingFailure;

/**
 * Callback interface for receiving recording session events on the shell-side.
 */
interface IRecordingCallback {

    /**
     * Reports a failure of the active recording session. The session should be considered invalid/ended if received.
     *
     * This callback is associated with the recording session that supplied it
     * to startRecording().
     */
    void onRecordingFailure(
        in RecordingFailure error
    );
}