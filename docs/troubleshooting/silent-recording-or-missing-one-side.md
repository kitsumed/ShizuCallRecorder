# Silent Recording or Missing One Side
### The recording of my calls only have silence or one side of the call audio!
#### Also related: I can't record call from X third-party app!
Recording calls on Android is complex since every OEM does things differently. This means that on some devices, everything will almost always work, while on others, it will only work under certain conditions, or not at all. The way the audio is managed on a device also changes depending on the protocol the call uses (Cellular/VoLTE vs Wi-Fi Calling/VoIP for example).

Cellular/VoLTE traditionally uses a physical route on an Android device, which makes them more straightforward to capture via the Android API, provided the OEM did not make major code changes. As for other methods like VoIP, they often are fully handled on the software side, meaning we must use different (harder) methods to attempt capturing them, if even possible.

If you are on **Android 14+**, try to enable `Allow capture via the Playback API`. The application will dynamically switch your selected audio source to a matching one that should work for those types of calls when it detects a VoIP/Wi-Fi call.

**On Android 13 and lower**, or **if it fail on Android 14+**, you can try disabling **VoIP** or **Wi-Fi Calling**. Please note that ***most third-party applications*** (like WhatsApp or Facebook) ***can only use VoIP*** or similar call type, which will result in silent recordings. For carrier phone calls, you should have an option available in your phone settings.

You can also look for existing discussions or issues on this project to see if other users have already had the same issue with your device. They may have shared a solution!
