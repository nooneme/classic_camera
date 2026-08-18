<p align="center">
  <a href="README.md">简体中文</a> &nbsp;|&nbsp; <b>English</b>
</p>

<h1 align="center">📷 Classic Camera</h1>

<p align="center">
  <em>Full RAW Pipeline · WYSIWYG · LUT Learning System</em>
</p>

---

**Classic Camera** is a minimalist, fully-manual camera app built for creation. It skips the manufacturer's image processing entirely and drives the sensor's **RAW data** directly, returning the purest optical quality to you. Every parameter adjustment is applied to RAW data in real time — what you see is what you get.

## ✨ Key Features

### 1. Full RAW Pipeline · Pure Optical Quality

Completely bypasses the vendor ISP (Image Signal Processor). Starting from the Bayer mosaic data, the entire chain — denoising, white balance, CFA reordering, and tone mapping — is handled by the app itself. **No aggressive sharpening, no over-smoothing — just the light the sensor actually captured.**

### 2. Live RAW Preview · What You See Is What You Get

The viewfinder isn't a preview stream from the vendor ISP — full of algorithms and inconsistent exposure — it's a **real-time, full render of the RAW data**. The light, color, and detail you see on screen are exactly what the shutter delivers. No more guessing how the final image will look from experience.

### 3. Parameters Applied to RAW · Skip the Post-Processing Hassle

Exposure, white balance, color temperature, tone curves, film simulation — every adjustment is **applied directly to the RAW pixels** and shown live in the preview. Finish your creative tuning on the spot, without the tedious "shoot first, edit later" workflow.

### 4. Multi-Lens Switching · Full Manual + Semi-Auto Exposure

Automatically detects all **logical and physical lenses** that support RAW output and switches between them with one tap. Shutter / ISO are adjusted via a stepless (logarithmic) mapping, with **shutter-priority / ISO-priority semi-auto** modes to balance pro control with quick snapshots.

### 5. GPU Multi-Frame Denoising · Your Low-Light Ally

A GPU alignment & fusion pipeline built on **OpenGL ES 3.1 compute shaders**: it aligns and merges 2–8 burst RAW frames via coarse-to-fine pyramids and noise-weighted blending, significantly reducing noise while preserving detail in low light.

### 6. Built-in LUT Learning System · Learn Any Filter With Just 2 Photos

The app ships with a **LUT learning engine**:

- **Learn any filter with as few as 2 photos** (one source image + one with the target filter applied)
- Uses **MLS (Moving Least Squares)** fitting to generate a 33³ 3D LUT, reporting live accumulated pixel coverage and fitting-error statistics
- Comes with **56** high-quality film simulation filters (Fujifilm, Ricoh, Leica, Pentax, Lumix, and more)
- Pairs with a **3D vector-field visualization** to inspect the LUT's color mapping (start = source color, end = mapped color, direction = offset), rotatable and zoomable

### 7. Apply Filters to Still Photos · WYSIWYG Post-Processing

Pick any photo from the gallery and preview a LUT filter live on GPU: **adjustable intensity**, **long-press to compare the original**, then save a full-resolution JPG to your album.

### 8. Built-in Gallery & Photo Management

- A built-in **gallery** groups photos by album with thumbnail caching; tap to pick a photo for filter processing
- The photo viewer shows **EXIF info** (camera model / aperture / shutter / ISO, etc.), and supports **delete** and **copy** with clear, responsive feedback

### 9. Customizable Settings Center & Themes

- The **settings dialog** exposes **17 parameters** (switches / sliders / tone curves / multi-frame denoising / themes) with **free drag-to-reorder** and **one-tap reset**, persisted reliably to local storage
- Ships with **8 themes** (Marshmallow, Macaron, Berry Coffee, Peach Mint, Night Sakura, Light Sakura, Graphite, Wine Berry) — switch globally with one tap

### 10. Radical Minimalism

No ads, no notifications, no accounts, no community bloat. Just the core ability to shoot: **clean, focused, and fully offline.**

> **Compatibility note:** Currently only tested on the **Samsung Galaxy S23 Ultra**; behavior on other devices may differ. Requires **minSdk 26 (Android 8.0)** or above, **arm64-v8a / x86_64** (64-bit only); GPU multi-frame denoising additionally requires **OpenGL ES 3.1 + GL_EXT_gpu_shader5**.

## 🛠 Tech Stack

| Layer | Technology |
| --- | --- |
| Languages | Kotlin · C/C++ (NDK) |
| Camera | Camera2 API, full manual control (exposure / focus / WB / multi-lens) |
| Rendering | OpenGL ES 3.x real-time RAW pipeline · 3.1 compute shaders for multi-frame fusion |
| Native | CMake + JNI, C++ for tone curves / MLS LUT generation, etc. |
| Platform | minSdk 26 · targetSdk 36, arm64-v8a / x86_64 |

## 📂 Project Layout (excerpt)

```
app/src/main/java/com/classic/camera/
├── MainActivity.kt        # Main capture UI (RAW live view / multi-lens / shutter)
├── RawPipeline.kt         # Core GPU RAW processing pipeline
├── CameraController.kt    # Camera2 control / focus / multi-frame burst
├── ManualController.kt    # Full manual + semi-auto exposure control
├── GpuAlignMerge.kt       # GPU multi-frame alignment & fusion (compute shaders)
├── FilmicHrEngine.kt      # Filmic highlight-reconstruction engine
├── LearnFilterActivity.kt # LUT learning system (MLS fitting)
├── ApplyFilterActivity.kt # Apply filters to still photos
├── VectorFieldRenderer.kt # 3D vector-field visualization
├── CurveEditorView.kt     # Custom tone-curve editor
├── LensInfo / LensStore   # Lens detection & persistence
├── SettingsAdapter / SettingsItem # 17-item drag-to-reorder settings center
├── ThemeUtils / ThemeSelectorAdapter # 8-theme switcher
├── GalleryActivity / PhotoPopupDialog # Built-in gallery & photo management
└── assets/filters/*.cube    # 56 film simulation LUTs
```

## 🚀 Build

```bash
# Requires Android SDK + NDK
./gradlew assembleDebug
```

## 📄 License

This project is for learning and technical exchange purposes only.

---

<p align="center">
  <b>English</b> &nbsp;|&nbsp; <a href="README.md">简体中文</a>
</p>
