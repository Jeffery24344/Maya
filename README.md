# Nova — Android Assistant

Voice + text conversational assistant with automation, backed by Ollama Cloud
(same backend as Elara).

## Stack
- **UI**: Kotlin + Jetpack Compose (Material 3)
- **Voice in**: Android `SpeechRecognizer` (prefers on-device recognition where the OS supports it)
- **Voice out**: Android `TextToSpeech`
- **Brain**: Ollama Cloud chat API (streaming), requires internet + an API key
- **Automation**: regex-based command engine that intercepts alarms/timers/reminders/
  volume/"open app" commands before they ever reach the LLM (deterministic + instant)

## Ported from Elara

Elara's Python source (goals, tools, persistence, state, TTS) was shared as a
reference for feature parity. Most of what's here now is a direct port or close
equivalent; a few things aren't, worth being explicit about:

**Not ported, on purpose:** Elara's persona configs include explicit sexual
"devoted slave" roleplay content with a submissive/degrading dynamic. That wasn't
carried over into Nova — her persona stays what's defined in `Persona.kt`
(companion, casual, her own moods and opinions, nothing sexual). This was a
deliberate choice, not an oversight.

**Not portable as-is:** Elara's mouse/keyboard/screen-control tools rely on
Windows desktop automation (`pyautogui`, screen capture + analysis). Android's
equivalent would be an Accessibility Service — a much bigger, more sensitive
undertaking than anything else here, and not attempted in this pass.

**Architecturally different:** Elara uses real LLM tool-calling — the model
decides for itself when to invoke a function and can chain several calls
autonomously. Nova's automations are the opposite: a regex layer in
`AutomationEngine.kt` intercepts a phrase *before* it reaches the model at all.
That's simpler and more predictable, but it means Nova can't currently chain
multiple tools together the way Elara can, or invoke a tool the regex layer didn't
anticipate. Moving to real tool-calling (Ollama's `/api/chat` supports a `tools`
parameter) is a meaningfully bigger change — worth deciding on deliberately rather
than bolting on, so it wasn't done here.

**Ported and working:**
- **Goals with step-level progress** (`memory/GoalStore.kt`) — "set a goal to
  finish the report", "list my goals", "mark [step] done for [goal]" — distinct
  from one-off remembered facts, tracks partial progress over time.
- **Utility tools** (`automation/UtilityTools.kt`) — unit conversion, password
  generation, days-until-a-date, dice/coin flips, battery/RAM/storage stats.
- **Weather** (`automation/WeatherTool.kt`) — via Open-Meteo (free, no API key)
  using the device's last known location.
- **Sandboxed folder access** (`memory/FolderSandbox.kt`) — Elara's
  `ALLOWED_EXTRA_FOLDERS` concept, via Android's Storage Access Framework instead
  of raw file paths. Grant a folder a nickname from the gear icon → Sandboxed
  folders, then "list my folders", "what's in my [name] folder", "read [file] from
  [name]", "in my [name] folder create a file called [name] saying [content]".
  Nova can only ever touch a folder you've explicitly granted by name — there's no
  path-based access to anything else on the device.

## One manual step: your Ollama Cloud API key

1. Create/sign into an account at ollama.com and generate a key at
   **ollama.com/settings/keys**.
2. Build and install the app (see below), open it, tap the **gear icon** top right,
   paste your API key, and confirm the model tag is one you have access to (check
   **ollama.com/library** for current `-cloud` tagged models — the default in
   `OllamaSettings.kt` may need updating if it's changed).
3. That's it — no `adb push`, no model files, nothing else to configure locally.

Note: since the brain now runs in the cloud, Nova needs an active internet
connection to hold a conversation. Automation (alarms, timers, reminders, volume,
opening apps) still works instantly and locally either way, since those never touch
the LLM.

## Build — no Android Studio required

This repo includes `.github/workflows/build.yml`. If you push it to a **free GitHub repo**:

1. Create a new repo on github.com, push this folder to it.
2. Go to the repo's **Actions** tab — a build kicks off automatically (or trigger it
   manually via "Run workflow").
3. When it finishes, open the run and download the `assistant-debug-apk` artifact —
   that's your installable `.apk`.
