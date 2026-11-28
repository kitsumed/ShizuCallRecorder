---
description: Step-by-step installation and configuration guide for ShizuCallRecorder. Include recommended configuration to maintain your device security.
---
[Go back to the home documentation page](./SUPPORT.md)
# Configuration Guide

>[!NOTE]  
> This guide assumes you have met all [requirements](https://github.com/kitsumed/ShizuCallRecorder#requirements) and installed all the mentioned third-party applications.  
> This guide also assumes you have read the [disclaimer](https://github.com/kitsumed/ShizuCallRecorder#disclaimer) and are following the law.

## Installing Shizuku
Once you're ready **to install Shizuku**, **follow the setup instructions on the [Shizuku Setup Page Wiki](https://github.com/thedjchi/Shizuku/wiki/Setup)**. Please **read them carefully**, there is a lot of **important information about its behavior and recommended settings** to help maintain good device security.

>[!TIP]
> Unless you know what you are doing, **you should enable "Auto-Disable USB Debugging" in Shizuku** to reduce security risks.

>[!WARNING]
> Shizuku **TCP Mode** allows you to start and keep alive Shizuku without a Wi-Fi connection, but it also comes with increased security risks. Please read the Shizuku wiki for more information. Additionally, some information is available in my [blog post about Shizuku](https://kitsumed.github.io/blog/posts/what-is-shizuku_how-does-it-work_security-implications/). You might also want to set another TCP port than the default `5555` one.

## Installing ShizuCallRecorder
You can **download** the **latest version from GitHub** [here](https://github.com/kitsumed/ShizuCallRecorder/releases/latest).

You can also use the following **mirrors**, however **take note that we do not control them**.

<div style="display: flex; align-items: center; flex-wrap: wrap; gap: 10px;">
  <a href="https://f-droid.org/packages/com.kitsumed.shizucallrecorder" target="_blank" rel="noopener noreferrer">
    <img src="/get-it-fdroid.svg" alt="Get it on F-Droid" style="height: 75px;">
  </a>
  <a href="https://apt.izzysoft.de/packages/com.kitsumed.shizucallrecorder" target="_blank" rel="noopener noreferrer">
    <img src="/get-it-izzyondroid.svg" alt="Get it on IzzyOnDroid" style="height: 55px;">
  </a>
  <a href="https://sourceforge.net/projects/shizucallrecorder.mirror/" target="_blank" rel="noopener noreferrer">
    <img src="/get-it-sourceforge.svg" alt="Get it on SourceForge" style="height: 75px;">
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.kitsumed.shizucallrecorder%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fkitsumed%2Fshizucallrecorder%22%2C%22author%22%3A%22Kitsumed%22%2C%22name%22%3A%22ShizuCallRecorder%22%7D" target="_blank" rel="noopener noreferrer">
    <img src="/get-it-obtainium.svg" alt="Get it on Obtainium" style="height: 75px;">
  </a>
  <a href="https://www.openapk.net/shizucallrecorder/com.kitsumed.shizucallrecorder/" target="_blank" rel="noopener noreferrer">
    <img src="/get-it-openapk.svg" alt="Get it on OpenAPK" style="height: 75px;">
  </a>
</div>

**You can check your downloaded SHA256 against our GitHub-CI build [attestations](https://github.com/kitsumed/ShizuCallRecorder/attestations).**

You may also want to read our [guidance on misleading forks](./untrusted-and-misleading-forks.md). We **do not officially endorse any forks**. Always **verify that third-party projects follow good security practices**.

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

This is the default behavior of ShizuCallRecorder. You can enable certain settings in Shizuku like to reduce the chance of failures.

1. You **need** to enable **Start on Boot**.  
2. You **may** want to enable **Watchdog** to automatically restart it on crashes (this create a persistent notification in the background).  
3. ⚠️ You **may** want to enable **TCP Mode** to start and/or keep Shizuku alive without a Wi-Fi connection (**LTE/DATA is not a Wifi connection**).

>[!NOTE]
> Having Shizuku always running in the background **increases the period during which your device is at higher security risk** because ADB is enabled.Some applications, such as banking apps, may also detect Shizuku or USB Debugging and refuse to work. In those cases, you should use [method 2](#method-2).

---
### Method 2
This method is for users who plan to have Shizuku only running when needed. (**Recommended**).

>[!NOTE]
> The main disadvantage of this method is that there may be a delay before recording starts since we need to wait for Shizuku to start. Depending on your device and Shizuku, this can take anywhere from 1 to 20+ seconds. In my tests, it is generally very fast, and **the security advantages, in my eyes, outweigh the few seconds of delay** at the start of the call.

To enable this behavior in ShizuCallRecorder, go to the **Security** section and enable **Manage Shizuku**.  

You will then need to open the Shizuku application, find the **auth key for intents**, and copy it back into ShizuCallRecorder.  

> This step is required so that the app can control Shizuku on your behalf.

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
> As mentioned earlier, recording may take a few seconds because Shizuku needs to start. If you enable **Start only when recording**, be aware that the audio will **not be recorded immediately** when you press the button as we would first need to wait for Shizuku to start.