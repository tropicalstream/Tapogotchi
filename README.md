# Tapogotchi 🥚🥽

The ultimate virtual pet, for the **RayNeo X3 Pro** smart glasses. It lives
in your view, keeps real hours, and — because it rides on your face — it's
the first pocket pet that actually goes on your walks.

## The life

A real-time creature that does not pause when you leave. Time passes on the
wall clock; on every launch the elapsed hours are simulated minute by
minute and read back as a **"While you were away"** report.

- **EGG → BABY → CHILD → TEEN → ADULT → ELDER**, across real days. The egg
  hatches faster if you keep watching it.
- The adult form is a verdict on your care: **ROYAL** (great care +
  discipline + fit), **SPRITE** (good), **SCRAPPY** (middling), **GLOOM**
  (neglect — it still loves you, sort of).
- Hunger, mood, energy, weight, mess, sickness, discipline — all classic,
  all honestly enforced. It sleeps by the real clock (turn the lights off
  or it sleeps badly). Neglect long enough and the little light goes out:
  gravestone, ghost, lineage record, and a new egg for generation N+1.
- **Attention calls are audio-first**: each need has its own chirp
  signature (hungry ≠ sad ≠ sick ≠ mess ≠ cheeky false alarm — scold the
  false alarms to build discipline). You hear your pet; you don't have to
  stare at it.

## The X3 exclusives

- **Walks.** Steps while wearing the glasses walk your pet (step detector →
  counter → accelerometer fallback). 1500 steps a day = the walk goal:
  full mood and a care bonus. No pocket tamagotchi ever knew you moved.
- **Room presence.** The pet drifts opposite your head turns and lazily
  floats back — it reads as a creature in your space, not a sticker on
  glass (Settings → Room drift).
- **Waveguide-native look.** Chunky LCD pixels on the black void — low
  APL, daylight-readable, very 1997.

## Controls (temple pads)

| Gesture | Action |
|---|---|
| RIGHT swipe ⇄ | move along the icon bar / choose / guess in the dance-off |
| RIGHT swipe ⇅ | settings rows |
| RIGHT tap | select / activate |
| RIGHT double-tap | back (and: forfeit a dance-off) |

Icon bar: FEED (meal or snack) · PLAY (dance-off, 5 rounds, guess the hop) ·
CLEAN · LIGHTS · MEDICINE · SCOLD · STATS · SETTINGS. One swipe = one step,
classified on finger-up (the suite standard). Reset Settings sits last,
confirm-twice; hatching a new egg from the grave is confirm-twice too.

## Build

```bash
cd ~/Projects/Tapogotchi && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

SBS auto-detects RayNeo hardware (no toggle needed). Runs single-view on a
phone for testing. The pet is saved after every interaction and every 30 s
— an exit never loses a life.
