<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Plugin-test Changelog

## [Unreleased]

### Added

- Nexus Reel and Nexus Deck now show the product's own interface to a stakeholder, redrawn
  from what its source declares: the rows of its navigation in their order with their real
  badges, the labels its pipeline gives its own stages, and the colours, corner radius and
  light or dark scheme it actually ships in. It is boxes and text, not a screenshot, so the
  slide stays editable and the scene stays seekable. Nothing on it is written by anything:
  a project with no readable interface, or one whose design system could only be read in
  part, gets no such scene and no such slide rather than its words in our colours.
- The design system a project wears is now read by role (page, panel, hairline, text,
  secondary text and one accent) rather than as a bag of colours, resolving oklch, oklab,
  hsl and rgba to sRGB so that a .pptx, which has no colour space but sRGB, can hold it.
