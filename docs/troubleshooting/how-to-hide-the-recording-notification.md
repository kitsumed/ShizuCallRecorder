# How to Hide The Recording Notification?

We sometimes receive requests from users asking us to add a feature to hide the recording service notification while in a call. We cannot do that because Android would kill our application foreground process after a very short amount of time, which forces us to always send a notification.

However, users can still hide our notification on their end. Here is a generic step-by-step guide:
- Go into your phone settings.
- Locate the section with the list of your phone applications.
- Select ShizuCallRecorder to view its application profile.
- Go to the Notifications section.
- Hide or disable the `Foreground Recording Service` notification category. This will cause your phone to hide the ongoing call notification.

> [!NOTE]
> We have received reports of some users not having a notification category available under our application profile. Some OEM, like Samsung, may disable this feature by default. In the case of Samsung, users must go into their global **Notifications** settings, navigate to their advanced settings, and enable notification category management for all applications.