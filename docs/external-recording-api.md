[Go back to the home documentation page](./SUPPORT.md)
# External Recording API

This page is for **developers of third-party applications** (ex: a dialer) that want to start and stop a ShizuCallRecorder recording, for example from their own "Record" button.

>[!IMPORTANT]
> This API **does not detect calls** and **cannot record outside a call**. ShizuCallRecorder must already have detected the call with its own call detection (it is in standby or recording). The API only replaces the user pressing the "Record" button of the ShizuCallRecorder notification.

## Requirements

1. ShizuCallRecorder is installed and configured (Shizuku, recording folder, call detection). Everything that works with the notification "Record" button will work with the API.
2. Your app declares and requests the runtime permission `com.kitsumed.shizucallrecorder.permission.CONTROL_RECORDING`, and **the user granted it**.
3. The user enabled **Settings > Security > External recording control** in ShizuCallRecorder. It is disabled by default.

### Requesting the permission
```xml
<!-- AndroidManifest.xml of your app -->
<uses-permission android:name="com.kitsumed.shizucallrecorder.permission.CONTROL_RECORDING" />
```
The permission is a [runtime (dangerous) permission](https://developer.android.com/guide/topics/permissions/overview#runtime), request it like any other one:
```kotlin
requestPermissions(arrayOf("com.kitsumed.shizucallrecorder.permission.CONTROL_RECORDING"), REQUEST_CODE)
```
>[!NOTE]
> The permission only exists once ShizuCallRecorder is installed. If ShizuCallRecorder is installed after your app, request the permission again.

## Commands

Commands are **explicit broadcasts** sent to the ShizuCallRecorder package. Android refuses to deliver them if your app was not granted the permission.

| Action | Description |
| :----- | :---------- |
| `com.kitsumed.shizucallrecorder.api.START_RECORDING` | Start recording the currently detected call. |
| `com.kitsumed.shizucallrecorder.api.STOP_RECORDING` | Stop the current recording. The call stays in standby, so `START_RECORDING` can be sent again during the same call (a new file is created). |

```kotlin
val intent = Intent("com.kitsumed.shizucallrecorder.api.START_RECORDING")
    .setPackage("com.kitsumed.shizucallrecorder")
context.sendBroadcast(intent)
```

Commands are idempotent:

| Command | Current state | Result |
| :------ | :------------ | :----- |
| `START_RECORDING` | Call detected, not recording | Recording starts (`STARTING`, then `RECORDING`) |
| `START_RECORDING` | Starting / recording | Nothing changes, current state is broadcasted again (`STARTING` / `RECORDING`) |
| `START_RECORDING` | No call detected | `FAILED` with `NO_ACTIVE_CALL` |
| `STOP_RECORDING` | Recording (paused or not) | Recording stops (`STOPPING`, then `STOPPED`) |
| `STOP_RECORDING` | Starting | `FAILED` with `INVALID_STATE`, send it again once `RECORDING` is received |
| `STOP_RECORDING` | Not recording / no call detected | Nothing changes, `STOPPED` is broadcasted |

When the call ends, ShizuCallRecorder stops the recording by itself, like it already does.

## Recording state broadcasts

ShizuCallRecorder sends `com.kitsumed.shizucallrecorder.api.RECORDING_STATE_CHANGED` to **every app holding the permission**, for every recording started or stopped, including those started automatically, from the notification or from the floating overlay. Nothing is sent while "External recording control" is disabled, except the `EXTERNAL_CONTROL_DISABLED` failure.

The broadcast is sent explicitly to your package, so both a manifest receiver and a context-registered receiver work.

| Extra | Type | Description |
| :---- | :--- | :---------- |
| `com.kitsumed.shizucallrecorder.api.extra.STATE` | String | One of the states below. |
| `com.kitsumed.shizucallrecorder.api.extra.IS_PAUSED` | Boolean | With `RECORDING`, true if the user paused the recording. |
| `com.kitsumed.shizucallrecorder.api.extra.ERROR_CODE` | String | With `FAILED`, one of the error codes below. |
| `com.kitsumed.shizucallrecorder.api.extra.ERROR_MESSAGE` | String | With `FAILED`, human-readable description (may be localized, do not parse it). |

| State | Meaning |
| :---- | :------ |
| `STARTING` | A recording is starting (waiting for Shizuku). |
| `RECORDING` | The recording is in progress. Sent again when it is paused or resumed. |
| `STOPPING` | The recording is being finalized. |
| `STOPPED` | No recording is in progress. |
| `FAILED` | A command was rejected or a recording failed. A failure during a recording is followed by `STOPPING`/`STOPPED`, a failure while starting is followed by `STOPPED`. |

Usual sequences:
- Start: `STARTING` → `RECORDING`
- Stop: `STOPPING` → `STOPPED`
- Failed start: `STARTING` → `FAILED` → `STOPPED`

### Error codes

| Code | Meaning |
| :--- | :------ |
| `EXTERNAL_CONTROL_DISABLED` | The user did not enable "External recording control". |
| `NO_ACTIVE_CALL` | ShizuCallRecorder has not detected a call. |
| `INVALID_STATE` | The command cannot be executed now (ex: STOP while starting). |
| `INVALID_COMMAND` | Unknown action. |
| `SHIZUKU_UNAVAILABLE` | Shizuku is not running or cannot be reached. |
| `SHIZUKU_PERMISSION_DENIED` | ShizuCallRecorder is not allowed to use Shizuku. |
| `SHIZUKU_AUTH_KEY_MISSING` | "Auto-manage Shizuku" is enabled without an authentication key. |
| `SHIZUKU_DISCONNECTED` | Shizuku stopped during the recording. |
| `RECORDING_INIT_FAILED` | The recording file could not be created (output folder missing, etc). |
| `RECORDING_ENGINE_FAILED` | The audio capture failed. |
| `INTERNAL_ERROR` | Unexpected error. |

### Receiving the state
```xml
<receiver android:name=".RecordingStateReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.kitsumed.shizucallrecorder.api.RECORDING_STATE_CHANGED" />
    </intent-filter>
</receiver>
```
```kotlin
class RecordingStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra("com.kitsumed.shizucallrecorder.api.extra.STATE")
        val errorCode = intent.getStringExtra("com.kitsumed.shizucallrecorder.api.extra.ERROR_CODE")
        // Update your "Record" button here.
    }
}
```
>[!WARNING]
> Your receiver must be exported to receive the broadcast, which means any app can also send it a fake state. Only use it to update your UI, never to make security decisions.

## Security model

- **Explicit user consent, twice**: the user must grant the runtime permission to your app from the Android dialog, **and** enable "External recording control" in ShizuCallRecorder. Both can be revoked at any time.
- **Unauthorized apps are rejected by Android**: the command receiver is protected by the permission, broadcasts from apps without it are never delivered.
- **The recording service is not exported**: commands go through the protected receiver, which only forwards them when a call is already detected. External apps cannot start the service or record outside a call.
- **No silent recording**: the API uses the same recording service as the notification button. The foreground notification (and the floating overlay, if enabled) is always visible while recording.
- **Shizuku is never bypassed**: ShizuCallRecorder still needs the user to allow it in Shizuku, the external app never gets access to Shizuku.
- **No recording data is shared**: the state broadcasts do not contain the phone number, the contact name, or the recording file.
