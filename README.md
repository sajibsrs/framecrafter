# FrameCraft

A camera for Minecraft (Fabric, 26.3). Works without a shader pack.

- **Focal length** 14–200 mm on a full-frame sensor; sets the field of view.
- **Aperture** f/1.4–f/22 in third stops.
- **Depth of field** from the thin-lens circle of confusion, so the blur matches a real lens (1 block = 1 m).
- **Autofocus** on whatever is under the focus box; hold right mouse to lock it.
- **Shutter** saves a clean `framecraft_*.png` to `screenshots/`, without the viewfinder.

## Controls (camera mode)

| Input | Action |
|---|---|
| `V` | Enter / leave camera mode |
| Scroll | Zoom |
| Ctrl + Scroll | Aperture |
| Right mouse (hold) | Lock focus |
| Left mouse | Take photo |

When an Iris shader pack is active, FrameCraft leaves depth of field to the pack; zoom, viewfinder and capture still work.

## Building

```
./gradlew build              # jar in build/libs
./gradlew runClientGameTest  # opens a test world and saves screenshots to build/run/clientGameTest/screenshots
```
