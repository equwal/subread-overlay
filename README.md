# SubRead Overlay

Shows the lines of a subtitle file (`.srt`) over the app that plays your
audiobook or video. The line follows the position of the player. Tap a word to
select it, and send it to a dictionary.

It works with each Android player that publishes a media session: Voice, VLC,
mpv-android, YouTube, Smart AudioBook Player, podcast apps. No player needs a
change, and the app has no network permission.

No `.srt` for your audiobook? [SubRead](https://subread.space) makes one from
the audiobook and its ebook, in the browser or on
[Android](https://github.com/equwal/subread-android).

## How to use it

1. Allow "Show over other apps".
2. Allow notification access. Android gives the position of other apps only to
   an app with this access. The app uses it for the media position, the speed,
   and play or pause. It does not read, keep or send a notification: the
   listener has no `onNotificationPosted`.
3. Choose the `.srt` file.
4. Choose the dictionary for "Look up", or let Android ask each time. Each app
   that has an entry in the text selection menu is in the list (Takoboto,
   Aedict, AnkiDroid, a translator).
5. Press "Show the subtitles", then start the player.

Two settings change the panel: how much of the player shows through it, and
whether it shows the line before and the line after the line of now.

On the panel:

- `≡` moves the panel.
- A tap selects the word under the finger. A drag selects more words.
  "Look up" pauses the player and sends the selection to the dictionary.
  "Share" opens the share sheet of Android, for an app without an entry in
  the text selection menu. "Copy" copies it.
- `▶` starts the player again after a lookup. `⏸` pauses it.
- `⋯` opens the timing row. `−0.5 s` and `+0.5 s` shift the subtitles.
  `◀ line` and `line ▶` make the line before, or the next line, the line of
  now. Use them when the media has an intro that the subtitle file does not
  have.
- `✕` closes the panel.

The line stays on the panel until the next line starts, also in a silence, so
that there is time to look a word up.

## How the timing works

A media session reports a position, the time of that report, and the speed.
`PlayClock` computes the position of now from these three. `Follower` sleeps
until the next line starts and wakes on each report of the player (pause,
seek, speed). It does not poll.

A player that reports no position: Android counts from zero when that player
starts to play, and the panel holds its line in a pause. Set the timing with
`◀ line` and `line ▶`.

The app does not use an accessibility service, and will not. Players that
hide their position (Netflix, some DRM players) are not supported.

A book in many audio files: the player reports the position in the current
file, and the subtitle file has one clock for the whole book. Shift the
subtitles with the timing row at the start of each file. One `.m4b` for one
book has no such problem.

## For reader apps

A reader app has no notification access, so it cannot see the position of
the player. This app answers for it, with a content provider at
`content://space.subread.overlay.player/state`. A query returns one row with
the column `state`:

```
playing=1;position=96153;speed=1.0;package=de.ph1b.audiobook
```

The position is in milliseconds, for the moment of the query. A problem is
`error=no_notification_access` or `error=no_player`. `call` with the method
`play`, `pause` or `seek` (the argument is the position in milliseconds)
controls the player. The panel does not need to be on the screen.

The [SubRead plugin for KOReader](https://github.com/equwal/subread.koplugin)
uses this to turn the pages with the audiobook.

## Build

```
./gradlew :core:test :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest   # word selection and the follower, on a device
```

`:core` is plain Kotlin: the subtitle reader, the line for a position, the
clock. It has property tests. `:app` has the panel and the listener.

`-PplayStore=true` leaves the Ko-fi link out of the build for Google Play.

## Licence

AGPL-3.0. See `LICENSE`.
