# Notify for Android

A native Android remote controller for Notify on your Mac.

## Background notifications

Opt in under **Settings → Notifications** after signing in and enrolling this phone.
Android 13+ also requires notification permission. The **Mac attention** channel
controls sound, vibration and interruption; Notify never overrides channel choices
or Do Not Disturb. The original generated cue is reproducible with
`python3 crypto/generate-attention-sound.py`.

FCM receives only a random event ID, timestamp and generic attention type/version.
No request, chat, command, approval or Mac/account identity is included. A tap
opens Notify; current encrypted state and the existing confirmation checks are
required before any action. Notifications expire after two minutes, are
deduplicated/rate-limited and are best effort—not a request inbox or delivery receipt.

Firebase Messaging auto-init is disabled until explicit consent. Tokens are
registered through the account-authenticated service using the saved device key;
they never create device trust. Token rotation refreshes while signed in and idle
in the foreground, or on the next sign-in; failed/uncertain registration requires
an explicit refresh. Account cookies and relay grants are never persisted for this.

Turning off stops local alerts immediately, disables Firebase auto-init and requests
token deletion. Signed server removal must complete before signing out or forgetting
the key. If offline/signed out, the UI honestly retains pending routing cleanup.
Server revocation removes routing with the device; a queued generic alert cannot
authorize an action. Your Mac must be awake, online and running the updated helper.

Build requirements: the matching private `app/google-services.json`, SDK 35,
JDK 17 and the existing native crypto artifact. Do not commit service-account keys.
The APK includes notification SDK notices alongside native crypto notices.

## Releases and updates

The first APK release requires end-to-end testing over the internet, including
pairing, approvals, chat, and reconnection. Local or simulated tests alone do not
meet that release gate.

Every Android app update gets a new versioned GitHub release in this repository,
with the installable APK attached. An update is not published as source code only.

Before publishing:

- Increment Android's version code and version name.
- Complete the relevant regression and real internet-connection checks.
- Verify the APK's signature, native libraries, alignment, and dependency notices.
- Preserve the signing identity so existing installations can update.
- Attach the verified APK and its SHA-256 checksum to the release.
- Describe changes and any known limitations in the release notes.

The current native library targets arm64 Android devices running Android 9 or
later. Real-device acceptance remains required before calling a build ready.
An agent-tested preview release may precede the user's physical-phone checks,
but release notes must distinguish public-service/synthetic verification from
pending physical delivery, sound/vibration and mobile-network acceptance.
