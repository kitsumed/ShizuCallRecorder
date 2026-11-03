# Permissions Granting Issues
The behavior between Android versions, firmware, and OEMs is all different. Some manufacturers, as of writing, mostly Chinese ones (Xiaomi, Vivo, etc), also have very aggressive monitoring system applications that prevent you (and us) from doing a lot of things, which is bound to cause issues.
Other OEMs may include some toggles you have to enable or disable in your Developer Settings or another area. **Search engines are your friend**.

---

### I get an error message telling me all steps where exhausted when trying to get the a specific AppOps permission

If you get this error message, it means that in 99% of cases (not based on any stats), something on your Android system is reverting our permission changes. As of writing this, it has only been reported on [Chinese OEMs](https://github.com/kitsumed/ShizuCallRecorder/issues/41).
The `steps exhausted` message means that our application tried all available commands to attempt to get the AppOps permission, but they all failed. The name of permission that could not be granted is shown in the error message.

The only real solution would be, if available on your device, to **use a different call detection method**. If you know how to use ADB, **you can also try to manually grant the permission yourself**. For example, **assuming you are on the user profile 0**, you could try running ` adb shell appops set --user 0 com.kitsumed.shizucallrecorder android:manage_ongoing_calls allow` to attempt gaining `manage_ongoing_calls` permission.

If you manage to find a new way to grant yourself the permission, please feel free to create an issue on GitHub so we can implement the method you discovered. If you have reason to believe you are in the 1% of cases where our app is running the command incorrectly, you can also submit a bug report including the logs (or check them yourself).
