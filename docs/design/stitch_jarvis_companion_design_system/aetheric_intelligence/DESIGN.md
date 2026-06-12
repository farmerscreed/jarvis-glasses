---
name: Aetheric Intelligence
colors:
  surface: '#101419'
  surface-dim: '#101419'
  surface-bright: '#36393f'
  surface-container-lowest: '#0a0e13'
  surface-container-low: '#181c21'
  surface-container: '#1c2025'
  surface-container-high: '#262a30'
  surface-container-highest: '#31353b'
  on-surface: '#e0e2ea'
  on-surface-variant: '#bbc9ce'
  inverse-surface: '#e0e2ea'
  inverse-on-surface: '#2d3136'
  outline: '#869397'
  outline-variant: '#3c494d'
  surface-tint: '#36d8fb'
  primary: '#bbefff'
  on-primary: '#003641'
  primary-container: '#3ddcff'
  on-primary-container: '#005e6f'
  inverse-primary: '#00687b'
  secondary: '#bfc7d7'
  on-secondary: '#29313d'
  secondary-container: '#444c59'
  on-secondary-container: '#b4bccc'
  tertiary: '#ffe2c4'
  on-tertiary: '#472a00'
  tertiary-container: '#ffbe6f'
  on-tertiary-container: '#794b00'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#aeecff'
  primary-fixed-dim: '#36d8fb'
  on-primary-fixed: '#001f26'
  on-primary-fixed-variant: '#004e5d'
  secondary-fixed: '#dbe3f3'
  secondary-fixed-dim: '#bfc7d7'
  on-secondary-fixed: '#141c28'
  on-secondary-fixed-variant: '#3f4754'
  tertiary-fixed: '#ffddb9'
  tertiary-fixed-dim: '#ffb961'
  on-tertiary-fixed: '#2b1700'
  on-tertiary-fixed-variant: '#663e00'
  background: '#101419'
  on-background: '#e0e2ea'
  surface-variant: '#31353b'
typography:
  display-lg:
    fontFamily: Space Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  display-md:
    fontFamily: Space Grotesk
    fontSize: 36px
    fontWeight: '600'
    lineHeight: 44px
  headline-lg:
    fontFamily: Space Grotesk
    fontSize: 28px
    fontWeight: '500'
    lineHeight: 36px
  headline-lg-mobile:
    fontFamily: Space Grotesk
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 32px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  data-mono:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.05em
  label-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.1em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
  gutter: 16px
  margin-mobile: 24px
  margin-desktop: 64px
---

## Brand & Style
The brand personality for this design system is characterized as an "Ethereal Sentinel"—a presence that is calm, confident, and technologically advanced yet inherently warm and approachable. Designed specifically for smart glasses, the UI must feel "alive," responding to the user's environment without obstructing it.

The design style merges **Modern Corporate** (Material 3 foundations) with **Glassmorphism** and **Futuristic** elements. It utilizes a deep, dark theme to minimize light leakage in optical displays, creating a "heads-up" experience where information floats naturally. The aesthetic is restrained and premium, favoring quality of motion and typographic clarity over decorative clutter.

## Colors
The palette is optimized for OLED and waveguide displays, prioritizing high-contrast legibility against a "Base Ink" background to ensure the hardware's black levels remain deep.

- **Electric Cyan (#3DDCFF):** The pulse of the system. Used for active listening states, primary action buttons, and critical focus indicators. It should appear to "glow" via subtle outer blurs.
- **Base Ink (#0B0F14):** The foundational canvas. Provides maximum contrast for text and minimizes peripheral eye strain.
- **Surface Navy (#1A222E):** Used for elevated containers and sheets. This color is slightly lifted from the background to define hierarchy.
- **Semantic Amber (#FFB454):** Reserved for secondary statuses such as "offline mode," "local storage," or "sync pending." It offers a warm contrast to the cool cyan.

## Typography
Typography is the primary vehicle for information. We use a tri-font strategy:
- **Space Grotesk** for headlines and display text, providing a technical, geometric edge that feels futuristic.
- **Inter** for all body copy and descriptions, chosen for its exceptional readability at various distances.
- **JetBrains Mono** for transcripts, timestamps, and data-heavy strings, evoking a "terminal" or "log" aesthetic suitable for an AI companion.

All caps should be used sparingly for labels and secondary data to enhance the "systemic" feel.

## Layout & Spacing
The layout philosophy is **Contextual & Fluid**. Since this is a voice-first interface, content should only appear when summoned, occupying the center of the field of view or anchoring to the corners.

We use a **soft grid** based on 4px increments. For smart glasses, we prioritize "Safe Areas"—avoiding the extreme edges of the lens where chromatic aberration occurs.
- **Margins:** 24px minimum on all sides.
- **Content Width:** Max-width of 600px for text readability to prevent excessive eye scanning.
- **Vertical Rhythm:** Generous whitespace between blocks (24px - 40px) to ensure the UI feels "airy" and doesn't clutter the user's vision.

## Elevation & Depth
Depth is created through **Tonal Layering** and **Glassmorphism** rather than traditional heavy shadows.
1. **Level 0 (Base):** #0B0F14 (Pure background).
2. **Level 1 (Cards/Sheets):** #1A222E with a 20% opacity white border.
3. **Level 2 (Overlays):** Semi-transparent Surface Navy with a `backdrop-filter: blur(12px)`.

Shadows are used minimally and are "Ambient": very low opacity (#000000 at 30%), highly diffused (20px-40px blur), intended to make cards feel like they are physically floating in the air.

## Shapes
Following the Material 3 "Extra Large" standard, we use generous corner radii to make the interface feel organic and safe. 
- **Small components (Chips/Inputs):** 8px (rounded-md).
- **Medium components (Cards/Modals):** 24px (rounded-xl).
- **Large surfaces (Full-screen sheets):** 32px (rounded-2xl).

Active voice indicators and "listening" states should transition into perfect circles (Pill-shaped) to represent the fluid nature of conversation.

## Components
- **Primary Buttons:** High-contrast Electric Cyan background with black text. Use a subtle outer glow (Cyan shadow) when focused.
- **Transcription Chips:** Use the Data/Mono font style. Background should be transparent with a 1px Electric Cyan border.
- **Interactive Cards:** Surface Navy background. Use "Extra Large" rounding. In active states, the border should pulse with a Cyan gradient.
- **Input Fields:** Minimalist. No solid background; only a bottom border that illuminates when the voice-to-text engine is active.
- **Voice Orb:** A custom component representing the AI. A fluid, glowing sphere of Electric Cyan that morphs based on frequency (speaking) or steady pulse (thinking).
- **Status Indicators:** Small, circular dots. Semantic Amber for local-only/offline status, Cyan for cloud-synced/live status.