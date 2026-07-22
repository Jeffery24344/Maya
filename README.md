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