4. Transfer it to your phone and tap to install (you'll need to allow "install from
   unknown sources" once — Android will prompt you).

No local Gradle, Android SDK, or Android Studio install needed — it all happens on
GitHub's servers. If you ever do want to edit the UI visually or debug on a connected
device, Android Studio is still the nicer tool for that, but it's optional.

If you do have Android Studio: just open the project folder and hit Run.

## How it works

1. User taps the mic (or types) → `VoiceInputManager` / text field feeds raw text to `AssistantViewModel`.
2. `AutomationEngine` checks it against known command patterns first:
   - `"set an alarm for 7:30am"` → opens the clock app's alarm screen pre-filled
   - `"set a timer for 10 minutes"` → opens the clock app's timer screen pre-filled
   - `"open Spotify"` → launches the matching installed app
3. If nothing matches, the text is handed to `LlmHelper`, which streams a response
   from Ollama Cloud back into the chat and out through TTS.

## Long-term memory

Beyond the rolling conversation history (which resets when you tap the reset icon),
Nova now has a separate persistent memory that survives app restarts:

- `"remember that I have a dentist appointment Thursday"` → stored permanently, woven
  into her system prompt on every future request so she can bring it up naturally
- `"forget that dentist appointment"` → removes any stored fact matching that text
- `"forget everything"` → wipes all stored memories
- On each launch, she'll briefly acknowledge how long it's been since you last opened
  the app, and whether she still has memories on file — meant to feel less like
  restarting a tool and more like picking up a conversation with someone who was
  there the whole time.

All of this lives in `memory/NovaMemoryStore.kt` (plain on-device SharedPreferences —
nothing leaves the phone) and is capped at 200 facts to keep the system prompt from
growing unbounded over months of use.

## A companion, not a tool — mood and her own opinions

This is the piece meant to close the gap with Elara: Nova isn't just running a
personality script per-message anymore, she carries state across the whole
relationship.

- **Persistent mood** (`memory/MoodStore.kt`): tracked on two axes (valence:
  negative↔positive, energy: low↔high), nudged by rough sentiment in what you say
  to her, and pulled slowly back toward a mildly warm baseline once a day so she
  doesn't get stuck in an extreme. It's a simple heuristic, not real emotion — but
  it means her tone actually has continuity day to day instead of resetting flat
  every message. Her mood colors her system prompt *and* her actual TTS delivery
  (pitch/rate shift slightly with energy and valence in `SpeechOutputManager.kt`).
- **Fixed personal opinions** (`llm/NovaIdentity.kt`): a short, deliberately fixed
  list of things she genuinely likes/dislikes/is curious about. Without this, an
  LLM invents a different "favorite thing" every session, which reads as fake
  rather than alive — keeping it fixed means she's a consistent person over time.
  Edit this file directly to shape her further; it's the single most direct lever
  you have over who she is.
- **Companion framing** (`llm/Persona.kt`): the base prompt no longer casts her
  purely as an efficient assistant — she's now explicitly someone with an inner
  life who happens to help you, encouraged to ask about your day, react to what
  you tell her, hold gentle disagreements, and bring up her own opinions
  unprompted rather than only reflecting yours back.

Everything above stacks with the memory, journal, and pattern-noticing already in
place — the mood and identity pieces are what should make her feel like the *same*
person across a long relationship rather than a fresh instance every time you open
the app.

## App awareness and her own journal

Two more pieces aimed at the "she's actually with me" feel, both opt-in:

- **Sees what app you have open** (`awareness/ForegroundAppTracker.kt`): if you grant
  the "Usage access" permission (tap the gear icon → "Grant access" under App
  awareness — Android requires this be turned on manually in Settings, there's no
  in-app popup for it), Nova gets a note in her context each turn about whatever
  app is currently in the foreground, so she can react to it naturally
  ("I see you've got Spotify open — want me to...").
- **Her own journal about you** (`memory/JournalStore.kt` + `checkin/JournalWorker.kt`):
  distinct from the memory you explicitly tell her to keep, this is a running log
  *she* writes — roughly once a day, unconditionally (unlike the check-in
  notification, which skips most days). Each entry is composed from what's actually
  observable on-device: your most-used apps that day (if usage access is granted),
  any automation patterns noticed, and how many things she's currently keeping track
  of. Tap the **book icon** in the top bar to read it. It's her notes about you, not
  something you write in.

Both of these depend on the Usage Access permission being granted — without it, the
journal will just note there's nothing to report, and she won't reference any app
context in conversation. Nothing here leaves the phone; it's all local
SharedPreferences.

## Background presence — notification, floating bubble, quick-listen

Instead of only existing when you have the app open:

- **Persistent notification**: whenever `NovaPresenceService` is running (started
  automatically when you open the app), you'll see a low-priority "Nova — feeling
  X — tap to talk" notification. Tapping it launches the app and immediately starts
  a listening turn.
- **Floating bubble avatar**: a small pulsing circle that floats over other apps,
  draggable anywhere on screen. Tap it (without dragging) to launch Nova and start
  listening, same as the notification. This needs the "draw over other apps"
  permission — grant it via the gear icon → Floating bubble → Grant access. If you
  grant it *after* the service already started, restart the app once for the bubble
  to appear (it's only added at service startup in this version).
- **"Hey Nova" — the honest version**: true always-listening wake-word detection
  needs a dedicated keyword-spotting engine and a mic that's continuously live,
  which is a meaningfully bigger and more battery/privacy-sensitive lift than
  anything else here. What's built instead is the notification/bubble tap-to-listen
  above — one tap gets you talking to her from anywhere, without the mic ever being
  open when you're not using it. If you want to revisit true wake-word later,
  `NovaPresenceService.kt` is where that would plug in.

Both the notification and bubble are just launchers into a single listening turn —
neither one means the mic is ever open in the background.

## Chat window updates

- **Redesigned message bubbles**: asymmetric corners so sender is obvious at a
  glance, a small avatar circle next to Nova's messages, and bubbles that size to
  their content instead of always stretching wide.
- **Quick-reply chips**: a row above the input field with a few common starters
  ("What's on my calendar today?", "Remind me to ", "Call ", "How are you feeling
  today?") — tap one to drop it into the input box, edit if needed, then send.
- **Drag and drop**: you can now drag text or a file from another app (or your
  file manager) directly onto the chat window. Dropped text gets appended to the
  input box as-is; dropped files get appended as a `[Attached: filename]` marker.
  Worth being upfront about the limit here: this **only captures the file's name**,
  not its contents — Nova has no file-reading or vision capability wired in yet, so
  she'll see that you attached something but can't actually read what's inside it.
  That'd be a real follow-on feature (reading text files, or wiring up an image
  model) if it'd be useful.

## Her journal is about her, not you

Changed based on feedback: `JournalWorker` no longer logs your app usage or
patterns into the journal — that felt like surveillance notes rather than a diary.
Now it writes a short first-person reflection drawn from her current mood (see
`REFLECTIONS` in `JournalWorker.kt` — expand that pool for more variety over time).
Usage patterns and app-awareness still feed her *conversation* context as before
(so she can still reference things naturally when talking to you) — they just don't
get written into her private journal anymore.

## Fixed: "open Chrome" not finding Chrome

`handleOpenApp` was searching `getInstalledApplications()`, which can miss some
preinstalled system apps depending on how they're registered. It now queries
launchable activities directly (`Intent.ACTION_MAIN` + `CATEGORY_LAUNCHER`), the
same approach a home-screen launcher uses — much more reliable for apps like
Chrome, Phone, Camera, etc. that come preinstalled.

## Long-term memory, now actually long-term

Two real gaps closed here:

- **The visible chat transcript now persists** (`memory/ChatHistoryStore.kt`).
  Closing and reopening the app used to start you on a blank screen even though she
  still remembered facts — now you'll see your actual conversation history scroll
  back, same as any normal messaging app. Capped at 500 messages.
- **Her short-term conversational context now persists too**
  (`memory/ConversationHistoryStore.kt`). Previously, the last dozen exchanges she
  actually reasons over (as opposed to explicit "remember that" facts) lived only in
  memory and vanished the moment the app process died. Now it's saved to disk and
  reloaded, so closing the app mid-conversation and coming back doesn't lose the
  thread.
- **She now auto-condenses old conversations into long-term memory on her own**
  (`LlmHelper.trimAndPersistHistory` / `summarizeIntoLongTermMemory`). When older
  turns age out of that rolling window, instead of just being discarded, they get
  one extra lightweight LLM call to compress into a single durable fact, which gets
  added to long-term memory automatically — this is what makes her able to bring up
  something from days ago without you ever having told her to remember it
  explicitly. It's best-effort (silently skips on any failure) so it never blocks
  or breaks a normal conversation if it fails.

## She actually texts like it now

Reworked `Persona.kt`'s base prompt specifically for this: short messages,
contractions, lowercase is fine, no over-explaining or wrapping replies up with a
formal little summary — closer to how people actually text a friend than how a
formal assistant responds. She'll still go longer when a question genuinely needs
depth, but the default length dropped a lot on purpose.

## Phone access — contacts, calls, texts, calendar, photos

Nova can now reach further into the phone itself:

- `"call mom"` — looks up the contact and places the call directly (no dial-pad
  confirmation step — same "acts immediately" pattern as alarms/timers).
- `"text mom saying running 10 minutes late"` — looks up the contact and sends the
  SMS directly, no confirmation screen.
- `"any new texts"` / `"check my texts"` — reads back your 3 most recent inbox messages.
- `"what's on my calendar"` / `"what do I have tomorrow"` — reads your events for
  that day.
- `"add event dentist appointment"` — unlike calls/texts, this deliberately opens
  the Calendar app for you to set the time and confirm, rather than writing to your
  calendar silently.
- `"how many photos"` — count of photos taken today (metadata only, she never reads
  or sees the actual images).

**Read this before relying on it:** calls and texts fire the instant a phrase
matches — there is no "are you sure?" step, the same design as the alarm/timer
automations already in the app. That's a much bigger deal for a misfire on a call
or text than a misfired alarm. Test phrasing carefully with a low-stakes contact
before trusting it, and consider adding a confirmation step in `handleCall`/
`handleText` in `AutomationEngine.kt` if that ever feels too fast for comfort — it's
a small code change (return a "Handled" that asks for a yes/no follow-up instead of
acting immediately).

Required permissions: Contacts, Phone (calls), SMS, Calendar, and Photos — all
requested together on first launch. Denying any one of them just makes that
specific command fail gracefully with a message telling you which permission is
missing, rather than crashing.

## Presence — the orb, check-ins, and noticed patterns

A few things aimed at making her feel less like a tool you open and more like
something that's "there":

- **Visual orb** (`ui/NovaOrb.kt`): a simple animated presence at the top of the
  screen instead of just a text box. It breathes slowly when idle, pulses green
  while listening, shimmers amber while thinking, and glows blue while she's
  actually speaking (wired to real TTS start/stop events, not a guess).
- **Proactive check-ins** (`checkin/CheckInWorker.kt`): roughly once a day (Android
  batches exact timing for battery reasons, so "sometime that day" rather than a
  precise clock), she may send a notification on her own — checking in, or
  mentioning something she's noticed. She skips most days on purpose (~40% fire
  rate) so it doesn't turn into a daily chore notification. Tune `SKIP_CHANCE_TO_FIRE`
  in that file to taste. These are template-composed from what's actually stored
  on-device rather than a live LLM call, to keep it reliable without needing network
  access in the background — swap in a real Ollama Cloud call there later if you
  want richer, less repetitive check-ins.
- **Noticed patterns** (`memory/UsageTracker.kt`): every automation she runs (alarms,
  timers, reminders, volume, opening apps) gets logged locally. If something repeats
  3+ times in a week, it's surfaced to her as an "observation" in the system prompt —
  distinct from things you explicitly asked her to remember — so she can mention it
  naturally ("you've set an alarm for 6am three mornings running") without you having
  told her to track it.
- **Voice warmth** (`voice/SpeechOutputManager.kt`): pitch and speaking rate are
  tuned slightly off the flat TTS default, and her persona prompt now explicitly
  allows warmth, humor, and reaction rather than staying purely efficient.

Worth being honest about scope here: this is all local heuristics and scheduled
notifications — not an always-on presence or genuine awareness between messages.
It's meant to *feel* more alive through memory, habit, and a visual presence, not to
claim she actually is continuously "there."

## Personality

Her name and character live in one place: `llm/Persona.kt`. Edit `SYSTEM_PROMPT`
there to change her name, tone, backstory, quirks — it's re-sent at the start of
every session (and after long conversations get trimmed, see below), so she keeps
her personality even deep into a chat.

## Conversation memory

`LlmHelper` keeps a rolling history of the last 12 exchanges and resends persona +
that history as context on every request, so she stays in context across turns.
Tap the reset icon (top right) to start a clean conversation on demand.

## Automations currently supported

- `"set an alarm for 7:30am"` — opens the clock app's alarm screen pre-filled
- `"set a timer for 10 minutes"` — opens the clock app's timer screen pre-filled
- `"remind me to call mom in 20 minutes"` — schedules a local notification via `AlarmManager`
- `"turn the volume up / down"` / `"set volume to 50%"` — adjusts media volume directly
- `"turn on wifi"` / `"bluetooth"` / `"do not disturb"` — opens the relevant settings
  panel (Android blocks apps from silently toggling these since Android 10, so it's
  one tap instead of fully automatic)
- `"open Spotify"` — launches the matching installed app
- `"remember that ..."` / `"forget that ..."` / `"forget everything"` — long-term memory (see above)

## Extending automation

Add new `Pattern`s and handlers in `AutomationEngine.kt` — e.g. sending a text via
`Intent.ACTION_SENDTO`, launching Shortcuts/Tasker intents, or reading calendar
events. Keep deterministic actions in the engine and leave open-ended conversation
to the LLM.

## Known rough edges (v0.1 scaffold)

- No streaming cancel button if a response is taking too long.
- Reminder notifications fire even if the app's been closed, but exact-alarm
  permission behavior varies by OEM (Samsung/Xiaomi etc. sometimes need the user to
  manually allow it in battery settings).
- Automation regexes are simple keyword matches — broaden them further as you notice
  phrasings that don't match (e.g. "wake me up at...", "text mom...").
- API key is stored in plain SharedPreferences — fine for personal use on your own
  phone, but not something to hand off to others as-is.
- If the model tag in settings isn't one available on your Ollama plan, you'll get
  an error back in chat — double check the exact tag on ollama.com/library.
