// synth.cpp — see synth.h.
#include "synth.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <time.h>
#include <unistd.h>
#include "platform.h"

namespace apfa {

// Never thin below this many voices: past here the audio stops being a
// quieter version of the piece and starts being a different one. The point is
// that it keeps playing, not that it stays faithful at any cost.
static const int kVoiceFloor = 16;

static uint64_t nowUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + ts.tv_nsec / 1000;
}

// --- transparent master DSP --------------------------------------------------
// Inspired by the audio discipline in SuperVirtualMIDISynth: stereo-linked
// dynamics, a short lookahead, and a subsonic/DC blocker. The old aPFAViz DSP
// used a 0.60 ceiling and one envelope over individual interleaved samples,
// which audibly flattened dense chords and let left/right samples influence
// each other as if they were successive mono samples.
//
// This state is touched only by BASS's DSP/update thread after init().
struct MasterDspState {
    static constexpr uint32_t kDelayFrames = 512;
    float delay[kDelayFrames * 2] = {};
    uint32_t writePos = 0;
    uint32_t lookahead = 96;      // overwritten from sample rate (~2 ms)
    float gain = 1.0f;
    float releaseCoeff = 0.0f;
    float hpPole = 0.0f;
    float prevInL = 0.0f, prevInR = 0.0f;
    float prevOutL = 0.0f, prevOutR = 0.0f;
    bool active = false;

    void reset(int sampleRate) {
        std::memset(delay, 0, sizeof(delay));
        writePos = 0;
        const int sr = sampleRate > 0 ? sampleRate : 48000;
        lookahead = static_cast<uint32_t>(
            std::max(1, std::min<int>(kDelayFrames - 1, sr * 2 / 1000)));
        // ~80 ms recovery: fast enough not to leave the song ducked after a
        // wall, slow enough not to pump between individual Black-MIDI peaks.
        releaseCoeff = 1.0f - std::exp(-1.0f / (0.080f * sr));
        hpPole = std::exp(-6.28318530718f * 3.0f / sr);
        gain = 1.0f;
        prevInL = prevInR = prevOutL = prevOutR = 0.0f;
        active = false;
    }

    inline void highPass(float& l, float& r) {
        const float yl = l - prevInL + hpPole * prevOutL;
        const float yr = r - prevInR + hpPole * prevOutR;
        prevInL = l; prevInR = r;
        prevOutL = yl; prevOutR = yr;
        l = yl; r = yr;
    }

    void process(float* p, size_t samples) {
        if (!p || samples < 2) return;
        constexpr float kThreshold = 0.985f;
        constexpr float kSafety = 0.9995f;

        const size_t frames = samples / 2;
        for (size_t f = 0; f < frames; ++f) {
            const float inL = p[f * 2];
            const float inR = p[f * 2 + 1];
            const float peak = std::max(std::fabs(inL), std::fabs(inR));
            const float required =
                (peak > kThreshold && peak > 0.0f) ? kThreshold / peak : 1.0f;

            // Immediate gain reduction plus a slow release. Because the audible
            // sample is delayed by lookahead frames, the detector sees a peak
            // before that peak reaches the output without needing a costly
            // sliding-max structure.
            if (required < gain) gain = required;
            else gain += releaseCoeff * (required - gain);

            const uint32_t readPos =
                (writePos + kDelayFrames - lookahead) % kDelayFrames;
            float outL = delay[readPos * 2] * gain;
            float outR = delay[readPos * 2 + 1] * gain;

            delay[writePos * 2] = inL;
            delay[writePos * 2 + 1] = inR;
            writePos = (writePos + 1) % kDelayFrames;

            // Stereo-linked final safety catch; unlike independent clipping it
            // cannot pull the stereo image sideways on a loud asymmetric peak.
            const float outPeak = std::max(std::fabs(outL), std::fabs(outR));
            if (outPeak > kSafety) {
                const float scale = kSafety / outPeak;
                outL *= scale;
                outR *= scale;
            }

            highPass(outL, outR);
            p[f * 2] = outL;
            p[f * 2 + 1] = outR;
        }
    }
};

