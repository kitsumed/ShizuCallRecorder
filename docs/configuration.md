[Go back to the home documentation page](./SUPPORT.md)
# Configuration Guide

>[!NOTE]  
> This guide assumes you have met all [requirements](../README.md#requirements) and installed all the mentioned third-party applications.  
> This guide also assumes you have read the [disclaimer](../README.md#disclaimer) and are following the law.

## Introduction
Ever release of Android 4.4 KitKat (API 19) / Android 6, native 3rd-party phone call recording apps have been [killed](https://issuetracker.google.com/issues/37127141) for valid [privacy](https://issuetracker.google.com/issues/137210607#comment8) reasons, with
no [visible effort](https://issuetracker.google.com/issues/128677410) from Android/Google in the following years to provide developers with [any compromises or runtime permissions](https://issuetracker.google.com/issues/112602629). In fact, in the following years, they kept closing work-around developers had founds to bypass the restrictions.

In an Android 11 development build, they created and new permission [`ACCESS_CALL_AUDIO`](https://web.archive.org/web/20240803000244/https://developer.android.com/sdk/api_diff/r-dp2/changes/android.Manifest.permission) and accorded it to the default phone dialer (still limited but better than nothing) that would allow call recording, only to [quickly revert](https://issuetracker.google.com/issues/158923887) it while claiming, "[Feature has been postponed](https://android.googlesource.com/platform/frameworks/av/+/57a3769fb12dc8b2df457e0919850bd976716706) [...] [we're not doing this in R](https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/d67ba72fbe34573ec5b5e88ddfd3ef1086922d7b)"

Meanwhile, proprietary apps like the Google Dialer and OEM apps can use the restricted permission `CAPTURE_AUDIO_OUTPUT`, and some do have the recording feature. However, they often choose not to offer it to most users, or they add their own proprietary rules on top of it, even if it is legal in the user country. Their apps are also often privacy invasive with telemetry or tracking in general. 

Shizuku is the workaround to this issue. **I would recommend you read [Shizuku About Page](https://github.com/thedjchi/Shizuku/wiki/About) wiki to have a very basic understanding** of why we need this.
If you are curious about the security aspects and how it works in more detail, you can read my blog post: **[What Is Shizuku? How Does It Work? Security Implications?](https://kitsumed.github.io/blog/posts/what-is-shizuku_how-does-it-work_security-implications/)**.  

Even if you do not understand everything, it will give you a basic, general understanding of why we must use it and what it allows us to do. This is probably a lot to read, but it's a one-time thing.

## Installing Shizuku
Once you're ready **to install Shizuku**, **follow the setup instructions on the [Shizuku Setup Page Wiki](https://github.com/thedjchi/Shizuku/wiki/Setup)**. Please **read them carefully**, there is a lot of **important information about its behavior and recommended settings** to help maintain good device security.

>[!TIP]
> Unless you know what you are doing, **you should enable "Auto-Disable USB Debugging" in Shizuku** to reduce security risks.

>[!WARNING]
> Shizuku **TCP Mode** allows you to start and keep alive Shizuku without a Wi-Fi connection, but it also comes with increased security risks. Please read the Shizuku wiki for more information. Additionally, some information is available in my previously mentioned blog post about Shizuku. You might also want to set another TCP port than the default `5555` one.


## Configuring ShizuCallRecorder
### The Two Method
There are two ways to run ShizuCallRecorder, both dependent on how you plan to use Shizuku:

- If you plan on always having Shizuku running in the background and using it with other applications,  
  - You want [method 1](#method-1).
- If you just learned about Shizuku, want it to only run during call recording, or want to do it the safest way possible,  
  - You want [method 2](#method-2) **(recommended)**.


---
### Method 1
This method is for users who plan to have Shizuku always running in the background. (**Not recommended for general users**).

>[!TIP]
> If you want to record the start of a call and avoid the bigger delay of a few seconds before recording begins, you should use this mode.

This is the default behavior of ShizuCallRecorder. You can enable certain settings in Shizuku to reduce the chance of failures.

1. You **need** to enable **Start on Boot**.  
2. You **may** want to enable **Watchdog** to automatically restart it on crashes (this create a persistent notification in the background).  
3. ⚠️ You **may** want to enable **TCP Mode** to start and/or keep Shizuku alive without a Wi-Fi connection (**LTE/DATA is not a Wifi connection**).

>[!NOTE]
> Having Shizuku always running in the background **increases the period during which your device is at higher security risk** because ADB is enabled. Some applications, such as banking apps, may also detect Shizuku or USB Debugging and refuse to work. In those cases, you should use [method 2](#method-2).

---
### Method 2
This method is for users who plan to have Shizuku only running when needed. (**Recommended**).

>[!NOTE]
> The main disadvantage of this method is that there may be a delay before recording starts since we need to wait for Shizuku to start. Depending on your device and Shizuku, this can take anywhere from 1 to 20+ seconds. In my tests, it is generally fast, and the advantages outweigh the few seconds of delay at the start of the call.

To enable this behavior in ShizuCallRecorder, go to the **Security** section and enable **Manage Shizuku**.  
You will then need to open the Shizuku application, find the **auth key for intents**, and copy it back into ShizuCallRecorder.  
This step is required so that the app can control Shizuku on your behalf.

Once completed, the application will behave as follows:  
- When you start a call, **Shizuku will automatically be started**, even if not recording.  
- When you end the call, **Shizuku will automatically be stopped**.

To have more control over when Shizuku is enabled, you can enable **Start only when recording**.  
When this option is enabled, the application will behave as follows:
- When you start a call, a notification will be shown, but **Shizuku will NOT start**.  
- If you press the start recording button, or if an auto-recording rule is triggered, **Shizuku will start automatically**.  
- When you end the call, **Shizuku will automatically stop**.

> If you plan on using this method, note that its reliability could be improved even further! You can support the issue at [GitHub](https://github.com/thedjchi/Shizuku/issues/158). Please do not harass the maintainer, show your support by reacting with an emoji or explaining why this would be useful to you (in this case, for this application).

>[!WARNING]
> As mentioned earlier, recording may take a few seconds because Shizuku needs to start. If you enable **Start only when recording**, be aware that audio will **not start recording immediately** when you press the button.

## Recording each side of the call separately
In the **Audio configuration** section of Settings, enabling **Record each side separately** captures your side (uplink) and the other party's side (downlink) as two separate, time-synced files instead of one mixed recording. This is intended for transcription workflows where you want each speaker clearly attributed.

While this is enabled, the **Audio source** dropdown is ignored (the uplink/downlink sources are used automatically), and two scrcpy-server processes run concurrently for the duration of the call.

If a transcription tool you use requires a single stereo file with one speaker per channel instead of two files, you can combine the two outputs afterward with `ffmpeg`, without any quality loss:
```
ffmpeg -i call_..._uplink.opus -i call_..._downlink.opus -filter_complex amerge -ac 2 call_stereo.opus
```
