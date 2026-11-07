# Skills audit

Audit performed in the available Termux workspace. All 18 `SKILL.md` files under `.agents/skills` were opened and read. `imagegen` at `/data/data/com.termux/files/home/.codex/skills/.system/imagegen/SKILL.md` was also read for the visual reference. No Cloudflare/Netlify integration skill was relevant: this is a local Android app, with no Netlify backend or deployment request. `plugin-management`, mobile iOS, pet, skill authoring/install, and OpenAI-product guidance are not relevant to this Android browser implementation.

| Skill | Application |
|---|---|
| `afd-app-frontend` | App states, settings and resilient controls informed the UI architecture. |
| `afd-design-direction` | Chosen product-specific, calm Japanese visual direction and design dials. |
| `afd-motion` | Motion kept restrained and reduced-motion respected. |
| `afd-static-sites` | Not applied: this is a native Android app shell, not a static website. |
| `afd-theme-systems` | CSS token themes, stored choice, system default. |
| `afd-ui-review` | Review checklist and viewport/accessibility checks applied as far as tooling allowed; browser matrix unavailable. |
| `agent-browser` | Read; `belt` browser CLI is unavailable, so not used for browser automation. |
| `anti-slop` | Product-specific design, verifiable claims, functional controls and quality review. |
| `anti-ui-slop` | Design contract, named states, purposeful screen composition. |
| `antislop-code` | Reviewed source comments and avoided generic commentary. |
| `antislop-layoutmobile` | Mobile-first sizing, narrow layout and touch targets. |
| `antislop` | Applied during implementation and delivery review. |
| `awesome-frontend-design` | Used as entry point to relevant frontend direction/design skills. |
| `design-mobile-apps` | Mobile screen hierarchy and one-hand controls. |
| `install-anti-slop` | Read; not used because no lint plugin installation was required. |
| `ios-design` | Read; not applied because the target is Android, though general mobile affordance guidance overlaps. |
| `ui-styling` | Accessible component states, CSS tokens, responsive styling. |
| `ui-taste` | Product-specific visual craft and review. |

The generated target in `docs/reference/home-target.png` is only a visual reference; it is not shipped as the UI. The implementation uses semantic HTML, CSS, and inline SVG line icons.
