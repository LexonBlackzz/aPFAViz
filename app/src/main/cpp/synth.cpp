// synth.cpp — see synth.h.
#include "synth.h"

#include <time.h>
#include "platform.h"

namespace apfa {

static uint64_t nowUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + ts.tv_nsec / 1000;
}

// --- limiter ----------------------------------------------------------------
static float g_limEnv    = 0.0f;
static bool  g_limActive = false;

static void CALLBACK limiterDSP(HDSP, DWORD, void* buffer, DWORD length, void*) {
    if (!g_limActive) { g_limActive = true; LOGI("limiter DSP running"); }
    float* p = static_cast<float*>(buffer);
    const size_t n = length / sizeof(float);
    const float kCeiling = 0.60f;
    const float kAttack  = 0.25f;
    const float kRelease = 0.0001f;
    float env = g_limEnv;
    for (size_t i = 0; i < n; i++) {
        float s = p[i];
        float a = s < 0.0f ? -s : s;
        env += (a > env ? kAttack : kRelease) * (a - env);
        if (env > kCeiling) s *= kCeiling / env;
        if      (s >  1.0f) s =  1.0f;
        else if (s < -1.0f) s = -1.0f;
        p[i] = s;
    }
    g_limEnv = env;
}

// ---------------------------------------------------------------------------

bool Synth::init(int voiceLimit, int sampleRate) {
    sampleRate_ = sampleRate;
    if (!BASS_Init(-1, sampleRate_, 0, nullptr, nullptr)) {
        LOGE("BASS_Init failed: %d", BASS_ErrorGetCode());
        return false;
    }
    BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, 100000);
    BASS_SetConfig(BASS_CONFIG_BUFFER, 100);
    BASS_SetConfig(BASS_CONFIG_UPDATEPERIOD, 10);

    midiStream_ = BASS_MIDI_StreamCreate(16, BASS_SAMPLE_FLOAT, sampleRate_);
    if (!midiStream_) {
        LOGE("BASS_MIDI_StreamCreate failed: %d", BASS_ErrorGetCode());
        return false;
    }
    setVoiceLimit(voiceLimit);
    g_limEnv = 0.0f;
    g_limActive = false;
    if (!BASS_ChannelSetDSP(midiStream_, &limiterDSP, nullptr, 0))
        LOGE("limiter DSP attach failed: %d", BASS_ErrorGetCode());

    // Pre-allocate frame buffer — 4096 bytes handles ~1365 note events per
    // frame without reallocation. Grows automatically if needed.

    ready_ = true;
    LOGI("Synth ready: BASSMIDI 0x%08X, %d Hz, voices=%d, raw batch path, limiter on",
         BASS_MIDI_GetVersion(), sampleRate_, voiceLimit);
    return true;
}

bool Synth::loadSoundfont(const std::string& path) {
    if (!midiStream_) return false;
    font_ = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!font_) { LOGE("BASS_MIDI_FontInit failed: %d", BASS_ErrorGetCode()); return false; }
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
    if (midiStream_)
        BASS_ChannelSetAttribute(midiStream_, BASS_ATTRIB_MIDI_VOICES,
                                 static_cast<float>(voices));
}

void Synth::start(uint64_t) {
    if (midiStream_) BASS_ChannelPlay(midiStream_, FALSE);
}

void Synth::pause() {
    if (midiStream_) BASS_ChannelPause(midiStream_);
}

void Synth::resume() {
    if (midiStream_) BASS_ChannelPlay(midiStream_, FALSE);
}

void Synth::allNotesOff() {
    if (!midiStream_) return;
    // All-notes-off (CC 123) and Sustain-off (CC 64) on all 16 channels.
    // We also explicitly reset Pitch Bend to center (8192) so that seeking
    // backward past a pitch-bend doesn't leave the channel permanently bent!
    for (int c = 0; c < 16; c++) {
        sendRaw(static_cast<uint8_t>(0xB0 | c), 123, 0);
        sendRaw(static_cast<uint8_t>(0xB0 | c), 64, 0);
        sendRaw(static_cast<uint8_t>(0xE0 | c), 0, 64); // Pitch Bend Center (LSB=0, MSB=64 -> 8192)
    }
    flush();
}

// --- immediate raw call path ------------------------------------------------
// Fire BASS_MIDI_StreamEvents immediately per note using raw MIDI bytes,
// same timing as before but no struct overhead — BASSMIDI decodes raw bytes
// directly, same as OmniMIDI's SendDirectData path.

void Synth::sendRaw(uint8_t status, uint8_t d1, uint8_t d2) {
    if (!midiStream_) return;
    uint8_t buf[3] = { status, d1, d2 };
    
    // Program Change (0xC0) and Channel Aftertouch (0xD0) are 2-byte messages.
    // BASS_MIDI_EVENTS_RAW reads sequentially; sending a 3rd byte (d2=0) would
    // be parsed as running status data (e.g. Program Change 0) and instantly undo
    // the instrument change!
    int len = ((status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0) ? 2 : 3;

    uint64_t t0 = nowUs();
    BASS_MIDI_StreamEvents(midiStream_, BASS_MIDI_EVENTS_RAW | BASS_MIDI_EVENTS_ASYNC, buf, len);
    evMicros_.fetch_add(nowUs() - t0, std::memory_order_relaxed);
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
    // Nothing to flush — events fire immediately. Kept for call-site compat.
}

void Synth::sampleEventCost(uint64_t& calls, uint64_t& micros, uint64_t& bpMicros) {
    calls    = evCalls_.exchange(0,  std::memory_order_relaxed);
    micros   = evMicros_.exchange(0, std::memory_order_relaxed);
    bpMicros = 0;
}

void Synth::shutdown() {
    ready_ = false;
    if (midiStream_) { BASS_StreamFree(midiStream_); midiStream_ = 0; }
    if (font_)       { BASS_MIDI_FontFree(font_);    font_ = 0; }
    BASS_Free();
}

}  // namespace apfa
