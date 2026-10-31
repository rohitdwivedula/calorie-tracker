# Launcher icon

Generated with the built-in imagegen tool, then copied unchanged into the project:
`app/src/main/res/drawable-nodpi/bowl_mascot.png`.

The original transparent PNG is the adaptive icon foreground. Android supplies the
solid background and launcher mask. The separate code-native monochrome glyph is
used for Android 13+ themed icons. No image-generation CLI or API key was used.

## Final generation prompt

Use case: logo-brand. Asset: a production Android nutrition-tracker launcher icon
foreground. Create one cute, beautifully simple smiling ceramic food bowl mascot,
with a small rounded mound of rice and two fresh leaves as a garnish. Warm cream
bowl, tiny charcoal eyes and a subtle happy mouth, soft mint and leafy green
accents, one restrained golden food accent. Friendly rounded silhouette, bold
clean shapes, polished flat illustration with very subtle matte shading, readable
at 48 pixels. Single centered subject occupying only the central 60 percent of a
square canvas, generous transparent safe-area margins on every side for Android
adaptive icon masks. Truly transparent background. No text, no letters, no
surrounding icon tile, no phone/mockup, no scenery, no watermark. The mascot should
feel like a calm friendly cooking companion rather than a clinical calorie counter.

## Android-rendered preview

[Launcher preview](launcher-preview.png) was rendered from the packaged adaptive
icon by the Android resource test. Android applies the launcher's chosen mask
to the original transparent foreground.
