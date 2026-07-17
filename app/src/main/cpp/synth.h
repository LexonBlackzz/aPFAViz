// synth.h — BASS + BASSMIDI wrapper, immediate raw call path.
//
// noteOn/noteOff call BASS_MIDI_StreamEvents(BASS_MIDI_EVENTS_RAW) immediately
// on the engine thread, one event per call, using raw 3-byte MIDI messages.
// Raw format avoids the struct decode overhead — same bytes OmniMIDI feeds
// BASSMIDI via SendDirectData. Timing is identical to the previous direct path.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

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

    void noteOn(int channel, int key, int velocity);
    void noteOff(int channel, int key);
    void flush();   // no-op, kept for call-site compat

    void sampleEventCost(uint64_t& calls, uint64_t& micros, uint64_t& bpMicros);

    bool ready() const { return ready_; }
    void shutdown();

    void sendRaw(uint8_t status, uint8_t d1, uint8_t d2);

private:
    HSTREAM    midiStream_ = 0;
    HSOUNDFONT font_       = 0;
    int  sampleRate_ = 48000;
    bool ready_      = false;

    std::atomic<uint64_t> evCalls_{0};
    std::atomic<uint64_t> evMicros_{0};
};

}  // namespace apfa
