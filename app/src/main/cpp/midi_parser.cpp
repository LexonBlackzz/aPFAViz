// midi_parser.cpp — see midi_parser.h.
//
// Each MIDI message becomes a compact PlayEvent appended to eventPool in FILE
// order — note-on when parsed, note-off when parsed. Keeping parse order retains
// the useful locality of note-on records while the time-sorted events[] table
// preserves the exact playback/render ordering.
//
// Times are stored as TICKS in absMicroSec during parsing and converted to
// microseconds in place. A note-on's link temporarily holds its partner pool
// index, then becomes the note's absolute end time. No runtime sister pointer
// is stored inside the 16-byte event.
#include "midi_parser.h"

#include <algorithm>
#include <array>
#include <cstdlib>   // srand (PFA seeds the RNG once at startup)
#include <ctime>     // time   (srand seed source)
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "platform.h"
#include "parallel_load.h"

namespace apfa {
namespace {

// Bounds-checked big-endian byte cursor.
struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;

    uint8_t u8() {
        if (p >= end) { ok = false; return 0; }
        return *p++;
    }
    uint32_t u16() { uint32_t a = u8(), b = u8(); return (a << 8) | b; }
    uint32_t u32() {
        uint32_t a = u8(), b = u8(), c = u8(), d = u8();
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
    uint32_t varlen() {
        uint32_t v = 0;
        for (int i = 0; i < 4; i++) {
            uint8_t c = u8();
            v = (v << 7) | (c & 0x7F);
            if (!(c & 0x80)) break;
        }
        return v;
    }
    void skip(uint32_t n) {
        if (p + n > end) { p = end; ok = false; } else { p += n; }
    }
};

struct TempoEvent { uint32_t tick; uint32_t usPerQuarter; };
struct TempoSeg   { uint32_t tick; uint64_t usAtTick; uint32_t usPerQuarter; };
