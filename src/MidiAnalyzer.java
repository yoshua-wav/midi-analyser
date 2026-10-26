import javax.sound.midi.*;
import java.io.File;
import java.util.HashMap;
import java.util.Scanner;





public class MidiAnalyzer {

    public static void main(String[] args) {
        boolean input = true;
        Scanner scanner = new Scanner(System.in);
        String filePath = "";
        while (input) {
            System.out.print("Midi-File path: ");
            filePath = scanner.nextLine();
            if (!filePath.endsWith(".mid")) {
                System.out.printf("Invalid file type: %s\n", filePath);
            } else {
                input = false;
            }
        }




        try {
            // Load the MIDI file into a Sequence
            File midiFile = new File(filePath);
            Sequence sequence = MidiSystem.getSequence(midiFile);

            // Print basic track information
            System.out.println();
            double lengthInSeconds = sequence.getMicrosecondLength() / 1_000_000.0;
            System.out.printf("Length: %.2f seconds%n", lengthInSeconds);
            System.out.println("Ticks per Beat (Resolution): " + sequence.getResolution());

            Track[] tracks = sequence.getTracks();

            // Iterate through all tracks
            for (int i = 0; i < tracks.length; i++) {
                System.out.println("\n--- Track " + i + " ---");
                Track track = tracks[i];
                int count = 0;

                //new HashMap for collecting hanging notes
                HashMap<Integer, Long> activeNotes = new HashMap<>();

                //new HashMap for Rapid Retrigger and threshold for rapid Retrigger
                HashMap<Integer, Long> lastTriggerTick = new HashMap<>();

                //Threshold for Chatter/Rapid Retrigger and Ghost Notes
                final long CHATTER_THRESHOLD = 200;
                final long GHOST_NOTE_THRESHOLD = 50;

                //variable for last Sustain Value
                int lastSustainValue = -1; //-> no event transmitted

                // Iterate through all events within the track
                for (int j = 0; j < track.size(); j++) {
                    MidiEvent event = track.get(j);
                    MidiMessage message = event.getMessage();



                    // Filter for ShortMessages (Notes, Control Change)
                    if (message instanceof ShortMessage) {

                        ShortMessage sm = (ShortMessage) message;
                        int command = sm.getCommand();

                        // Match Note On, Note Off, and Control Change (Pedal)
                        if (command == ShortMessage.NOTE_ON ||
                                command == ShortMessage.NOTE_OFF ||
                                command == ShortMessage.CONTROL_CHANGE) {

                            // Extract relevant data for analysis
                            int channel = sm.getChannel();
                            int data1 = sm.getData1(); // Note number or Controller number
                            int data2 = sm.getData2(); // Velocity or Controller value

                            //check if out-of-range-data
                            if (data1 < 0 || data1 > 127 || data2 < 0 || data2 > 127) {
                                System.out.printf("[WARN] Tick %d: Invalid data byte detected", event.getTick());
                            }

                            //Analysis
                            boolean isNoteOn = (command == ShortMessage.NOTE_ON && data2 > 0);
                            boolean isNoteOff = (command == ShortMessage.NOTE_OFF) || (command == ShortMessage.NOTE_ON && data2 == 0);

                            if (isNoteOn) {
                                if (activeNotes.containsKey(data1)) {
                                    // Note is already active: potential sensor bounce or dropped note_off
                                    long startTick = activeNotes.get(data1);
                                    System.out.printf("[WARN] Tick %d: Note %d retriggered while still active (started at tick %d)%n",
                                            event.getTick(), data1, startTick);

                                    //calculate Distance between the last Tick
                                    long delta = event.getTick() - lastTriggerTick.get(data1);
                                    if (lastTriggerTick.containsKey(data1) && delta < CHATTER_THRESHOLD) {
                                        System.out.printf("[WARN) Tick %d: Note retriggered in %d Ticks (Rapid Retrigger)", event.getTick(), delta);
                                    }
                                    //put last Tick in lastTriggerTick
                                    lastTriggerTick.put(data1, event.getTick());
                                }
                                // Track note start tick
                                activeNotes.put(data1, event.getTick());

                            } else if (isNoteOff) {
                                if (activeNotes.containsKey(data1)) {
                                    //Test if Ghost-Note
                                    long duration = event.getTick() - activeNotes.get(data1);
                                    if (duration < GHOST_NOTE_THRESHOLD) {
                                        System.out.printf("[WARN] Tick : %d: Ghost Note spotted",  event.getTick());
                                    }

                                    // Normal release: remove note from active state
                                    activeNotes.remove(data1);
                                } else {
                                    // Received release event without preceding note_on
                                    System.out.printf("[WARN] Tick %d: Note %d released, but was not active%n",
                                            event.getTick(), data1);
                                }
                            } else if (command == ShortMessage.CONTROL_CHANGE) {
                                if (data1 == 64) { //if Sustain-Pedal
                                    if (data2 == lastSustainValue) {
                                        System.out.printf("[WARN] Tick %d: redundant sustain-pedal activity", event.getTick());
                                    }
                                }
                            }





                            // Format the output
                            String eventName = getEventName(command, data2);
                            System.out.printf("Tick %-6d | %-14s | channel=%d data1=%d value/velocity=%d%n",
                                    event.getTick(), eventName, channel, data1, data2);

                            count++;
                            if (count >= 300) {
                                System.out.println("... (further events truncated)");
                                break;
                            }
                        }
                    }
                    // Filter for MetaMessages (Tempo, Track Name)
                    else if (message instanceof MetaMessage) {
                        MetaMessage mm = (MetaMessage) message;
                        // 0x51 is the standard HEX code for a tempo change event
                        if (mm.getType() == 0x51) {
                            System.out.printf("Tick %-6d | %-14s%n", event.getTick(), "set_tempo");
                            count++;
                        }
                    }
                }

                //Search for possible hanging notes in Hashmap
                if (!activeNotes.isEmpty()) {
                    System.out.println("\n[ERROR] Hanging notes detected (never released):");
                    for (var entry : activeNotes.entrySet()) {
                        System.out.printf("  -> Note %d (triggered at tick %d)%n", entry.getKey(), entry.getValue());
                    }
                } else {
                    System.out.println("\n[OK] No hanging notes found on this track.");
                }

            }
        } catch (Exception e) {
            System.err.println("Error reading MIDI file: " + e.getMessage());
        } finally {
            scanner.close();
        }
    }

    // Helper method to translate MIDI commands into readable strings
    private static String getEventName(int command, int velocity) {
        if (command == ShortMessage.NOTE_ON && velocity > 0) return "note_on";
        // A Note On with 0 velocity is functionally a Note Off in MIDI standard
        if (command == ShortMessage.NOTE_ON && velocity == 0) return "note_off";
        if (command == ShortMessage.NOTE_OFF) return "note_off";
        if (command == ShortMessage.CONTROL_CHANGE) return "control_change";
        return "unknown";
    }
}