static MasterDspState g_masterDsp;

static void CALLBACK masterDSP(HDSP, DWORD, void* buffer, DWORD length, void*) {
    if (!g_masterDsp.active) {
        g_masterDsp.active = true;
        LOGI("master DSP running: stereo-linked lookahead limiter + 3 Hz HPF");
    }
    g_masterDsp.process(static_cast<float*>(buffer), length / sizeof(float));
}



bool Synth::init(int voiceLimit, int sampleRate) {
    sampleRate_ = sampleRate;
    if (!BASS_Init(-1, sampleRate_, 0, nullptr, nullptr)) {
        LOGE("BASS_Init failed: %d", BASS_ErrorGetCode());
        return false;
    }
    BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, 100000);
    // Keep enough safety margin for older phones. Event timing is decoupled
    // from the visual frame by the scheduler rather than by making the device
    // buffer dangerously small.
    BASS_SetConfig(BASS_CONFIG_BUFFER, 100);
    BASS_SetConfig(BASS_CONFIG_UPDATEPERIOD, 10);

    midiStream_ = BASS_MIDI_StreamCreate(
        16, BASS_SAMPLE_FLOAT | BASS_MIDI_ASYNC, sampleRate_);
    if (!midiStream_) {
        LOGE("BASS_MIDI_StreamCreate failed: %d", BASS_ErrorGetCode());
        return false;
    }
    rawBatch_.clear();
    rawBatch_.reserve(kRawBatchBytes);
    setVoiceLimit(voiceLimit);
    voiceCeiling_.store(voiceLimit < 1 ? 1 : voiceLimit);
    voiceCurrent_.store(voiceLimit < 1 ? 1 : voiceLimit);
    // Preallocate the live-event queue so a dense wall does not force BASSMIDI
    // to grow it on its real-time update path.
    if (!BASS_ChannelSetAttribute(
            midiStream_, BASS_ATTRIB_MIDI_QUEUE_ASYNC, 131072.0f))
        LOGE("BASS async queue preallocation failed: %d", BASS_ErrorGetCode());

    // 16-point sinc is the highest BASSMIDI SoundFont interpolation mode. It
    // is NEON-accelerated on supported ARM builds. Fall back to 8-point sinc,
    // then linear, rather than making synth startup fail on an older binary.
    int srcQuality = 2;
    if (!BASS_ChannelSetAttribute(midiStream_, BASS_ATTRIB_MIDI_SRC, 2.0f)) {
        srcQuality = 1;
        if (!BASS_ChannelSetAttribute(midiStream_, BASS_ATTRIB_MIDI_SRC, 1.0f))
            srcQuality = 0;
    }

    g_masterDsp.reset(sampleRate_);
    if (!BASS_ChannelSetDSP(midiStream_, &masterDSP, nullptr, 0))
        LOGE("master DSP attach failed: %d", BASS_ErrorGetCode());

    // OmniMIDI's own overload setting (BASSSynth.cpp StreamSettings). Kept
    // because it is what the reference synth does and it costs nothing; the
    // guard thread below is what actually holds the line on this build. See
    // the comment in synth.h.
    BASS_ChannelSetAttribute(midiStream_, BASS_ATTRIB_MIDI_CPU, 95.0f);

    startGuard();

    ready_ = true;
    LOGI("Synth ready: BASSMIDI 0x%08X, %d Hz, voices=%d, async queue, "
         "SRC=%d (0=linear 1=8pt 2=16pt), transparent master DSP, guard on",
         BASS_MIDI_GetVersion(), sampleRate_, voiceLimit, srcQuality);
    return true;
}

// --- audio overload guard ----------------------------------------------------

void Synth::startGuard() {
    if (guardRun_.load()) return;
    guardRun_.store(true);
    guardArmed_.store(false);
    // Created here on purpose: Engine::threadMain applies the "avoid the engine
    // core" affinity mask before calling init(), so this thread inherits it and
    // stays off the engine's core, exactly like BASS's own render thread.
    guardThread_ = std::thread(&Synth::guardMain, this);
}

