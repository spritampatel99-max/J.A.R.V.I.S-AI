# Stitch design: what was taken, what was changed

Source: the five Stitch screens the owner attached (Home, Chat, Tools, Logs, Prefs). The link itself could not be read from the build environment, so only those five HTML files were used.

## Taken
Colours, type scale, radii, glass cards, the floating five-tab bar, the header, the orb states (idle, listening, thinking, speaking), the voice wave, the call confirmation card, the spectrum switch and sliders. Icons are drawn in code instead of Material Symbols. Fonts are Inter and Space Grotesk (SIL OFL 1.1).

## Rewired to real data
- Orb and wave: driven by the recogniser's real loudness, the executor's real state and the text-to-speech state.
- Tools: the list is the real tool registry, with the real risk level and whether Android currently allows it.
- Logs: real saved conversations (search, Voice / Actions filters, Today / Yesterday groups) and the real audit record.
- Prefs: voice, speed, spoken replies, ambient grid, reduce motion and orb glow all change real behaviour; permission chips read Android's state; storage shows the real size.

## Left out on purpose
Invented numbers (latency, sync percentages, counters), "Knox Gateway", "Verified Sandbox", "HW Accelerated", "AES encrypted archive" and "zero telemetry" claims (none can be shown to be true), a switch that would bypass the call confirmation, buttons that only simulate a run, a "Hey Jarvis" wake word, the suggestion pills "Send audio note" and "Schedule call".

## Not built
Light / Solar / System themes: the design has no light version, so only dark exists.
