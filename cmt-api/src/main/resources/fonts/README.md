# Fonts for generated files

Used to draw the printed gate QR sheet (step 6.2). Copied from the staff app (`Mobile-App/assets/fonts`).

| File | Font | License |
|---|---|---|
| `Siemreap-400.ttf` | Siemreap (Khmer) | SIL Open Font License 1.1 (Google Fonts) |
| `PlusJakartaSans-*.ttf` | Plus Jakarta Sans | SIL Open Font License 1.1 (Google Fonts) |

Khmer needs text shaping; Java2D does it (HarfBuzz), so text is drawn with Java2D into an image, never as raw PDF
text.
