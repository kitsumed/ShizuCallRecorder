---
title: About Misleading, Malicious, and Non-Compliant Forkseeee
description: Learn about the security risks of unreviewed, vibe-coded ShizuCallRecorder forks running with ADB shell privileges, stolen attribution, and how to identify non-compliant projects.
---
# Misleading, Malicious, and Non-Compliant Forks
## The Beginning of ShizuCallRecorder

As ShizuCallRecorder is becoming more popular, all sorts of people have started engaging with the project. In our early days, users would sometimes make a quick fork for personal use, make a couple of edits, maybe with AI agents, and use it for themselves. Now that we have passed **2.2K+ stars on GitHub**, we are facing a new kind of fork. They are often non-compliant, misleading in some way, and in general are acting outside of the FOSS/GPL spirit, actively trying to promote themselves.

As stated in our [introduction](./introduction.md), I, Kitsumed, created ShizuCallRecorder because for over 10 years I could not find a modern, free, ad-free, open-source call recording application. For this reason, when I got our initial v1.0.0 version working, I decided to release the source code under the **GPL-3.0 license, with additional terms under Section 7**, to ensure any derivatives remain FOSS. This way, anyone can make any edits they want. At the time, I told myself, *"If one day I can no longer maintain it, someone else will pick it up."* As someone who relies on call recording to help with a disability, I was also hoping to attract experienced developers who could submit PRs and help improve the project.

After my first posts on Reddit, the project quickly gained interest and support across a lot of other forums. In a single week, the project was shared on forums across multiple countries and in languages I didn't even know existed. A month after our release, I even made a request to enable the GitHub Sponsors program to allow users to [support me financially](https://github.com/sponsors/kitsumed).

## Common Non-Compliant and Risky Behaviors

Over the following 4 months, and ever since then, I have found several forks and projects that violate our GPL license. Their actions goes from attribution stealing, to being bad-faith actors, and having behavior that goes against the FOSS/GPL spirit even when not strictly a license violation. What all of these forks have in common is their heavy usage of AI agents. Some of these forks can be classified as vibe-coded (meaning there is little to no human interaction), while others are AI-assisted to varying degrees.

In my observations, these forks typically come from unknown or small developer accounts with little to no visible activity or projects on GitHub, and they do the following:

* **Poor Security Practices:** They generate features with AI without proper code review, skip basic security practices, do not attest or make immutable releases, and in some cases release unoptimized binary (big APK sizes due to R8 disabled).
* **Blind Reliance on AI:** Some developers push code they do not appear to understand. We know this because they sometimes commit their AI-generated "planning" markdown files that contain false assumptions treated as fact in the LLM conclusions.
* **Security Risks:** Because ShizuCallRecorder needs ADB to run code as the `shell` user, it run with **elevated permissions close to a root user**. This means that unreviewed code like thoses from vibe-coded forks carry **a bigger risk of compromising your device**.
* **License Violations & Misrepresentation:** Some forks also do the following:
  * **Stripping our GPL license** and **not crediting us** (despite explicit notice in our files that humans (and indirectly LLMs) need to keep / add attribution).
  * Playing with their words to **claim they built the app entirely from scratch** while asking for donations.
  * **Relicensing the entire repository under a different license.**

> [!NOTE]
> I am aware that the GPL-3.0 license allows forks and commercial use. However, the code needs to remain under the GPL, you need to credit us, clearly state your changes, and comply with our additional terms under Section 7, which require linking back (keeping the link) to our repository inside the app interface, not reusing our package ID, not impersonating us, and more.
> I'm not trying to dismiss every fork that used AI or say something like *"all forks are bad"*. It's just frustrating to put my free time into building and maintaining something for free, only to watch other developers tell AI to strip credit, claim full authorship, and then profit from it. You can read our [full license here](https://github.com/kitsumed/ShizuCallRecorder/blob/main/LICENSE).
>
> **I also use AI as a tool for parts of development**, I just use it responsibly, review the results, and actually try to understand and learn. The reason I bring up AI so often here is that this kind of issue has become far more common in recent years (2025-2026), and in my own view this is the main reason: someone looking to quickly rip off a project for money is going to rely on AI rather than put in hours of real work.

## Examples of Misleading or Non-Compliant Projects

Below are examples of forks that **violated our license and/or did misleading actions**.

> [!IMPORTANT]
> **Please do not harass, or spam any developers mentioned below.** This page exists to show a list of examples of forks that were non-compliant or did misleading things. This page main goal is to inform users about them and the potential risks of using similar projects. 


### CallVault (by madkongo)
Vibe-coded fork that removed parts of our copyright. They previously claimed to have made the application by themselves in donation prompts, and engaged in self-promotion on XDA and other platforms during that period.

<details>
<summary>Show more details</summary>

- **Details:**
  - Repo credits ShizuCallRecorder in the readme, but they **removed the copyright notices from all source files**. The in-app **disclaimer also only shows the fork author**, they removed us from it.
  - **Previously claimed to have built the entire application alone in their donation prompts**.
  - There were two XDA accounts (`madkongo` and `sindo11`) used for the self-promotion. The second account was created later and only ever made two posts. Its only reaction came from `madkongo`. After reporting the message and accounts, XDA moderators removed the promotional posts and banned the second account.
- **Proof:**
  - <a href="https://web.archive.org/web/20261128000847/https://github.com/madkongo/CallVault/issues/66" target="_blank" rel="nofollow noopener noreferrer">Archived issue about license violation and misleading donation prompts</a>
  - <a href="https://web.archive.org/web/20261128002115/https://github.com/timschneeb/awesome-shizuku/issues/193" target="_blank" rel="nofollow noopener noreferrer">Archived Awesome-Shizuku unlisting request (with XDA screenshots)</a>

</details>

### AVault (by arpanjot-coding)
Vibe-coded call recording app containing **~86-90% of ShizuCallRecorder source code** with removed attribution and MIT relicensing.

<details>
<summary>Show more details</summary>

- **Details:**
  - With a tool I matched around ~86-90% of ShizuCallRecorder source code. They also **removed all of our original copyright headers, licenses and attribution**.
  - After I made a DMCA request to Github for the violation, I also saw rapid changes in star and fork counts (forks amount changing to 150 before dropping back to 26, and stars changing between ~500 and ~1K over a single week. This was a brand-new repository). I reported it to GitHub as I suspected some form of stats manipulation.
  - After GitHub acknowledged the DMCA notice, a commit partially restored a minimal mention of the GPL license in text but not our notice itself or copyright. That commit was also *attempting to license new code under MIT*, which **violates the GPL-3.0 requirement** that derivative works remain licensed as a whole under GPL.
- **Proof:**
  - <a href="https://web.archive.org/web/20261128005446/https://github.com/arpanjot-coding/AVault/tree/aceef7bddd8e6189a6650c273ef928e758d344bb" rel="nofollow noopener noreferrer" target="_blank">Archived GitHub Repo</a>
  - *We are still waiting for GitHub final decision on the DMCA takedown. They publish censored versions of them publicly.*

</details>