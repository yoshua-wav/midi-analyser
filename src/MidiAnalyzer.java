import javax.sound.midi.*;
import java.io.File;
import java.util.HashMap;
import java.util.Scanner;

public class MidiAnalyzer {

    // ANSI escape codes for console colors
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String filePath = "";
        boolean input = true;

        while (input) {
            System.out.print("Midi-File path: ");
            filePath = scanner.nextLine();
            if (!filePath.endsWith(".mid")) {
                System.out.printf(ANSI_RED + "Invalid file type: %s%n" + ANSI_RESET, filePath);
            } else {
                input = false;
            }
        }

        // Prompt for detailed event logging
        System.out.print("Print all individual events? (y/n): ");
        String printChoice = scanner.nextLine().trim().toLowerCase();
        boolean printEvents = printChoice.equals("y") || printChoice.equals("yes");

        try {
            // Load the MIDI file into a Sequence
            File midiFile = new File(filePath);
            Sequence sequence = MidiSystem.getSequence(midiFile);

            System.out.println("\n=========================================");
            System.out.println(" MIDI ANALYSIS REPORT");
            System.out.println("=========================================");
            double lengthInSeconds = sequence.getMicrosecondLength() / 1_000_000.0;
            System.out.printf("Length: %.2f seconds%n", lengthInSeconds);
            System.out.println("Ticks per Beat (Resolution): " + sequence.getResolution());

            // Dynamic Thresholds based on file resolution
            final long CHATTER_THRESHOLD = sequence.getResolution() / 8;
            final long GHOST_NOTE_THRESHOLD = sequence.getResolution() / 16;

            Track[] tracks = sequence.getTracks();

            // Iterate through all tracks
            for (int i = 0; i < tracks.length; i++) {
                System.out.println("\n-----------------------------------------");
                System.out.println(" TRACK " + i);
                System.out.println("-----------------------------------------");
                Track track = tracks[i];
                int printedEventsCount = 0;

                // HashMaps for tracking active states and timestamps
                HashMap<Integer, Long> activeNotes = new HashMap<>();
                HashMap<Integer, Long> lastTriggerTick = new HashMap<>();
                HashMap<Integer, Long> lastReleaseTick = new HashMap<>();
                int lastSustainValue = -1;

                // Iterate through all events within the track
                for (int j = 0; j < track.size(); j++) {
                    MidiEvent event = track.get(j);
                    MidiMessage message = event.getMessage();

                    // Filter for ShortMessages (Notes, Control Change)
                    if (message instanceof ShortMessage) {
                        ShortMessage sm = (ShortMessage) message;
                        int command = sm.getCommand();

                        if (command == ShortMessage.NOTE_ON ||
                                command == ShortMessage.NOTE_OFF ||
                                command == ShortMessage.CONTROL_CHANGE) {

                            // Extract relevant data for analysis
                            int channel = sm.getChannel();
                            int data1 = sm.getData1(); // Note or Controller number
                            int data2 = sm.getData2(); // Velocity or Controller value

                            // Check for out-of-range data
                            if (data1 < 0 || data1 > 127 || data2 < 0 || data2 > 127) {
                                System.out.printf(ANSI_RED + "  [WARN] Tick %d: Invalid data byte detected%n" + ANSI_RESET, event.getTick());
                            }

                            // Analysis logic
                            boolean isNoteOn = (command == ShortMessage.NOTE_ON && data2 > 0);
                            boolean isNoteOff = (command == ShortMessage.NOTE_OFF) || (command == ShortMessage.NOTE_ON && data2 == 0);

                            if (isNoteOn) {
                                if (activeNotes.containsKey(data1)) {
                                    // Note is already active
                                    long startTick = activeNotes.get(data1);
                                    System.out.printf(ANSI_RED + "  [WARN] Tick %d: Note %d retriggered while active (started tick %d)%n" + ANSI_RESET,
                                            event.getTick(), data1, startTick);
                                }

                                // Hardware-Glitch: Triggered in the exact same tick it was released
                                if (lastReleaseTick.containsKey(data1) && (event.getTick() - lastReleaseTick.get(data1)) == 0) {
                                    System.out.printf(ANSI_RED + "  [WARN] Tick %d: Note %d triggered in exact same tick it was released (Hardware Glitch)%n" + ANSI_RESET,
                                            event.getTick(), data1);
                                }

                                // Rapid Retrigger check
                                if (lastTriggerTick.containsKey(data1)) {
                                    long delta = event.getTick() - lastTriggerTick.get(data1);
                                    if (delta > 0 && delta < CHATTER_THRESHOLD) {
                                        System.out.printf(ANSI_RED + "  [WARN] Tick %d: Note %d retriggered in %d Ticks (Rapid Retrigger)%n" + ANSI_RESET,
                                                event.getTick(), data1, delta);
                                    }
                                }

                                lastTriggerTick.put(data1, event.getTick());
                                activeNotes.put(data1, event.getTick());

                            } else if (isNoteOff) {
                                if (activeNotes.containsKey(data1)) {
                                    // Test for Ghost-Note
                                    long duration = event.getTick() - activeNotes.get(data1);
                                    if (duration < GHOST_NOTE_THRESHOLD) {
                                        System.out.printf(ANSI_RED + "  [WARN] Tick %d: Ghost Note spotted (Duration: %d Ticks)%n" + ANSI_RESET,
                                                event.getTick(), duration);
                                    }
                                    activeNotes.remove(data1);
                                } else {
                                    System.out.printf(ANSI_RED + "  [WARN] Tick %d: Note %d released, but was not active%n" + ANSI_RESET,
                                            event.getTick(), data1);
                                }

                                lastReleaseTick.put(data1, event.getTick());

                            } else if (command == ShortMessage.CONTROL_CHANGE) {
                                if (data1 == 64) { // Sustain-Pedal
                                    if (data2 == lastSustainValue) {
                                        System.out.printf(ANSI_RED + "  [WARN] Tick %d: Redundant sustain-pedal activity%n" + ANSI_RESET, event.getTick());
                                    }
                                    lastSustainValue = data2;
                                }
                            }

                            // Console Output formatting
                            if (printEvents && printedEventsCount < 300) {
                                String eventName = getEventName(command, data2);
                                System.out.printf("  Tick %-6d | %-14s | channel=%d data1=%d value/velocity=%d%n",
                                        event.getTick(), eventName, channel, data1, data2);

                                printedEventsCount++;
                                if (printedEventsCount == 300) {
                                    System.out.println("  ... (further events truncated from console, analysis continues in background)");
                                }
                            }
                        }
                    }
                    // Filter for MetaMessages (Tempo, Track Name)
                    else if (message instanceof MetaMessage) {
                        if (printEvents && printedEventsCount < 300) {
                            MetaMessage mm = (MetaMessage) message;
                            if (mm.getType() == 0x51) {
                                System.out.printf("  Tick %-6d | %-14s%n", event.getTick(), "set_tempo");
                                printedEventsCount++;
                            }
                        }
                    }
                }

                // Search for possible hanging notes after track ends
                if (!activeNotes.isEmpty()) {
                    System.out.println(ANSI_RED + "\n  [ERROR] Hanging notes detected (never released):" + ANSI_RESET);
                    for (var entry : activeNotes.entrySet()) {
                        System.out.printf(ANSI_RED + "    -> Note %d (triggered at tick %d)%n" + ANSI_RESET, entry.getKey(), entry.getValue());
                    }
                } else {
                    System.out.println(ANSI_GREEN + "\n  [OK] No hanging notes found on this track." + ANSI_RESET);
                }
            }

            System.out.println("\n=========================================");
            System.out.println(" ANALYSIS COMPLETE");
            System.out.println("=========================================\n");

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error reading MIDI file: " + e.getMessage() + ANSI_RESET);
        } finally {
            scanner.close();
        }
    }

    // Helper method to translate MIDI commands into readable strings
    private static String getEventName(int command, int velocity) {
        if (command == ShortMessage.NOTE_ON && velocity > 0) return "note_on";
        if (command == ShortMessage.NOTE_ON && velocity == 0) return "note_off";
        if (command == ShortMessage.NOTE_OFF) return "note_off";
        if (command == ShortMessage.CONTROL_CHANGE) return "control_change";
        return "unknown";
    }
}