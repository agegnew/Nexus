# Third party notices, Nexus Reel

Everything the reel runtime ships is vendored rather than fetched, because a render has
to produce the same frames offline. That means this plugin redistributes the files
below, so their notices travel with them.

## Microsoft Fluent Emoji

Sixteen flat SVGs, inlined as base64 data URIs in `runtime/icons.js`:

`rocket`, `search` (magnifying glass tilted left), `chart` (bar chart), `toolbox`,
`laptop`, `bulb` (light bulb), `package`, `plug` (electric plug), `files` (card index
dividers), `sparkles`, `lock` (locked), `check` (check mark button), `compass`,
`palette` (artist palette), `bolt` (high voltage), `target` (bullseye).

- Source: https://github.com/microsoft/fluentui-emoji
- Licence: MIT
- Copyright (c) Microsoft Corporation

> Permission is hereby granted, free of charge, to any person obtaining a copy of this
> software and associated documentation files (the "Software"), to deal in the Software
> without restriction, including without limitation the rights to use, copy, modify,
> merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
> permit persons to whom the Software is furnished to do so, subject to the following
> conditions:
>
> The above copyright notice and this permission notice shall be included in all copies
> or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
> INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
> PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
> HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
> CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
> OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The icons are used as-is. None has been recoloured or redrawn; the tinted disc each one
sits on is drawn around the artwork by `reel.css`, or by the deck's own geometry, and is
never painted into it.

The same sixteen also ship as PNGs at `../yasin-deck/icons`, embedded in every generated
`.pptx`. They were rasterised from the very data URIs in `runtime/icons.js` rather than
fetched a second time, so the deck and the film cannot drift into two similar sets.

## GSAP

- File: `vendor/gsap.min.js`, version 3.12.5
- Source: https://gsap.com
- Licence: GSAP standard licence, see the header of that file

Used for the single paused timeline the whole runtime is built on.