void Synth::stopGuard() {
    if (!guardRun_.exchange(false)) return;
    if (guardThread_.joinable()) guardThread_.join();
}

void Synth::guardMain() {
    // 20 ms cadence: fast enough to react well before a 100 ms playback buffer
    // can empty, slow enough to be free (50 wakeups/s, two cheap BASS calls).
    const int kPeriodUs = 20000;
    while (guardRun_.load(std::memory_order_relaxed)) {
        HSTREAM h = midiStream_;
        if (h && BASS_ChannelIsActive(h) == BASS_ACTIVE_PLAYING) {
            DWORD avail = BASS_ChannelGetData(h, nullptr, BASS_DATA_AVAILABLE);
            if (avail != static_cast<DWORD>(-1)) {
                int bufMs = static_cast<int>(
                    BASS_ChannelBytes2Seconds(h, avail) * 1000.0);
                // Only start steering once the buffer has filled once, so the
                // initial fill (and the refill after a resume or a seek) is not
                // mistaken for an overload.
                if (!guardArmed_.load(std::memory_order_relaxed)) {
                    if (bufMs >= 80) guardArmed_.store(true);
                } else {
                    float cpu = 0.0f;
                    BASS_ChannelGetAttribute(h, BASS_ATTRIB_CPU, &cpu);
                    int cur  = voiceCurrent_.load(std::memory_order_relaxed);
                    int ceil = voiceCeiling_.load(std::memory_order_relaxed);
                    int want = cur;
                    // Cut fast, restore slowly — the reverse pumps audibly.
                    // The CPU term leads (it crosses 100% before the buffer has
                    // drained); the buffer term is the backstop.
                    if      (bufMs < 20)                 want = cur >> 1;
                    else if (bufMs < 50 || cpu > 92.0f)  want = cur - (cur >> 2);
                    else if (bufMs > 80 && cpu < 75.0f)  want = cur + (cur >> 4) + 1;
                    if (want < kVoiceFloor) want = kVoiceFloor;
                    if (want > ceil)        want = ceil;
                    if (want != cur) {
                        voiceCurrent_.store(want, std::memory_order_relaxed);
                        BASS_ChannelSetAttribute(h, BASS_ATTRIB_MIDI_VOICES,
                                                 static_cast<float>(want));
                    }
                    int prev = voiceFloorSeen_.load(std::memory_order_relaxed);
                    while (want < prev &&
                           !voiceFloorSeen_.compare_exchange_weak(prev, want)) {}
                }
            }
        }
        usleep(kPeriodUs);
    }
}

void Synth::sampleGuard(int& voiceCeiling, int& voiceFloorSeen) {
    voiceCeiling   = voiceCurrent_.load(std::memory_order_relaxed);
    int seen       = voiceFloorSeen_.exchange(1 << 30, std::memory_order_relaxed);
    voiceFloorSeen = (seen == (1 << 30)) ? voiceCeiling : seen;
}

bool Synth::loadSoundfont(const std::string& path) {
    if (!midiStream_) return false;
    font_ = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!font_) {
        const int err = BASS_ErrorGetCode();
        // 7000 = BASS_ERROR_MIDI_INCLUDE: an SFZ named a file it could not open.
        // Worth saying out loud — an SFZ is only ever as portable as the sample
        // folder beside it, and the generic code makes that look like a bad font.
        if (err == 7000)
            LOGE("BASS_MIDI_FontInit: SFZ references a file it cannot open (%s)",
                 path.c_str());
        else
            LOGE("BASS_MIDI_FontInit failed: %d", err);
        return false;
    }
    BASS_MIDI_FONT f;
    f.font = font_; f.preset = -1; f.bank = 0;
    if (!BASS_MIDI_StreamSetFonts(midiStream_, &f, 1)) {
        LOGE("BASS_MIDI_StreamSetFonts failed: %d", BASS_ErrorGetCode());
        return false;
    }
    BASS_MIDI_StreamLoadSamples(midiStream_);
    LOGI("Soundfont loaded: %s", path.c_str());
    return true;
}

