# BearerMail Android build

This is the plMail Android app (by karatektus, AGPL-3.0) with BearerMail changes on top. Everything
not listed here is upstream's work.

## What changed

- **Name and install id.** The app shows as **BearerMail** (and "BearerMail Calendar" for the
  optional calendar icon). It installs as `com.bearermail.mail` (`com.bearermail.mail.google` for
  the google flavour), so it sits next to a stock plMail install instead of replacing it. The
  launcher icons, including the snail, are unchanged.
- **Drive** (drawer → Drive; shown only when the server offers Drive):
  browse folders, upload from the phone, open, send or save a file, rename, move, delete, and
  manage share links (optional password, expiry of 1, 7 or 30 days, or never). New links are copied
  to the clipboard.
- **Share to "BearerMail Drive".** A second entry in every app's share sheet. Sharing there uploads
  the files straight into Drive instead of opening the composer.
- **Drive links in mail and events.** The link button in the composer's top bar, and **Add from
  Drive** under an event's description, pick a Drive file (or upload one from the phone) and insert
  a download link.
- **Plans in emails → calendar.** When a message says something like "meet me at 218 Main St at
  10am" or "lunch Friday at 12:30", the reader shows a **Looks like a plan** card. **Add to
  calendar** opens the event editor already filled in (title, time, place, a note); nothing is saved
  until you press Save. The detection runs on the phone, on the text already downloaded.
- **Insert event in compose.** Composer menu (⋯) → **Insert event** picks one of your upcoming
  events, writes its details into the message and attaches it as an `.ics`, so the recipient can add
  it to their calendar in one tap.
- **On-the-spot aliases.** Drawer → **New alias**, or the composer's From menu → **New alias…** /
  **Random alias (one tap)**. Type a name or make a random one (e.g. `shop.k3v9x2ma@yourdomain`).
  The address is copied to the clipboard, receives mail within seconds (the server adds it to the
  mail server automatically), and can be used as the From address straight away. Delete it on the
  web under Settings → Aliases.

## Server needed

The BearerMail server branch with the Drive API (`/jmap/drive`, session capability
`urn:bearermail:params:drive`). Aliases use standard JMAP `Identity/set`, which any plMail server
supports; receiving on them needs the BearerMail mail server.

The app only talks to the server you pair it with. Drive calls are refused unless they go to that
same server, so your app password is never sent anywhere else.

## Building

Open the folder in a current Android Studio (the project uses AGP 9.3) and build the **fossDebug** variant, or from a
terminal:

    ./gradlew :app:assembleFossDebug

The APK lands in `app/build/outputs/apk/foss/debug/`.

`gradle.properties` sets `plmail.warningsAsErrors=false`, because these changes were written
without an Android SDK to compile against. Once a build is clean, you can set it back to `true`.
