<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Plugin-test Changelog

## [Unreleased]

### Added

- Trust light: paints the lines in the open file that no run has ever executed, with a stripe
  beside the scrollbar and a reading in the status bar. Off by default; switch it on from
  Tools | Show Code That Has Never Run, or by clicking the status bar. The verdict currently
  comes from `.nexus/trust.json`; a coverage-backed source replaces it next.