void Synth::setVoiceLimit(int voices) {
    if (voices < 1) voices = 1;
    voiceCeiling_.store(voices);
    voiceCurrent_.store(voices);
    if (midiStream_)
        BASS_ChannelSetAttribute(midiStream_, BASS_ATTRIB_MIDI_VOICES,
                                 static_cast<float>(voices));
}

void Synth::start(uint64_t) {
    if (midiStream_) BASS_ChannelPlay(midiStream_, FALSE);
}

// Pause releases the notes and leaves the stream RENDERING, exactly as PFA
// does: GameState.cpp:1128 ("If we just paused, kill the music") calls
// MIDIOutDevice::AllNotesOff(), and OmniMIDI — a live output device — keeps
// rendering right through it, so the release tails decay naturally in real
// time and the piece ends up genuinely silent while paused.
//
// This used to call BASS_ChannelPause(), which freezes the whole stream
// mid-sample. That froze the decay instead of letting it finish, and the
// frozen tail (plus whatever was still sitting in the playback buffer) was
// then played back on resume — audible as the previous note's release
// arriving after you had already seeked somewhere else.
void Synth::pause() {
    if (!midiStream_) return;

    // Cancel future async input and release notes in ONE ordered submission.
    // Keeping pitch untouched preserves PFA's pause/resume behavior.
    rawBatch_.clear();
    uint8_t release[16 * 6];
    size_t n = 0;
    for (int c = 0; c < 16; ++c) {
        release[n++] = static_cast<uint8_t>(0xB0 | c);
        release[n++] = 123;
        release[n++] = 0;
        release[n++] = static_cast<uint8_t>(0xB0 | c);
        release[n++] = 64;
        release[n++] = 0;
    }
    const uint64_t t0 = nowUs();
    const DWORD done = BASS_MIDI_StreamEvents(
        midiStream_,
        BASS_MIDI_EVENTS_RAW | BASS_MIDI_EVENTS_ASYNC |
            BASS_MIDI_EVENTS_CANCEL,
        release, static_cast<DWORD>(n));
    evMicros_.fetch_add(nowUs() - t0, std::memory_order_relaxed);
    bassCalls_.fetch_add(1, std::memory_order_relaxed);
    evCalls_.fetch_add(32, std::memory_order_relaxed);
    if (done == static_cast<DWORD>(-1))
        LOGE("BASS pause cancel/release failed: %d", BASS_ErrorGetCode());
    guardArmed_.store(false, std::memory_order_relaxed);
}

void Synth::resume() {
    // Nothing to restart: the stream was never stopped. Re-arm the overload
    // guard because a resume is a natural place for the buffer to be refilling.
    guardArmed_.store(false);
}

// PFA's MIDIOutDevice::AllNotesOff (MIDI.cpp:894-898) verbatim: All-notes-off
// (CC 123) then Sustain-off (CC 64) across all 16 channels. CC 123 releases the
// notes rather than cutting them, which is what makes the tails ring out.
void Synth::releaseAllNotes() {
    if (!midiStream_) return;
    for (int c = 0; c < 16; c++) {
        sendRaw(static_cast<uint8_t>(0xB0 | c), 123, 0);
        sendRaw(static_cast<uint8_t>(0xB0 | c), 64, 0);
    }
    flush();
}

void Synth::allNotesOff() {
    if (!midiStream_) return;
    releaseAllNotes();
    // Seek-only extra: reset Pitch Bend to center (8192) so seeking backward
    // past a pitch-bend doesn't leave the channel permanently bent. Deliberately
    // NOT done on pause — resuming has to keep the bend that was in force.
    for (int c = 0; c < 16; c++)
        sendRaw(static_cast<uint8_t>(0xE0 | c), 0, 64); // LSB=0, MSB=64 -> 8192
    flush();
}

