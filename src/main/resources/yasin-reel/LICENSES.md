# Third party notices, Nexus Reel

Everything the reel runtime ships is vendored rather than fetched, because a render has
to produce the same frames offline. That means this plugin redistributes the files
below, so their notices travel with them.

## The icon set

Sixteen line glyphs, drawn for this plugin by `tools/yasin-icons.py` and owned by it. No
third party artwork is redistributed, so there is nothing to notice here; the section this
replaces covered Microsoft's Fluent Emoji, which the runtime carried until a stakeholder
cut was watched and the artwork was judged too playful for the room the film is made for.

They exist twice, from one source. The generator writes the PNGs the deck embeds in every
`.pptx` at `../yasin-deck/icons`, and the SVG data URIs inlined in `runtime/icons.js`, from
the same path definitions in the same run, so the deck and the film cannot drift into two
similar sets. Re-run it after editing a path:

```
python3 tools/yasin-icons.py            # all sixteen, about two minutes
python3 tools/yasin-icons.py lock bulb  # just those, for iterating
```

The tinted disc each glyph sits on is drawn around it by `reel.css` or by the deck's own
geometry, and is never painted into the artwork.

## GSAP

- File: `vendor/gsap.min.js`, version 3.12.5
- Source: https://gsap.com
- Licence: GSAP standard licence, see the header of that file

Used for the single paused timeline the whole runtime is built on.
