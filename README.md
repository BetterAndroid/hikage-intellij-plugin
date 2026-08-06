# Hikage IntelliJ Plugin

Android Studio support for writing, checking, navigating, and converting Hikage layouts.

This project is under active development. Checked items are available in the current development baseline; unchecked items are planned or still in
progress.

## Project Setup

- [x] Recognize Hikage Android projects and enable plugin features only where they apply
- [x] Recommend Hikage for suitable Android apps and add the standard dependencies with one action
- [x] Provide one settings page for editor, attribute, XML layout conversion, and Android Lint options, with one-click Hikage setup when needed

## Kotlin Editing

- [x] Recognize custom Hikage Views and performers before generated source is available
- [x] Complete and highlight Hikage component calls and layout values
- [x] Fill the default `LayoutParams` argument when requested
- [x] Check common declaration and DSL mistakes and offer fixes where possible
- [x] Navigate, find usages, and rename supported Views and performer calls

## Attributes and Layouts

- [x] Complete Hikage attribute names, values, resources, and theme references
- [x] Preview, document, navigate, pick, and rename supported attribute resources
- [x] Complete and check layout ID lookups
- [x] Preview layout lookups and support navigation, Find Usages, and Rename
- [x] Run common Android layout checks directly on Hikage layout code
- [x] Detect a missing runtime-attribute dependency and offer to add it

## XML Layout Conversion

- [x] Provide conversion settings and context-aware Hikage menus under Tools, the XML editor, and Project View
- [x] Show cancellable progress in the IDE while a conversion is running
- [x] Copy a common static XML layout as a Performer snippet
- [x] Convert supported IDs, visibility, symbolic attribute options, sizes, margins, and padding, including theme-based padding values, without
  silently dropping unsupported values
- [x] Preserve the snippet as plain text while restoring only the required imports when pasted into Android Studio
- [x] Fall back to generic View or ViewGroup calls when a snippet has no matching custom performer and the result is safe
- [ ] Copy a layout as a Hikagable property
- [ ] Copy a layout as a HikageBuilder
- [ ] Generate a complete HikageBuilder Kotlin file in a selectable package
- [ ] Help complete-file conversion handle custom Views without performers
- [ ] Convert multiple layouts together, including local `include` and `merge` relationships
- [ ] Cover the remaining resource values, parent-specific layout rules, and special XML cases

## Hikage Preview

- [ ] Add a Hikage Preview tool window
- [ ] Discover previewable layouts from the current Kotlin file
- [ ] Switch between multiple layouts in one file
- [ ] Build and render layouts through Android Studio
- [ ] Refresh safe code changes quickly and clearly request a full refresh when required

## Future Ideas

- [ ] Generate type-safe layout ID accessors
- [ ] Add visual editing through Android Studio's Layout Editor
- [ ] Trace Hikage layout code from Layout Inspector
- [ ] Provide a new-project template for Hikage apps
- [ ] Add compiler-level checks for errors that can be proven before runtime