// synth.h — BASS + BASSMIDI wrapper, bounded raw-MIDI submission path.
//
// The normal Android path feeds this object from Engine's dedicated MIDI
// scheduler thread. BASS owns its own render/update thread, so visual GL/vsync
// stalls no longer sit between a due MIDI event and BASSMIDI. The 32-bit sliced
// fallback can still feed it from the visual engine thread.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "bassmidi.h"

namespace apfa {

class Synth {
public:
    bool init(int voiceLimit, int sampleRate = 48000);
    bool loadSoundfont(const std::string& path);
    void setVoiceLimit(int voices);
    void start(uint64_t reservedMask = 0);
    void pause();
    void resume();
    void allNotesOff();
    // Seek/reset boundary: cancel BASSMIDI's pending async events, release all
    // notes/sustain, and center pitch bend before the scheduler restores MIDI
    // controller/program state at the new song position.
    void resetForSeek();
    // Release every sounding note without touching pitch bend — PFA's
    // MIDIOutDevice::AllNotesOff. This is what pause() uses.
    void releaseAllNotes();

    void noteOn(int channel, int key, int velocity);
    void noteOff(int channel, int key);
    void flush();   // submits queued raw MIDI bytes in-order

    void sampleEventCost(uint64_t& calls, uint64_t& micros, uint64_t& bpMicros);
    // Voices actually sounding right now vs the ceiling the guard may lower to.
    // For the perf log only — nothing in the engine reads these.
    void sampleGuard(int& voiceCeiling, int& voiceFloorSeen);

    bool ready() const { return ready_; }
    void shutdown();

    void sendRaw(uint8_t status, uint8_t d1, uint8_t d2);

private:
    // --- audio overload guard -----------------------------------------------
    // BASSMIDI on a slow SoC can need MORE than real time to render a dense
    // Black MIDI: measured 100-127% render load on an SD425, which drains the
    // playback buffer to nothing and makes the audio stutter. Lowering the
    // voice count fixes it, which is why this is the one thing users were
    // being told to do by hand.
    //
    // This is the OmniMIDI analog. OmniMIDI caps its own render load with
    // BASSMIDI's built-in BASS_ATTRIB_MIDI_CPU (BASSSynth.cpp: MaxCPU = 95),
    // which makes BASSMIDI shed voices to stay under the limit. That attribute
    // is set here too, but it did not engage on this BASSMIDI build (2.4.16,
    // Android) - the voice count stayed pinned at the limit while the render
    // load sat at 126%. So the same policy is applied from the outside: a low
    // rate guard thread watches the playback buffer and the render load and
    // moves BASS_ATTRIB_MIDI_VOICES between a floor and the user's setting.
    //
    // This is deliberately AUDIO ONLY. The clock, dispatch, the active-note
    // set, the note count and everything drawn are untouched, so the real-time
    // slowdown that the engine is built around is unaffected - only how many
    // voices sound at once adapts, exactly as OmniMIDI does on the desktop.
    void guardMain();
    void startGuard();
    void stopGuard();

    std::thread       guardThread_;
    std::atomic<bool> guardRun_{false};
    // Set false whenever the buffer has to refill from empty (start, resume,
    // seek): a filling buffer reads as a starving one and would otherwise slam
    // the voice count on the first frame.
    std::atomic<bool> guardArmed_{false};
    std::atomic<int>  voiceCeiling_{250};   // the user's Voice Count setting
    std::atomic<int>  voiceCurrent_{250};   // what the guard has it set to now
    std::atomic<int>  voiceFloorSeen_{1 << 30};

    HSTREAM    midiStream_ = 0;
    HSOUNDFONT font_       = 0;
    int  sampleRate_ = 48000;
    bool ready_      = false;

    // Single-producer raw MIDI batching. On the separated path the dedicated
    // scheduler is the sole producer; on the sliced fallback the visual engine
    // remains the sole producer. Metrics are atomic because the visual perf log
    // samples them while the scheduler is writing them.
    static constexpr size_t kRawBatchBytes = 64 * 1024;
    std::vector<uint8_t> rawBatch_;
    std::atomic<uint64_t> evCalls_{0};    // MIDI messages queued
    std::atomic<uint64_t> bassCalls_{0};  // actual BASS_MIDI_StreamEvents calls
    std::atomic<uint64_t> evMicros_{0};   // wall time spent submitting batches
};

}  // namespace apfa