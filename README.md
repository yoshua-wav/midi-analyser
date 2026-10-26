# midi-analyzer

A simple Java CLI tool to debug `.mid` files and detect hardware glitches or serial transmission errors from digital pianos. 

DAWs usually ignore or silently correct bad MIDI data. This script parses the raw events using native `javax.sound.midi` (no dependencies) and flags anomalies.

## Detected Errors

* **Hanging Notes:** `note_on` events without a matching `note_off`.
* **Rapid Retrigger (Key Bouncing):** Notes that retrigger unnaturally fast.
* **Ghost Notes:** Notes with an impossibly short duration (accidental micro-touches).
* **0-Tick Hardware Glitches:** A note being released and pressed again in the exact same tick.
* **Redundant CC64 Spam:** The sustain pedal sending the same value multiple times in a row.
* **Out-of-Range Data:** Pitch or velocity bytes outside the standard 0-127 range.

*Note: The time thresholds for rapid retriggers and ghost notes are calculated dynamically based on the file's resolution (ticks per beat).*

## Usage

Compile and run the script from your terminal:

```bash
javac MidiAnalyzer.java
java MidiAnalyzer
```

The script will prompt you for the path to your .mid file. You can choose to print the full chronological event log or filter the output to only show warnings and errors.
