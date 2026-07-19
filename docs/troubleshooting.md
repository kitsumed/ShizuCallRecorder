This is the troubleshooting docs. Each titles are separating different section of the application. You can use the burger menu on the right to navigate quickly.

If you can't find a response to your issue here and need help using, or configuring Shizuku and/or ShizuCallRecorder. Please use the correct community support forum. [Shizuku](https://github.com/thedjchi/Shizuku/discussions/categories/q-a) or [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder/discussions).

# Permissions Granting Issues
The behavior between Android versions, firmware, and OEMs is all different. Some manufacturers, as of writing, mostly Chinese ones (Xiaomi, Vivo, etc), also have very aggressive monitoring system applications that prevent you (and us) from doing a lot of things, which is bound to cause issues.
Other OEMs may include some toggles you have to enable or disable in your Developer Settings or another area. **Search engines are your friend**.

---

### I get an error message telling me all steps where exhausted when trying to get the a specific AppOps permission

If you get this error message, it means that in 99% of cases (not based on any stats), something on your Android system is reverting our permission changes. As of writing this, it has only been reported on [Chinese OEMs](https://github.com/kitsumed/ShizuCallRecorder/issues/41).
The `steps exhausted` message means that our application tried all available commands to attempt to get the AppOps permission, but they all failed. The name of permission that could not be granted is shown in the error message.

The only real solution would be, if available on your device, to **use a different call detection method**. If you know how to use ADB, you can also try to manually grant the permission yourself.

If you manage to find a new way to grant yourself the permission, please feel free to create an issue on GitHub so we can implement the method you discovered. If you have reason to believe you are in the 1% of cases where our app is running the command incorrectly, you can also submit a bug report including the logs (or check them yourself).

---

# General Application Issues

### The applications does not start when in the background

Some OEMs impose very aggresive restrictions. If you have issues with ShizuCallRecorder getting killed in background or not starting when you receive a call, **please check [dontkillmyapp](https://dontkillmyapp.com/) for specific instructions** for you phone.
Also **ensure that the application is FULLY excluded from battery saving**.

---

### The recording of my calls only have silence or one side of the call audio!

Recording calls on Android is complex since every OEM does things differently. This means that on some devices, everything will almost always work, while on others, it will only work under certain conditions, or not at all.

**You can try** disabling **VoIP** or **Wi-Fi Calling**. Please also note that third-party applications (like WhatsApp or Facebook) often use VoIP, which will often result in silent recordings. For carrier phone calls, you should have an option available in your phone settings.

You can also look for existing discussions or issues on this project to see if other users have already had the same issue with your device. They may have shared a solution!

---

### Can we integrate specific file name formats to support native phone application integrations?

While we do not and will not offer pre-defined templates for specific apps, we provide the ability to customize your filename format by including multiple details like the hour, contact name, or, when available, the name of the application that made the call.

<details>
<summary><b>See the list of templates reported to work by users</b></summary>

| Target OEM / OS Version | Template Type | Required Storage Path | Template Format | Filename Example |
| :--- | :--- | :--- | :--- | :--- |
| Honor (MagicOS 10 / Android 16) | **Standard (Number only)** | `/storage/emulated/0/Sounds/CallRecord/` | `{phone_number}_{date:year}{date:month}{date:day}{date:hours}{date:minutes}{date:seconds}` | `+33612345678_20260719093015.ogg` |
| Honor (MagicOS 10 / Android 16) | **With Contact Name** | `/storage/emulated/0/Sounds/CallRecord/` | `{caller_name}@{phone_number}_{date:year}{date:month}{date:day}{date:hours}{date:minutes}{date:seconds}` | `JohnDoe@+33612345678_20260719093015.ogg` |
</details>
