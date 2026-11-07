# Design system

## Intent

Cloudflare Browser is a one-hand Android browser for ordinary daily browsing. Its interface uses a warm paper surface and quiet ink, with a small vermilion accent for actions and warnings. Japanese influence stays in the short `雲` mark, a single sakura detail, and one restrained Japanese footer line; it does not compete with the page or controls. The mark is a typographic character, not a claim of official Cloudflare branding.

## Tokens and type

| Token | Light | Dark | Use |
|---|---|---|---|
| `--paper` | `#F5F1E9` | `#191A1B` | App background |
| `--card` | `#FFFDF8` | `#232425` | Controls and grouped surfaces |
| `--ink` | `#292A28` | `#F1EEE7` | Primary text and primary action |
| `--muted` | `#6A6B65` | `#A8A79F` | Supporting text |
| `--line` | `#E5DFD4` | `#38393A` | Borders and separators |
| `--accent` | `#AA3F2A` | `#E38168` | Links, emphasis, warnings |
| `--olive` | `#6D7862` | `#AAB49C` | Enabled and successful states |
| `--accent-soft` | `#F7E8E1` | `#392723` | Low emphasis accent surface |

Use the Android/system sans-serif for compact, familiar controls; Georgia/serif appears only in the home headline and small Japanese detail. This keeps the APK independent of remote fonts. Icons are original, inline 24-unit line SVGs with rounded caps, no emoji icons, and 1.7-unit stroke.

## Layout and interaction

Small text colors are tuned to meet WCAG AA contrast against their corresponding main surface tokens (light muted 4.78:1 on paper and 5.29:1 on cards; light accent 5.40:1 and 5.98:1; dark muted 7.21:1 and 6.44:1; dark accent 6.31:1 and 5.63:1). `tools/check_contrast.mjs` checks these text tokens on both paper and card surfaces.

- Mobile-first single column, 22 dp side inset (16 on narrow devices), 8/12/16/24/32 spacing rhythm.
- 12–17 dp surface radii; pill shape reserved for switches and status markers.
- Surfaces use a one-pixel border and subtle shadow; no gradients or blur.
- Buttons have a minimum 40 dp hit area; form inputs have explicit labels for assistive technology.
- Motion is limited to short state transitions and progress feedback. `prefers-reduced-motion` disables transitions.
- Theme follows the system by default; explicit light and dark choices are stored locally.
- DNS enabled/success uses olive, warning/error uses vermilion, and neutral/unsupported uses muted gray. Text labels accompany color.

## Reference

`docs/reference/home-target.png` is a generated, visual-only composition reference. The shipped interface is implemented with semantic HTML/CSS controls and native WebView content; the reference image is not used as a flattened screen asset.
