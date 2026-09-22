/*
 * The icon set, inlined.
 *
 * Sixteen line glyphs drawn by tools/yasin-icons.py, carried as base64 data URIs rather
 * than as files on disk. They were Microsoft's Fluent Emoji until somebody watched the
 * stakeholder cut and said it looked childish, which was fair: emoji are full colour and
 * softly shaded, and they read as decoration rather than as information in a film shown
 * to the person deciding whether to fund the work. One weight, one colour, one grid. That is the whole reason
 * this file exists in this shape: the standalone .html export inlines every script and
 * the headless MP4 render is forbidden from touching the network, so an icon referenced
 * by URL would be a hole in both. A data URI travels inside the script that uses it.
 *
 * They are painted as background-image on a span, never injected as markup, so the
 * gradient and mask ids inside sixteen separate SVG documents can never collide.
 *
 * Which icon a scene gets is decided by `pick`, from the words the director and the
 * harvester actually wrote. Nothing here is chosen by scene index unless the text
 * matched nothing at all, and then the fallback is a rotation rather than a constant,
 * so two scenes in a row never wear the same face.
 */
window.ReelIcons = (function () {
  'use strict';

  var ICONS = {
    'rocket': { tint: '#F92F60', words: ["launch", "ship", "shipped", "deploy", "release", "start", "begin", "startup", "go live", "publish", "fast", "takeoff", "accelerat"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTIxLjAwIDMuMDAgTDMuMDAgMTAuNTAiLz48cGF0aCBkPSJNMy4wMCAxMC41MCBMMTAuNTAgMTMuNTAiLz48cGF0aCBkPSJNMTAuNTAgMTMuNTAgTDIxLjAwIDMuMDAiLz48cGF0aCBkPSJNMjEuMDAgMy4wMCBMMTMuNTAgMjEuMDAiLz48cGF0aCBkPSJNMTMuNTAgMjEuMDAgTDEwLjUwIDEzLjUwIi8+PHBhdGggZD0iTTEwLjUwIDEzLjUwIEwyMS4wMCAzLjAwIi8+PC9zdmc+' },
    'search': { tint: '#00A6ED', words: ["search", "find", "analys", "analyz", "scan", "discover", "detect", "inspect", "read", "understand", "explore", "look", "index", "parse", "trace", "insight"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PGNpcmNsZSBjeD0iMTAuNTAiIGN5PSIxMC41MCIgcj0iNi41MCIvPjxwYXRoIGQ9Ik0xNS4yMCAxNS4yMCBMMjEuMDAgMjEuMDAiLz48L3N2Zz4=' },
    'chart': { tint: '#00A6ED', words: ["metric", "stat", "number", "count", "data", "measure", "scale", "growth", "report", "volume", "size", "total", "dashboard", "analytic", "benchmark"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTMuNTAgMjAuNTAgTDIwLjUwIDIwLjUwIi8+PHBhdGggZD0iTTcuMDAgMTguMjAgTDcuMDAgMTIuNTAiLz48cGF0aCBkPSJNMTIuMDAgMTguMjAgTDEyLjAwIDYuNTAiLz48cGF0aCBkPSJNMTcuMDAgMTguMjAgTDE3LjAwIDkuNTAiLz48L3N2Zz4=' },
    'toolbox': { tint: '#F8312F', words: ["build", "engine", "tool", "system", "process", "pipeline", "compile", "gradle", "maven", "assembl", "machinery", "worker", "job", "task", "run"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTE4LjQ0IDExLjk3IEwxNy41OSAxMi40NCIvPjxwYXRoIGQ9Ik0xNy41OSAxMi40NCBMMTYuNjcgMTIuNzIiLz48cGF0aCBkPSJNMTYuNjcgMTIuNzIgTDE1LjcxIDEyLjgwIi8+PHBhdGggZD0iTTE1LjcxIDEyLjgwIEwxNC43NSAxMi42OCIvPjxwYXRoIGQ9Ik0xNC43NSAxMi42OCBMMTMuODQgMTIuMzYiLz48cGF0aCBkPSJNMTMuODQgMTIuMzYgTDEzLjAxIDExLjg2Ii8+PHBhdGggZD0iTTEzLjAxIDExLjg2IEwxMi4zMSAxMS4yMCIvPjxwYXRoIGQ9Ik0xMi4zMSAxMS4yMCBMMTEuNzYgMTAuNDAiLz48cGF0aCBkPSJNMTEuNzYgMTAuNDAgTDExLjM5IDkuNTEiLz48cGF0aCBkPSJNMTEuMzkgOS41MSBMMTEuMjEgOC41NiIvPjxwYXRoIGQ9Ik0xMS4yMSA4LjU2IEwxMS4yNCA3LjYwIi8+PHBhdGggZD0iTTExLjI0IDcuNjAgTDExLjQ3IDYuNjYiLz48cGF0aCBkPSJNMTEuNDcgNi42NiBMMTEuODggNS43OSIvPjxwYXRoIGQ9Ik0xMS44OCA1Ljc5IEwxMi40NyA1LjAzIi8+PHBhdGggZD0iTTEyLjQ3IDUuMDMgTDEzLjIxIDQuNDAiLz48cGF0aCBkPSJNMTMuMjEgNC40MCBMMTQuMDYgMy45NCIvPjxwYXRoIGQ9Ik0xNC4wNiAzLjk0IEwxNC45OCAzLjY3Ii8+PHBhdGggZD0iTTE0Ljk4IDMuNjcgTDE1Ljk1IDMuNjAiLz48cGF0aCBkPSJNMTUuOTUgMy42MCBMMTYuOTAgMy43MyIvPjxwYXRoIGQ9Ik0xNi45MCAzLjczIEwxNy44MSA0LjA2Ii8+PHBhdGggZD0iTTE3LjgxIDQuMDYgTDE4LjYzIDQuNTciLz48cGF0aCBkPSJNMTguNjMgNC41NyBMMTkuMzIgNS4yNCIvPjxwYXRoIGQ9Ik0xMi42MCAxMS41MCBMNC44MCAxOS4zMCIvPjxwYXRoIGQ9Ik00LjgwIDE5LjMwIEwzLjIwIDE3LjcwIi8+PHBhdGggZD0iTTMuMjAgMTcuNzAgTDExLjAwIDkuOTAiLz48L3N2Zz4=' },
    'laptop': { tint: '#26C9FC', words: ["app", "screen", "ui", "interface", "editor", "ide", "view", "front", "desktop", "window", "panel", "browser", "display", "client", "web"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTUuNDAgNS4wMCBMMTguNjAgNS4wMCIvPjxwYXRoIGQ9Ik0yMC4wMCA2LjQwIEwyMC4wMCAxNC4xMCIvPjxwYXRoIGQ9Ik0xOC42MCAxNS41MCBMNS40MCAxNS41MCIvPjxwYXRoIGQ9Ik00LjAwIDE0LjEwIEw0LjAwIDYuNDAiLz48cGF0aCBkPSJNMTguNjAgNS4wMCBMMTguOTEgNS4wNCIvPjxwYXRoIGQ9Ik0xOC45MSA1LjA0IEwxOS4yMSA1LjE0Ii8+PHBhdGggZD0iTTE5LjIxIDUuMTQgTDE5LjQ3IDUuMzEiLz48cGF0aCBkPSJNMTkuNDcgNS4zMSBMMTkuNjkgNS41MyIvPjxwYXRoIGQ9Ik0xOS42OSA1LjUzIEwxOS44NiA1Ljc5Ii8+PHBhdGggZD0iTTE5Ljg2IDUuNzkgTDE5Ljk2IDYuMDkiLz48cGF0aCBkPSJNMTkuOTYgNi4wOSBMMjAuMDAgNi40MCIvPjxwYXRoIGQ9Ik0yMC4wMCAxNC4xMCBMMTkuOTYgMTQuNDEiLz48cGF0aCBkPSJNMTkuOTYgMTQuNDEgTDE5Ljg2IDE0LjcxIi8+PHBhdGggZD0iTTE5Ljg2IDE0LjcxIEwxOS42OSAxNC45NyIvPjxwYXRoIGQ9Ik0xOS42OSAxNC45NyBMMTkuNDcgMTUuMTkiLz48cGF0aCBkPSJNMTkuNDcgMTUuMTkgTDE5LjIxIDE1LjM2Ii8+PHBhdGggZD0iTTE5LjIxIDE1LjM2IEwxOC45MSAxNS40NiIvPjxwYXRoIGQ9Ik0xOC45MSAxNS40NiBMMTguNjAgMTUuNTAiLz48cGF0aCBkPSJNNS40MCAxNS41MCBMNS4wOSAxNS40NiIvPjxwYXRoIGQ9Ik01LjA5IDE1LjQ2IEw0Ljc5IDE1LjM2Ii8+PHBhdGggZD0iTTQuNzkgMTUuMzYgTDQuNTMgMTUuMTkiLz48cGF0aCBkPSJNNC41MyAxNS4xOSBMNC4zMSAxNC45NyIvPjxwYXRoIGQ9Ik00LjMxIDE0Ljk3IEw0LjE0IDE0LjcxIi8+PHBhdGggZD0iTTQuMTQgMTQuNzEgTDQuMDQgMTQuNDEiLz48cGF0aCBkPSJNNC4wNCAxNC40MSBMNC4wMCAxNC4xMCIvPjxwYXRoIGQ9Ik00LjAwIDYuNDAgTDQuMDQgNi4wOSIvPjxwYXRoIGQ9Ik00LjA0IDYuMDkgTDQuMTQgNS43OSIvPjxwYXRoIGQ9Ik00LjE0IDUuNzkgTDQuMzEgNS41MyIvPjxwYXRoIGQ9Ik00LjMxIDUuNTMgTDQuNTMgNS4zMSIvPjxwYXRoIGQ9Ik00LjUzIDUuMzEgTDQuNzkgNS4xNCIvPjxwYXRoIGQ9Ik00Ljc5IDUuMTQgTDUuMDkgNS4wNCIvPjxwYXRoIGQ9Ik01LjA5IDUuMDQgTDUuNDAgNS4wMCIvPjxwYXRoIGQ9Ik0yLjUwIDE5LjUwIEwyMS41MCAxOS41MCIvPjwvc3ZnPg==' },
    'bulb': { tint: '#FFB02E', words: ["idea", "insight", "meaning", "explain", "learn", "clarity", "why", "understand", "knowledge", "concept", "think", "reason", "story", "narrat"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTcuNDEgMTIuODEgTDYuODMgMTEuNzQiLz48cGF0aCBkPSJNNi44MyAxMS43NCBMNi40OSAxMC41NyIvPjxwYXRoIGQ9Ik02LjQ5IDEwLjU3IEw2LjQxIDkuMzYiLz48cGF0aCBkPSJNNi40MSA5LjM2IEw2LjU5IDguMTUiLz48cGF0aCBkPSJNNi41OSA4LjE1IEw3LjAzIDcuMDEiLz48cGF0aCBkPSJNNy4wMyA3LjAxIEw3LjcxIDYuMDAiLz48cGF0aCBkPSJNNy43MSA2LjAwIEw4LjU5IDUuMTYiLz48cGF0aCBkPSJNOC41OSA1LjE2IEw5LjYzIDQuNTIiLz48cGF0aCBkPSJNOS42MyA0LjUyIEwxMC43OSA0LjEzIi8+PHBhdGggZD0iTTEwLjc5IDQuMTMgTDEyLjAwIDQuMDAiLz48cGF0aCBkPSJNMTIuMDAgNC4wMCBMMTMuMjEgNC4xMyIvPjxwYXRoIGQ9Ik0xMy4yMSA0LjEzIEwxNC4zNyA0LjUyIi8+PHBhdGggZD0iTTE0LjM3IDQuNTIgTDE1LjQxIDUuMTYiLz48cGF0aCBkPSJNMTUuNDEgNS4xNiBMMTYuMjkgNi4wMCIvPjxwYXRoIGQ9Ik0xNi4yOSA2LjAwIEwxNi45NyA3LjAxIi8+PHBhdGggZD0iTTE2Ljk3IDcuMDEgTDE3LjQxIDguMTUiLz48cGF0aCBkPSJNMTcuNDEgOC4xNSBMMTcuNTkgOS4zNiIvPjxwYXRoIGQ9Ik0xNy41OSA5LjM2IEwxNy41MSAxMC41NyIvPjxwYXRoIGQ9Ik0xNy41MSAxMC41NyBMMTcuMTcgMTEuNzQiLz48cGF0aCBkPSJNMTcuMTcgMTEuNzQgTDE2LjU5IDEyLjgxIi8+PHBhdGggZD0iTTguODAgMTQuMjAgTDguODAgMTcuMjAiLz48cGF0aCBkPSJNMTUuMjAgMTQuMjAgTDE1LjIwIDE3LjIwIi8+PHBhdGggZD0iTTguODAgMTcuMjAgTDE1LjIwIDE3LjIwIi8+PHBhdGggZD0iTTEwLjEwIDIwLjMwIEwxMy45MCAyMC4zMCIvPjwvc3ZnPg==' },
    'package': { tint: '#FFCE7C', words: ["depend", "module", "librar", "bundle", "plugin", "package", "import", "artifact", "jar", "npm", "vendor", "component", "extension"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTEyLjAwIDIuNTAgTDIwLjUwIDcuMDAiLz48cGF0aCBkPSJNMjAuNTAgNy4wMCBMMjAuNTAgMTcuMDAiLz48cGF0aCBkPSJNMjAuNTAgMTcuMDAgTDEyLjAwIDIxLjUwIi8+PHBhdGggZD0iTTEyLjAwIDIxLjUwIEwzLjUwIDE3LjAwIi8+PHBhdGggZD0iTTMuNTAgMTcuMDAgTDMuNTAgNy4wMCIvPjxwYXRoIGQ9Ik0zLjUwIDcuMDAgTDEyLjAwIDIuNTAiLz48cGF0aCBkPSJNMy41MCA3LjAwIEwxMi4wMCAxMS41MCIvPjxwYXRoIGQ9Ik0xMi4wMCAxMS41MCBMMjAuNTAgNy4wMCIvPjxwYXRoIGQ9Ik0xMi4wMCAxMS41MCBMMTIuMDAgMjEuNTAiLz48L3N2Zz4=' },
    'plug': { tint: '#F3AD61', words: ["connect", "integrat", "api", "bridge", "wire", "hook", "link", "endpoint", "request", "call", "route", "channel", "protocol", "between", "cross"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTkuMDAgMy4wMCBMOS4wMCA4LjAwIi8+PHBhdGggZD0iTTE1LjAwIDMuMDAgTDE1LjAwIDguMDAiLz48cGF0aCBkPSJNNy4yMCA4LjAwIEwxNi44MCA4LjAwIi8+PHBhdGggZD0iTTE4LjAwIDkuMjAgTDE4LjAwIDExLjgwIi8+PHBhdGggZD0iTTE2LjgwIDEzLjAwIEw3LjIwIDEzLjAwIi8+PHBhdGggZD0iTTYuMDAgMTEuODAgTDYuMDAgOS4yMCIvPjxwYXRoIGQ9Ik0xNi44MCA4LjAwIEwxNy4wNyA4LjAzIi8+PHBhdGggZD0iTTE3LjA3IDguMDMgTDE3LjMyIDguMTIiLz48cGF0aCBkPSJNMTcuMzIgOC4xMiBMMTcuNTUgOC4yNiIvPjxwYXRoIGQ9Ik0xNy41NSA4LjI2IEwxNy43NCA4LjQ1Ii8+PHBhdGggZD0iTTE3Ljc0IDguNDUgTDE3Ljg4IDguNjgiLz48cGF0aCBkPSJNMTcuODggOC42OCBMMTcuOTcgOC45MyIvPjxwYXRoIGQ9Ik0xNy45NyA4LjkzIEwxOC4wMCA5LjIwIi8+PHBhdGggZD0iTTE4LjAwIDExLjgwIEwxNy45NyAxMi4wNyIvPjxwYXRoIGQ9Ik0xNy45NyAxMi4wNyBMMTcuODggMTIuMzIiLz48cGF0aCBkPSJNMTcuODggMTIuMzIgTDE3Ljc0IDEyLjU1Ii8+PHBhdGggZD0iTTE3Ljc0IDEyLjU1IEwxNy41NSAxMi43NCIvPjxwYXRoIGQ9Ik0xNy41NSAxMi43NCBMMTcuMzIgMTIuODgiLz48cGF0aCBkPSJNMTcuMzIgMTIuODggTDE3LjA3IDEyLjk3Ii8+PHBhdGggZD0iTTE3LjA3IDEyLjk3IEwxNi44MCAxMy4wMCIvPjxwYXRoIGQ9Ik03LjIwIDEzLjAwIEw2LjkzIDEyLjk3Ii8+PHBhdGggZD0iTTYuOTMgMTIuOTcgTDYuNjggMTIuODgiLz48cGF0aCBkPSJNNi42OCAxMi44OCBMNi40NSAxMi43NCIvPjxwYXRoIGQ9Ik02LjQ1IDEyLjc0IEw2LjI2IDEyLjU1Ii8+PHBhdGggZD0iTTYuMjYgMTIuNTUgTDYuMTIgMTIuMzIiLz48cGF0aCBkPSJNNi4xMiAxMi4zMiBMNi4wMyAxMi4wNyIvPjxwYXRoIGQ9Ik02LjAzIDEyLjA3IEw2LjAwIDExLjgwIi8+PHBhdGggZD0iTTYuMDAgOS4yMCBMNi4wMyA4LjkzIi8+PHBhdGggZD0iTTYuMDMgOC45MyBMNi4xMiA4LjY4Ii8+PHBhdGggZD0iTTYuMTIgOC42OCBMNi4yNiA4LjQ1Ii8+PHBhdGggZD0iTTYuMjYgOC40NSBMNi40NSA4LjI2Ii8+PHBhdGggZD0iTTYuNDUgOC4yNiBMNi42OCA4LjEyIi8+PHBhdGggZD0iTTYuNjggOC4xMiBMNi45MyA4LjAzIi8+PHBhdGggZD0iTTYuOTMgOC4wMyBMNy4yMCA4LjAwIi8+PHBhdGggZD0iTTE4LjAwIDEzLjAwIEwxNy44NyAxNC4yNSIvPjxwYXRoIGQ9Ik0xNy44NyAxNC4yNSBMMTcuNDggMTUuNDQiLz48cGF0aCBkPSJNMTcuNDggMTUuNDQgTDE2Ljg1IDE2LjUzIi8+PHBhdGggZD0iTTE2Ljg1IDE2LjUzIEwxNi4wMSAxNy40NiIvPjxwYXRoIGQ9Ik0xNi4wMSAxNy40NiBMMTUuMDAgMTguMjAiLz48cGF0aCBkPSJNMTUuMDAgMTguMjAgTDEzLjg1IDE4LjcxIi8+PHBhdGggZD0iTTEzLjg1IDE4LjcxIEwxMi42MyAxOC45NyIvPjxwYXRoIGQ9Ik0xMi42MyAxOC45NyBMMTEuMzcgMTguOTciLz48cGF0aCBkPSJNMTEuMzcgMTguOTcgTDEwLjE1IDE4LjcxIi8+PHBhdGggZD0iTTEwLjE1IDE4LjcxIEw5LjAwIDE4LjIwIi8+PHBhdGggZD0iTTkuMDAgMTguMjAgTDcuOTkgMTcuNDYiLz48cGF0aCBkPSJNNy45OSAxNy40NiBMNy4xNSAxNi41MyIvPjxwYXRoIGQ9Ik03LjE1IDE2LjUzIEw2LjUyIDE1LjQ0Ii8+PHBhdGggZD0iTTYuNTIgMTUuNDQgTDYuMTMgMTQuMjUiLz48cGF0aCBkPSJNNi4xMyAxNC4yNSBMNi4wMCAxMy4wMCIvPjxwYXRoIGQ9Ik0xMi4wMCAxOS4wMCBMMTIuMDAgMjEuNTAiLz48L3N2Zz4=' },
    'files': { tint: '#00A6ED', words: ["file", "folder", "code", "source", "repositor", "document", "structure", "director", "path", "tree", "project", "codebase", "script", "module tree"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTcuNTAgMy41MCBMMTQuNTAgMy41MCIvPjxwYXRoIGQ9Ik0xNC41MCAzLjUwIEwxOS4wMCA4LjAwIi8+PHBhdGggZD0iTTE5LjAwIDguMDAgTDE5LjAwIDIwLjUwIi8+PHBhdGggZD0iTTE5LjAwIDIwLjUwIEw3LjUwIDIwLjUwIi8+PHBhdGggZD0iTTcuNTAgMjAuNTAgTDcuNTAgMy41MCIvPjxwYXRoIGQ9Ik0xNC41MCAzLjUwIEwxNC41MCA4LjAwIi8+PHBhdGggZD0iTTE0LjUwIDguMDAgTDE5LjAwIDguMDAiLz48cGF0aCBkPSJNMTAuNTAgMTIuNTAgTDE2LjAwIDEyLjUwIi8+PHBhdGggZD0iTTEwLjUwIDE2LjAwIEwxNi4wMCAxNi4wMCIvPjwvc3ZnPg==' },
    'sparkles': { tint: '#F9C23C', words: ["ai", "model", "generat", "smart", "language", "magic", "llm", "intelligen", "auto", "suggest", "predict", "gpt", "narration", "voice", "speech"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTEyLjAwIDIuNTAgTDE0LjIwIDkuODAiLz48cGF0aCBkPSJNMTQuMjAgOS44MCBMMjEuNTAgMTIuMDAiLz48cGF0aCBkPSJNMjEuNTAgMTIuMDAgTDE0LjIwIDE0LjIwIi8+PHBhdGggZD0iTTE0LjIwIDE0LjIwIEwxMi4wMCAyMS41MCIvPjxwYXRoIGQ9Ik0xMi4wMCAyMS41MCBMOS44MCAxNC4yMCIvPjxwYXRoIGQ9Ik05LjgwIDE0LjIwIEwyLjUwIDEyLjAwIi8+PHBhdGggZD0iTTIuNTAgMTIuMDAgTDkuODAgOS44MCIvPjxwYXRoIGQ9Ik05LjgwIDkuODAgTDEyLjAwIDIuNTAiLz48L3N2Zz4=' },
    'lock': { tint: '#F9C23C', words: ["secur", "safe", "privat", "auth", "secret", "key", "token", "credential", "protect", "permission", "scrub", "redact", "encrypt", "trust"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTcuMDAgMTAuODAgTDE3LjAwIDEwLjgwIi8+PHBhdGggZD0iTTE4LjgwIDEyLjYwIEwxOC44MCAxOC44MCIvPjxwYXRoIGQ9Ik0xNy4wMCAyMC42MCBMNy4wMCAyMC42MCIvPjxwYXRoIGQ9Ik01LjIwIDE4LjgwIEw1LjIwIDEyLjYwIi8+PHBhdGggZD0iTTE3LjAwIDEwLjgwIEwxNy40MCAxMC44NSIvPjxwYXRoIGQ9Ik0xNy40MCAxMC44NSBMMTcuNzggMTAuOTgiLz48cGF0aCBkPSJNMTcuNzggMTAuOTggTDE4LjEyIDExLjE5Ii8+PHBhdGggZD0iTTE4LjEyIDExLjE5IEwxOC40MSAxMS40OCIvPjxwYXRoIGQ9Ik0xOC40MSAxMS40OCBMMTguNjIgMTEuODIiLz48cGF0aCBkPSJNMTguNjIgMTEuODIgTDE4Ljc1IDEyLjIwIi8+PHBhdGggZD0iTTE4Ljc1IDEyLjIwIEwxOC44MCAxMi42MCIvPjxwYXRoIGQ9Ik0xOC44MCAxOC44MCBMMTguNzUgMTkuMjAiLz48cGF0aCBkPSJNMTguNzUgMTkuMjAgTDE4LjYyIDE5LjU4Ii8+PHBhdGggZD0iTTE4LjYyIDE5LjU4IEwxOC40MSAxOS45MiIvPjxwYXRoIGQ9Ik0xOC40MSAxOS45MiBMMTguMTIgMjAuMjEiLz48cGF0aCBkPSJNMTguMTIgMjAuMjEgTDE3Ljc4IDIwLjQyIi8+PHBhdGggZD0iTTE3Ljc4IDIwLjQyIEwxNy40MCAyMC41NSIvPjxwYXRoIGQ9Ik0xNy40MCAyMC41NSBMMTcuMDAgMjAuNjAiLz48cGF0aCBkPSJNNy4wMCAyMC42MCBMNi42MCAyMC41NSIvPjxwYXRoIGQ9Ik02LjYwIDIwLjU1IEw2LjIyIDIwLjQyIi8+PHBhdGggZD0iTTYuMjIgMjAuNDIgTDUuODggMjAuMjEiLz48cGF0aCBkPSJNNS44OCAyMC4yMSBMNS41OSAxOS45MiIvPjxwYXRoIGQ9Ik01LjU5IDE5LjkyIEw1LjM4IDE5LjU4Ii8+PHBhdGggZD0iTTUuMzggMTkuNTggTDUuMjUgMTkuMjAiLz48cGF0aCBkPSJNNS4yNSAxOS4yMCBMNS4yMCAxOC44MCIvPjxwYXRoIGQ9Ik01LjIwIDEyLjYwIEw1LjI1IDEyLjIwIi8+PHBhdGggZD0iTTUuMjUgMTIuMjAgTDUuMzggMTEuODIiLz48cGF0aCBkPSJNNS4zOCAxMS44MiBMNS41OSAxMS40OCIvPjxwYXRoIGQ9Ik01LjU5IDExLjQ4IEw1Ljg4IDExLjE5Ii8+PHBhdGggZD0iTTUuODggMTEuMTkgTDYuMjIgMTAuOTgiLz48cGF0aCBkPSJNNi4yMiAxMC45OCBMNi42MCAxMC44NSIvPjxwYXRoIGQ9Ik02LjYwIDEwLjg1IEw3LjAwIDEwLjgwIi8+PHBhdGggZD0iTTguMjAgMTAuODAgTDguMjAgOC4wMCIvPjxwYXRoIGQ9Ik0xNS44MCAxMC44MCBMMTUuODAgOC4wMCIvPjxwYXRoIGQ9Ik04LjIwIDguMDAgTDguMjggNy4yMSIvPjxwYXRoIGQ9Ik04LjI4IDcuMjEgTDguNTMgNi40NSIvPjxwYXRoIGQ9Ik04LjUzIDYuNDUgTDguOTMgNS43NyIvPjxwYXRoIGQ9Ik04LjkzIDUuNzcgTDkuNDYgNS4xOCIvPjxwYXRoIGQ9Ik05LjQ2IDUuMTggTDEwLjEwIDQuNzEiLz48cGF0aCBkPSJNMTAuMTAgNC43MSBMMTAuODMgNC4zOSIvPjxwYXRoIGQ9Ik0xMC44MyA0LjM5IEwxMS42MCA0LjIyIi8+PHBhdGggZD0iTTExLjYwIDQuMjIgTDEyLjQwIDQuMjIiLz48cGF0aCBkPSJNMTIuNDAgNC4yMiBMMTMuMTcgNC4zOSIvPjxwYXRoIGQ9Ik0xMy4xNyA0LjM5IEwxMy45MCA0LjcxIi8+PHBhdGggZD0iTTEzLjkwIDQuNzEgTDE0LjU0IDUuMTgiLz48cGF0aCBkPSJNMTQuNTQgNS4xOCBMMTUuMDcgNS43NyIvPjxwYXRoIGQ9Ik0xNS4wNyA1Ljc3IEwxNS40NyA2LjQ1Ii8+PHBhdGggZD0iTTE1LjQ3IDYuNDUgTDE1LjcyIDcuMjEiLz48cGF0aCBkPSJNMTUuNzIgNy4yMSBMMTUuODAgOC4wMCIvPjwvc3ZnPg==' },
    'check': { tint: '#00D26A', words: ["done", "verif", "test", "quality", "valid", "pass", "correct", "confirm", "check", "proof", "accurate", "review", "guarantee", "ensure", "honest"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PGNpcmNsZSBjeD0iMTIuMDAiIGN5PSIxMi4wMCIgcj0iOS4wMCIvPjxwYXRoIGQ9Ik04LjAwIDEyLjIwIEwxMS4wMCAxNS4yMCIvPjxwYXRoIGQ9Ik0xMS4wMCAxNS4yMCBMMTYuMDAgOS4yMCIvPjwvc3ZnPg==' },
    'compass': { tint: '#FFB02E', words: ["map", "navigat", "guide", "tour", "orient", "overview", "direction", "onboard", "where", "journey", "path", "architect", "layout", "blueprint"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PGNpcmNsZSBjeD0iMTIuMDAiIGN5PSIxMi4wMCIgcj0iOS4wMCIvPjxwYXRoIGQ9Ik0xNS41MCA4LjUwIEwxMy41MCAxMy41MCIvPjxwYXRoIGQ9Ik0xMy41MCAxMy41MCBMOC41MCAxNS41MCIvPjxwYXRoIGQ9Ik04LjUwIDE1LjUwIEwxMC41MCAxMC41MCIvPjxwYXRoIGQ9Ik0xMC41MCAxMC41MCBMMTUuNTAgOC41MCIvPjwvc3ZnPg==' },
    'palette': { tint: '#F70A8D', words: ["design", "visual", "colour", "color", "theme", "style", "render", "draw", "paint", "brand", "look", "animat", "film", "video", "frame", "scene"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTEyLjAwIDMuMDAgTDIxLjAwIDguMDAiLz48cGF0aCBkPSJNMjEuMDAgOC4wMCBMMTIuMDAgMTMuMDAiLz48cGF0aCBkPSJNMTIuMDAgMTMuMDAgTDMuMDAgOC4wMCIvPjxwYXRoIGQ9Ik0zLjAwIDguMDAgTDEyLjAwIDMuMDAiLz48cGF0aCBkPSJNMy4wMCAxMi4wMCBMMTIuMDAgMTcuMDAiLz48cGF0aCBkPSJNMTIuMDAgMTcuMDAgTDIxLjAwIDEyLjAwIi8+PHBhdGggZD0iTTMuMDAgMTYuMDAgTDEyLjAwIDIxLjAwIi8+PHBhdGggZD0iTTEyLjAwIDIxLjAwIEwyMS4wMCAxNi4wMCIvPjwvc3ZnPg==' },
    'bolt': { tint: '#FF822D', words: ["fast", "speed", "perform", "instant", "live", "real time", "real-time", "power", "quick", "latency", "second", "efficien", "respons", "cache"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PHBhdGggZD0iTTEzLjUwIDIuNTAgTDUuMDAgMTMuNTAiLz48cGF0aCBkPSJNNS4wMCAxMy41MCBMMTEuNTAgMTMuNTAiLz48cGF0aCBkPSJNMTEuNTAgMTMuNTAgTDEwLjUwIDIxLjUwIi8+PHBhdGggZD0iTTEwLjUwIDIxLjUwIEwxOS4wMCAxMC41MCIvPjxwYXRoIGQ9Ik0xOS4wMCAxMC41MCBMMTIuNTAgMTAuNTAiLz48cGF0aCBkPSJNMTIuNTAgMTAuNTAgTDEzLjUwIDIuNTAiLz48L3N2Zz4=' },
    'target': { tint: '#F8312F', words: ["goal", "focus", "precis", "accurate", "aim", "outcome", "result", "objective", "impact", "value", "benefit", "solve", "problem", "need", "audience"],
      uri: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMTAxODI4IiBzdHJva2Utd2lkdGg9IjEuOTAiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCI+PGNpcmNsZSBjeD0iMTIuMDAiIGN5PSIxMi4wMCIgcj0iOS4wMCIvPjxjaXJjbGUgY3g9IjEyLjAwIiBjeT0iMTIuMDAiIHI9IjQuNzAiLz48Y2lyY2xlIGN4PSIxMi4wMCIgY3k9IjEyLjAwIiByPSIxLjUwIiBmaWxsPSIjMTAxODI4IiBzdHJva2U9Im5vbmUiLz48L3N2Zz4=' },
  };

  var ORDER = ["rocket", "search", "chart", "toolbox", "laptop", "bulb", "package", "plug", "files", "sparkles", "lock", "check", "compass", "palette", "bolt", "target"];

  function get(name) {
    return ICONS[name] || null;
  }

  /*
   * Scores every icon against a blob of text and returns the best name.
   *
   * A longer keyword beats a shorter one so that "endpoint" is a connection rather
   * than a point, and a word found in the first field beats the same word found in a
   * body paragraph, because a card's title is what the icon is standing next to.
   */
  function score(name, primary, secondary) {
    var words = ICONS[name].words;
    var total = 0;
    for (var i = 0; i < words.length; i++) {
      var w = words[i];
      var weight = w.length;
      if (primary.indexOf(w) >= 0) total += weight * 3;
      else if (secondary.indexOf(w) >= 0) total += weight;
    }
    return total;
  }

  function norm(value) {
    return String(value === undefined || value === null ? '' : value).toLowerCase();
  }

  /**
   * @param primary the text the icon sits beside, usually a title or a label
   * @param secondary supporting copy, weighted lower
   * @param index the scene or card position, used only to rotate the fallback
   * @param taken optional map of names already used in this scene, so a grid of six
   *        cards does not come out wearing the same icon six times
   */
  function pick(primary, secondary, index, taken) {
    var a = norm(primary);
    var b = norm(secondary);
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < ORDER.length; i++) {
      var name = ORDER[i];
      if (taken && taken[name]) continue;
      var s = score(name, a, b);
      if (s > bestScore) { bestScore = s; best = name; }
    }
    if (best) {
      if (taken) taken[best] = true;
      return best;
    }
    // Nothing matched. Walk the order from the caller's position and take the first
    // free slot, which is deterministic and therefore identical in a headless render.
    var start = Math.abs(index | 0) % ORDER.length;
    for (var k = 0; k < ORDER.length; k++) {
      var candidate = ORDER[(start + k) % ORDER.length];
      if (!taken || !taken[candidate]) {
        if (taken) taken[candidate] = true;
        return candidate;
      }
    }
    return ORDER[start];
  }

  /**
   * A mounted icon: a tinted disc with the artwork centred on it.
   *
   * The disc carries the icon's own dominant colour, which is what keeps a set of six
   * reading as colour rather than as a row of identical chips. Sized by CSS, not here.
   */
  function el(host, name, cls) {
    var icon = ICONS[name] || ICONS[ORDER[0]];
    var node = document.createElement('span');
    node.className = 'ic' + (cls ? ' ' + cls : '');
    node.setAttribute('data-icon', name);
    node.style.setProperty('--ic-tint', icon.tint);
    var art = document.createElement('span');
    art.className = 'ic__art';
    art.style.backgroundImage = 'url("' + icon.uri + '")';
    node.appendChild(art);
    if (host) host.appendChild(node);
    return node;
  }

  return { get: get, pick: pick, el: el, names: ORDER };
})();
