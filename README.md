# ShizuCallRecorder
[![GitHub Repo stars](https://img.shields.io/github/stars/kitsumed/ShizuCallRecorder?style=for-the-badge&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI%2BPHRpdGxlPnN0YXI8L3RpdGxlPjxwYXRoIGQ9Ik0xMiwxNy4yN0wxOC4xOCwyMUwxNi41NCwxMy45N0wyMiw5LjI0TDE0LjgxLDguNjJMMTIsMkw5LjE5LDguNjJMMiw5LjI0TDcuNDUsMTMuOTdMNS44MiwyMUwxMiwxNy4yN1oiIGZpbGw9IndoaXRlIiAvPjwvc3ZnPg%3D%3D&labelColor=gray&color=gold)](https://github.com/kitsumed/ShizuCallRecorder/graphs/traffic)
[![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/kitsumed/ShizuCallRecorder/total?style=for-the-badge&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI%2BPHRpdGxlPmRvd25sb2FkPC90aXRsZT48cGF0aCBkPSJNNSwyMEgxOVYxOEg1TTE5LDlIMTVWM0g5VjlINUwxMiwxNkwxOSw5WiIgZmlsbD0id2hpdGUiIC8%2BPC9zdmc%2B&label=Downloads&labelColor=gray&color=gold&)](https://github.com/kitsumed/ShizuCallRecorder/releases/)
[![GitHub Release](https://img.shields.io/github/v/release/kitsumed/ShizuCallRecorder?sort=semver&display_name=tag&style=for-the-badge&logo=testcafe&logoColor=white&label=Latest%20Release&labelColor=gray&color=blue)](https://github.com/kitsumed/ShizuCallRecorder/releases/latest)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/kitsumed/ShizuCallRecorder/build-app.yml?style=for-the-badge&label=Build%20Workflow)](https://github.com/kitsumed/ShizuCallRecorder/actions/workflows/build-app.yml)

The first **non-root FOSS call recorder app for Android 11+**! ShizuCallRecorder empowers ADB through Shizuku to use an [advanced list of permissions given to the shell application](https://android.googlesource.com/platform/frameworks/base/+/android16-release/packages/Shell/AndroidManifest.xml).
[Latest URL](https://cs.android.com/search?q=com.android.shell%20file:%2Fpackages%2FShell%2FAndroidManifest.xml).

It can also be seen as an on-device wrapper for [scrcpy-server](https://github.com/genymobile/scrcpy).

**This application is intended to be a very basic call recorder that focuses solely on call recording for phone carriers**.

>[!NOTE]
> I am not 100% opposed to adding support for third-party apps, but this is not the main focus and I want to keep the application simple. See [contributing](./CONTRIBUTING.md) for more information. We would first need to look into [this issue](https://github.com/kitsumed/ShizuCallRecorder/issues/1).

## Features

- Records **both sides of phone calls** (incoming and outgoing)
    - Should work even when using Bluetooth or a remote headset
- **Security** toggles to **manage Shizuku on/off state**
    - An attempt to reduce the potential attack surface introduced by Shizuku
    - Helps with apps that detect / yells at you when USB Debugging or Shizuku is enabled
- **Automatic call recording** option with basic **exclusion rules**:
    - Ignore anonymous calls
    - Ignore specific contacts
    - Ignore all contacts
- Saves recordings with **Opus** or **AAC** codec.
- The app runs only on phone event changes, no persistent background process and notifications

## Requirements
- **Android 12** or more recent*.
    - **Look at the *Android Tested Versions* table** for more informations on limitations.
- [Shizuku](https://github.com/thedjchi/Shizuku)* (we recommend [thedjchi](https://github.com/thedjchi) fork)

<details>
<summary><b>Android Tested Versions</b></summary>
<b>I cannot extensively test all of these versions, so issues may arise. This table may change as more testing by other users is done.</b>
    
| Android Version | Supported | Note                                  |
|-----------------|-----------|---------------------------------------|
| 11              |**Limited**|**[Unlocked screen required](https://github.com/Genymobile/scrcpy/blob/3fcc177da5b6b4514d0e8e8d90d7d58d6731eac9/server/src/main/java/com/genymobile/scrcpy/audio/AudioDirectCapture.java#L56-L68)**, else it crash|
| 12              | Yes       |                                      |
| 13              | Yes       |                                      |
| 14              | Yes       |                                      |
| 15              | Yes       |                                      |
| 16              | Yes       |                                      |
| 17              |**Unknown**| Not yet released, has major ADB changes|

</details>

> [!IMPORTANT]  
> This application makes use of hidden internal Android APIs. As such, it is prone to breaking in new Android releases or due to specific OEM modifications to the source code. There are multiple breaking points and dependencies. The two main ones right now are [scrcpy-server](https://github.com/genymobile/scrcpy) and Shizuku.

## Installation
⚠️⚠️⬇️**YOU WILL NEED TO DO SOME INITIAL CONFIGURATIONS**.⬇️⚠️⚠️

**Please follow the installation instructions in the [SUPPORT documentation](./docs/SUPPORT.md)** under **Installation & Configuration**. 

You can download the latest version [here](https://github.com/kitsumed/ShizuCallRecorder/releases/latest).

## Contributing

Please see the [contributing](./CONTRIBUTING.md) guide.

<details>
  <summary><strong>Translation hosting generously provided by <a href="https://hosted.weblate.org/engage/shizucallrecorder/">Weblate</a></strong>.</summary>
  <p align="left">
    <p><strong>NOTE:</strong> By default, Weblate will use your account creation email when making commits to GitHub (to give you credits). This would leak your email address, you can change this behavior in your account settings.</p>
    <a href="https://hosted.weblate.org/engage/shizucallrecorder/">
      <img src="https://hosted.weblate.org/widget/shizucallrecorder/horizontal-auto.svg" alt="Traduction Stats">
    </a>
  </p>
</details>

## License
The software is licensed under the [GNU General Public License, version 3 (GPL-3.0)](LICENSE). ⚠️ **Additional terms** under GNU GPL version 3 Section 7 are at the end of the file.
 - This License does not grant any rights in the trademarks, service marks, or logos of any Contributor.
 - *As example, the name "`ShizuCallRecorder`" and `com.kitsumed.shizucallrecorder` are the property of the copyright holder **kitsumed***.

I decided to use the **GPL-3.0** license, as no other FOSS call recording app for non-root devices exists yet, and I want to ensure that any potential future alternatives derived from this project remain FOSS.

## Disclaimer
**Recording phone calls may be subject to complex and varying laws in different countries and jurisdictions.** For example, you may need to **ensure you have consent** from all parties before recording conversations. The **developers and contributors are not responsible** for any misuse or legal consequences arising from the use of this application.
You can **learn more on Wikipedia at**: [Telephone call recording laws](https://en.wikipedia.org/wiki/Telephone_call_recording_laws).

**If you are legally required to inform or ask someone for consent before recording**, please note that the application **DOES NOT handle this for you**.
In some cases, certain features, like automatic call recording, may not be legally allowed. It is your responsibility to ensure compliance with applicable laws. **This is not legal advice**. Please **consult a legal professional for guidance** regarding your specific situation.

> [!CAUTION]
> ### **APPLICATION BEHAVIOR**
> Due to the evolving Android ecosystem and varying hardware (OEMs), this software is subject to **non-deterministic behavior**:
> * **Concurrent Calls:** The app may fail to detect transitions (e.g., while in a call, a second incoming call arrives, or switching between held calls). In these scenarios, the application may **continue to record audio into a single continuous file** without separate notifications or new notifications prompts for subsequent callers.
> * **Filter Logic Limitations**: **Due to Android privacy-driven restrictions on real-time phone number access**, the app may relies, depending on the call detection mode, on fetching deprecated or unstable values. Theses initial check could be used to decide whether to automatically record based on your settings. **In cases where we receive the value too late, receive invalid data, or receive no data, the application is likely to consider the call as "anonymous" in its decision process**.
> * **Unforeseen Failures:** Future OS updates, bugs, design choices in the app, or undocumented system behaviors may cause the app to start and/or continue recording, or fail, in a unexpected manner.
>
> ### **USER AWARENESS**
> Because deterministic behavior of the application cannot be guaranteed, it is your responsibility to:
> 1. Ensure the recording behavior and your application settings aligns with your intent and the specific requirements in your jurisdiction.
> 2. **Monitor the app's behavior** on your specific device. If you observe any behavior that does not comply with your local laws, you **must immediately cease any activity that constitutes a legal infraction** (For example, hanging up the call, deleting the audio files, etc).