void Synth::resetForSeek() {
    if (!midiStream_) return;

    // One bounded call both invalidates scheduler work that BASS has not
    // processed yet and establishes a clean channel baseline. The scheduler
    // follows this immediately with the latest program/controller/pitch state
    // selected for the new song position.
    rawBatch_.clear();
    uint8_t reset[16 * 9];
    size_t n = 0;
    for (int c = 0; c < 16; ++c) {
        reset[n++] = static_cast<uint8_t>(0xB0 | c); reset[n++] = 123; reset[n++] = 0;
        reset[n++] = static_cast<uint8_t>(0xB0 | c); reset[n++] = 64;  reset[n++] = 0;
        reset[n++] = static_cast<uint8_t>(0xE0 | c); reset[n++] = 0;   reset[n++] = 64;
    }

    const uint64_t t0 = nowUs();
    const DWORD done = BASS_MIDI_StreamEvents(
        midiStream_,
        BASS_MIDI_EVENTS_RAW | BASS_MIDI_EVENTS_ASYNC |
            BASS_MIDI_EVENTS_CANCEL,
        reset, static_cast<DWORD>(n));
    evMicros_.fetch_add(nowUs() - t0, std::memory_order_relaxed);
    bassCalls_.fetch_add(1, std::memory_order_relaxed);
    evCalls_.fetch_add(48, std::memory_order_relaxed);
    if (done == static_cast<DWORD>(-1))
        LOGE("BASS pending-event cancel/reset failed: %d", BASS_ErrorGetCode());
    guardArmed_.store(false, std::memory_order_relaxed);
}

// --- batched raw call path --------------------------------------------------
// Preserve every raw MIDI message and its order, but amortise the BASS API call
// overhead. The engine already dispatches all events due for a frame in one
// tight loop; flush() is its existing frame boundary. A 64 KiB hard boundary
// prevents a catch-up frame from building an unbounded temporary packet.

void Synth::sendRaw(uint8_t status, uint8_t d1, uint8_t d2) {
    if (!midiStream_) return;

    const bool shortMsg = ((status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0);
    const size_t len = shortMsg ? 2u : 3u;
    if (rawBatch_.size() + len > kRawBatchBytes) flush();

    rawBatch_.push_back(status);
    rawBatch_.push_back(d1);
    if (!shortMsg) rawBatch_.push_back(d2);
    evCalls_.fetch_add(1, std::memory_order_relaxed);
}

void Synth::noteOn(int channel, int key, int velocity) {
    sendRaw(static_cast<uint8_t>(0x90 | (channel & 0x0F)),
            static_cast<uint8_t>(key & 0x7F),
            static_cast<uint8_t>(velocity & 0x7F));
}

void Synth::noteOff(int channel, int key) {
    sendRaw(static_cast<uint8_t>(0x80 | (channel & 0x0F)),
            static_cast<uint8_t>(key & 0x7F),
            0);
}

void Synth::flush() {
    if (!midiStream_ || rawBatch_.empty()) return;
    const uint64_t t0 = nowUs();
    BASS_MIDI_StreamEvents(
        midiStream_,
        BASS_MIDI_EVENTS_RAW | BASS_MIDI_EVENTS_ASYNC,
        rawBatch_.data(),
        static_cast<DWORD>(rawBatch_.size()));
    evMicros_.fetch_add(nowUs() - t0, std::memory_order_relaxed);
    bassCalls_.fetch_add(1, std::memory_order_relaxed);
    rawBatch_.clear();
}

void Synth::sampleEventCost(uint64_t& calls, uint64_t& micros, uint64_t& bpMicros) {
    calls = evCalls_.exchange(0, std::memory_order_relaxed);
    micros = evMicros_.exchange(0, std::memory_order_relaxed);
    bpMicros = bassCalls_.exchange(0, std::memory_order_relaxed);
}

void Synth::shutdown() {
    rawBatch_.clear();
    ready_ = false;
    stopGuard();   // must go first: guardMain dereferences midiStream_
    if (midiStream_) { BASS_StreamFree(midiStream_); midiStream_ = 0; }
    if (font_)       { BASS_MIDI_FontFree(font_);    font_ = 0; }
    BASS_Free();
}

}  // namespace apfa