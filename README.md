# aPFAViz v1.3.1

So.. we have many many Black MIDI players for Android, but not one seemingly has tried to replicate PFAs engine...
**UNTIL NOW!** 
aPFAViz is an Android Black MIDI player inspired by PFA/PFAViz, keeping the familiar PFA-style playback model while adding Android-focused performance, streaming, and UI improvements.

aPFAViz's current features as of **v1.3.1** are:
- Manual/Automatic core affinity chooser. (can choose various cores, or if you don't care, aPFAViz will choose your strongest core!) 
- Track skimming (if a little slow, aka you can go to random points in the MIDI) 
- A 1:1 of PianoFromAboves actual engine! 
- Background color chooser along with custom images
- Color profiles!
- Legacy OpenGL ES 2.0 renderer
- Responsive portrait + landscape UI
- **Pagefiling! (and chunked paging) for very large MIDIs!** (with SD cards too for 64-bit!)

  <img width="48" height="48" alt="aPFAViz logo" src="https://github.com/user-attachments/assets/cc1f24f7-b5a1-4daa-87d9-4bca952d007e" />

## Android requirement

The Liquid Glass UI experiment raises the minimum supported version to **Android 7.0 (API 24)**. The native playback engine remains the same. Launcher glass is provided by QWEA0/Liquid-Glass-Android (MIT); the live piano-roll surface intentionally stays on lightweight native/translucent chrome so UI effects do not add capture/compositing work to PFA-faithful playback.

## Contributors

- Starzainia
- HexagonMIDIs
- mappazinho
- LexonBlackzz
