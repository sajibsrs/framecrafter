# Framecraft

A camera for Minecraft (Fabric, 26.3). Works without a shader pack.

- **Focal length** 14–200 mm on a full-frame sensor; sets the field of view.
- **Aperture** f/1.2–f/22 in third stops.
- **Depth of field** from the thin-lens circle of confusion, so the blur matches a real lens (1 block = 1 m). The game test checks rendered edge blur against the formula.
- **Back-button autofocus**: hold right mouse to focus on whatever is under the focus box. The box turns green once the subject is sharp, red if the lens can't focus (open sky, or closer than the lens's minimum focus distance). Release and focus stays put.
- **Movable focus point**: hold Ctrl and move the mouse; it starts in the centre each time you enter camera mode.
- **Shutter** saves a clean `framecraft_*.png` to `screenshots/`, without the viewfinder.

## Controls (camera mode)

| Input | Action |
|---|---|
| `V` | Enter / leave camera mode |
| Scroll | Zoom |
| Ctrl + Scroll | Aperture |
| Right mouse (hold) | Autofocus |
| Ctrl + Mouse move | Move focus point |
| Left mouse | Take photo |
| `F1` | Hide the viewfinder |

Works with Iris shader packs: depth of field is applied on top of the pack's image.

## Building

```
./gradlew build              # jar in build/libs
./gradlew runClientGameTest  # opens a test world and saves screenshots to build/run/clientGameTest/screenshots
```
