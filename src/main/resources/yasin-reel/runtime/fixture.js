/*
 * A hand written Storyboard for this very repository, in both cuts.
 *
 * It exists so the player can be developed and reviewed by opening index.html in any
 * browser with ?fixture=1, with no IDE, no key and no model call. Every file and line
 * it references is a real one in Nexus, so click to source is exercised for real the
 * moment the page is opened inside the IDE.
 *
 * Nothing here is derived at load time: no Date.now, no randomness. The same fixture
 * has to produce the same frames every run, which is the same rule the pipeline obeys.
 *
 * bridge.js decides when to use this. This file only holds the data.
 *
 * There are three: the two real cuts, and `stress`, the regression harness for the
 * defect where a stat-grid was handed sentences and drew them as display digits, cut
 * off at the top and bottom of their cards. Everything in `stress` is deliberately
 * larger than the slot it goes in: a 90 character product name, a 300 character card,
 * nine journey steps, six layers of five, and the four sentence-valued stats a real run
 * actually produced. No frame of it may clip, at ?fixture=stress.
 */
window.ReelFixture = (function () {
  'use strict';

  var THEME = {
    projectName: 'Nexus',
    colors: ['oklch(72% 0.12 185)', 'oklch(81% 0.12 95)', 'oklch(66% 0.15 275)']
  };

  var REPO = 'github.com/virufy/nexus';
  var STAMP = '22 September 2026';

  var technical = {
    audience: 'technical',
    totalMs: 56000,
    theme: THEME,
    scenes: [
      {
        template: 'title',
        durationMs: 6000,
        slots: {
          productName: 'Nexus',
          tagline: 'An IntelliJ plugin that reads a project and draws how it actually fits together.',
          repoUrl: REPO
        },
        narration: 'Nexus is an IDE plugin that turns whatever project you have open into a picture of itself.',
        sourceRefs: [{ file: 'README.md', line: 1 }]
      },
      {
        template: 'stat-grid',
        durationMs: 8000,
        slots: {
          heading: 'What it is made of',
          stats: [
            { label: 'Kotlin sources', value: '38' },
            { label: 'Lines of source', value: '7,420' },
            { label: 'Runtime dependencies', value: '2' },
            { label: 'Languages in play', value: '3' }
          ]
        },
        narration: 'It is small. Two runtime dependencies, three languages, and most of the weight sits in the analyser.',
        sourceRefs: [{ file: 'build.gradle.kts', line: 1 }]
      },
      {
        template: 'arch-layers',
        durationMs: 12000,
        slots: {
          heading: 'Architecture',
          layers: [
            {
              name: 'IDE surface',
              components: [
                { name: 'MyToolWindowFactory', tech: 'JCEF' },
                { name: 'ReelToolWindowFactory', tech: 'JCEF' }
              ]
            },
            {
              name: 'Analysis',
              components: [
                { name: 'ProjectFlowAnalyzer', tech: 'VirtualFile' },
                { name: 'EvidenceHarvester', tech: 'Kotlin' }
              ]
            },
            {
              name: 'Transport',
              components: [
                { name: 'ReelServer', tech: 'HttpServer' },
                { name: 'Gson', tech: 'JSON' }
              ]
            },
            {
              name: 'Panel',
              components: [
                { name: 'App.jsx', tech: 'React' },
                { name: 'React Flow', tech: 'graph' }
              ]
            }
          ]
        },
        narration: 'Four layers, and the interesting thing is the seam. The two halves share nothing but one JSON payload.',
        sourceRefs: [
          { file: 'src/main/kotlin/ProjectFlowAnalyzer.kt', line: 89 },
          { file: 'visualizer-ui/src/App.jsx', line: 264 }
        ]
      },
      {
        template: 'flow-trace',
        durationMs: 14000,
        slots: {
          name: 'One click, end to end',
          steps: [
            {
              label: 'Tool window opens',
              detail: 'createToolWindowContent',
              file: 'src/main/kotlin/MyToolWindowFactory.kt',
              line: 91
            },
            {
              label: 'Project is analysed',
              detail: 'analyze(project)',
              file: 'src/main/kotlin/ProjectFlowAnalyzer.kt',
              line: 89
            },
            {
              label: 'Graph is serialised',
              detail: 'Gson.toJson',
              file: 'src/main/kotlin/MyToolWindowFactory.kt',
              line: 115
            },
            {
              label: 'Crosses into Chromium',
              detail: "CustomEvent 'code-visualizer:graph'",
              file: 'src/main/kotlin/MyToolWindowFactory.kt',
              line: 119
            },
            {
              label: 'The panel draws it',
              detail: 'React Flow',
              file: 'visualizer-ui/src/App.jsx',
              line: 264
            }
          ]
        },
        narration: 'There is no network call anywhere in this. The boundary is a JVM handing a string to a browser it is hosting.',
        sourceRefs: [{ file: 'src/main/kotlin/MyToolWindowFactory.kt', line: 116 }]
      },
      {
        template: 'capability-cards',
        durationMs: 10000,
        slots: {
          heading: 'What it gives you',
          cards: [
            {
              title: 'A graph you can trust',
              body: 'Nodes come from files that exist and edges from calls that were found, never from a guess.',
              file: 'src/main/kotlin/ProjectFlowAnalyzer.kt',
              line: 89
            },
            {
              title: 'Evidence before narrative',
              body: 'Facts are harvested with no model in the loop, then handed to the model as the only thing it may cite.',
              file: 'src/main/kotlin/com/example/yasinreel/model/Evidence.kt',
              line: 16
            },
            {
              title: 'Click straight into the code',
              body: 'Every path on screen is live. Click one mid playback and the editor opens on that line.',
              file: 'src/main/kotlin/com/example/yasinreel/render/ReelToolWindowFactory.kt',
              line: 171
            }
          ]
        },
        narration: 'The rule throughout is the same. Show the real thing, and make the real thing reachable.',
        sourceRefs: []
      },
      {
        template: 'outro',
        durationMs: 6000,
        slots: {
          cta: 'Open a project. Watch it explain itself.',
          repoUrl: REPO,
          generatedAt: STAMP
        },
        narration: 'That is Nexus.',
        sourceRefs: []
      }
    ]
  };

  var stakeholder = {
    audience: 'stakeholder',
    totalMs: 50000,
    theme: THEME,
    scenes: [
      {
        template: 'title',
        durationMs: 6000,
        slots: {
          productName: 'Nexus',
          tagline: 'Understand a piece of software you have never seen, in about a minute.',
          repoUrl: REPO
        },
        narration: 'Nexus answers one question: what is this thing, and how does it work.',
        sourceRefs: []
      },
      {
        template: 'big-statement',
        durationMs: 7000,
        slots: {
          statement: 'Nobody reads a new project. They guess, and then they find out.',
          context: 'The first week on unfamiliar software is mostly spent building a mental picture that already exists, just nowhere anyone can look at it.'
        },
        narration: 'Every engineer who joins a team pays the same tax, and every team pays it again with the next hire.',
        sourceRefs: []
      },
      {
        template: 'journey',
        durationMs: 13000,
        slots: {
          name: 'A first day, with Nexus open',
          steps: [
            { actor: 'A new engineer', action: 'opens the project on their first morning' },
            { actor: 'Nexus', action: 'reads every file and draws how the pieces connect' },
            { actor: 'The engineer', action: 'watches a short film about the work instead of reading it' },
            { actor: 'They click a step', action: 'and land in the exact place that does it' },
            { actor: 'By lunchtime', action: 'they are changing something real' }
          ]
        },
        narration: 'Same project, same person. The difference is that the shape of the work arrives first.',
        sourceRefs: []
      },
      {
        template: 'capability-cards',
        durationMs: 10000,
        slots: {
          heading: 'What a person can now do',
          cards: [
            { title: 'See the whole thing at once', body: 'One picture of how the work is put together, drawn from the work itself.' },
            { title: 'Show it to someone else', body: 'Two versions of the same story: one for the people building it, one for everyone else.' },
            { title: 'Check any claim', body: 'Everything said out loud points at the place it came from, one click away.' }
          ]
        },
        narration: 'Three things, and all three come out of the same read of the project.',
        sourceRefs: []
      },
      {
        template: 'stat-grid',
        durationMs: 8000,
        slots: {
          heading: 'Where the time goes',
          stats: [
            { label: 'Minutes to the first picture', value: '2' },
            { label: 'Days of onboarding it replaces', value: '3' },
            { label: 'People who need to be asked', value: '0' }
          ]
        },
        narration: 'It runs on a laptop, on whatever is already open, and it asks nobody for anything.',
        sourceRefs: []
      },
      {
        template: 'outro',
        durationMs: 6000,
        slots: {
          cta: 'Two minutes to understand a year of work.',
          repoUrl: REPO,
          generatedAt: STAMP
        },
        narration: 'Nexus.',
        sourceRefs: []
      }
    ]
  };

  /*
   * The four values below are quoted from .idea/yasin-reel/storyboard-stakeholder.json
   * as it was generated, not invented. They are the bug.
   */
  var stress = {
    audience: 'technical',
    totalMs: 54000,
    theme: THEME,
    scenes: [
      {
        template: 'title',
        durationMs: 6000,
        slots: {
          productName: 'Nexus Reel Continuous Narration And Codebase Understanding Platform For Teams',
          tagline: 'A deliberately overlong tagline that keeps going well past the point any director would sensibly stop, because the template has to hold whatever the model decides to write into this slot on a bad day.',
          repoUrl: 'github.com/virufy/nexus/tree/yasin/full-codebase-video/src/main/resources/yasin-reel'
        },
        narration: 'This is the stress fixture. Every slot in it carries more than the template was designed for, and the point of the harness is that not one frame of it may be cut off.',
        sourceRefs: [{ file: 'src/main/resources/yasin-reel/runtime/fixture.js', line: 1 }]
      },
      {
        template: 'big-statement',
        durationMs: 6000,
        slots: {
          statement: 'A template that only holds together for the data it was written against is not a template, it is a coincidence, and the demo is the moment the coincidence runs out and the audience watches a sentence get sliced in half.',
          context: 'The supporting line under the statement is also far longer than it has any business being, which is exactly the case that pushes the quote above it out of its own half of the frame.'
        },
        narration: 'A contract protects the common case. A defensive template protects the demo, which is the only case anybody watches.',
        sourceRefs: []
      },
      {
        template: 'stat-grid',
        durationMs: 6000,
        slots: {
          heading: 'What is real today, with the values exactly as one real run emitted them',
          stats: [
            { label: 'Built and working', value: 'A large share of the product surface is in place' },
            { label: 'Implementation mix', value: 'Both screen-side and service-side work are substantial' },
            { label: 'Interaction surface', value: '200 screen-to-service actions detected' },
            { label: 'Automated checks', value: '64 check files found' }
          ]
        },
        narration: 'These four are the defect itself, copied out of the run the user photographed.',
        sourceRefs: [{ file: 'src/main/resources/yasin-reel/runtime/scenes.js', line: 424 }]
      },
      {
        template: 'capability-cards',
        durationMs: 6000,
        slots: {
          heading: 'Six cards where the layout expects three',
          cards: [
            {
              title: 'A card body of three hundred characters, which is roughly five times what this slot was measured for',
              body: 'This body is three hundred characters long on purpose. It exists to prove that a card handed five times the copy it was designed around shrinks its own type and clamps what is left, rather than letting the paragraph run out through the bottom edge of the card and off the stage entirely.',
              file: 'src/main/resources/yasin-reel/reel.css',
              line: 446
            },
            { title: 'Short one', body: 'Short body.' },
            {
              title: 'Averylongunbrokentokenthatcannotwrapanywhereatallwithoutanexplicitbreakrule',
              body: 'A single unbroken token in the title, because a class name or a package path arrives that way.',
              file: 'src/main/kotlin/com/example/yasinreel/model/Storyboard.kt',
              line: 1
            },
            { title: 'Fourth capability', body: 'A body of an ordinary, expected length, for contrast with its neighbours.' },
            { title: 'Fifth capability', body: 'Another ordinary body so the row is full.' },
            { title: 'Sixth capability with a title that is itself rather long', body: 'And a body that is moderately long as well, to fill the last cell of the grid.' }
          ]
        },
        narration: 'Six cards, one of them carrying three hundred characters of body copy and one carrying a single unbreakable token.',
        sourceRefs: []
      },
      {
        // The worst case for the recreated interface: the longest row a cap allows, the
        // most rows it allows, two badges, five stage labels at the character limit, and
        // a product that ships dark, so the frame is drawn in colours nothing else in the
        // film uses. If this reads, a real project reads.
        template: 'product-ui',
        durationMs: 6000,
        slots: {
          eyebrow: 'The product',
          brand: 'A product with a long name',
          brandSub: 'And a line under it that is itself long',
          nav: [
            { label: 'Dashboard' },
            { label: 'A row at the cap exactly' },
            { label: 'Assets' },
            { label: 'Ideation' },
            { label: 'Producer', badge: 'beta' },
            { label: 'SMAA intelligence', badge: 'preview' },
            { label: 'Library' },
            { label: 'Settings' }
          ],
          more: 3,
          stages: ['Ideation', 'Editing', 'Finalization', 'Scheduling', 'Approval'],
          tokens: {
            page: '0B0F14', surface: '141A21', line: '1E2630', ink: 'E6EDF3', dim: '8B98A5',
            accent: 'F0790B', accentInk: '000000', accentWash: '2A1F14', radius: 14, scheme: 'dark'
          }
        },
        narration: 'And this is the thing itself, in the colours and the words it already wears.',
        sourceRefs: []
      },
      {
        template: 'journey',
        durationMs: 6000,
        slots: {
          heading: 'Nine steps in a track that was drawn for four',
          steps: [
            { actor: 'A team member with a very long role description indeed', action: 'brings in school material and starts a refresh of the entire brand guidance document' },
            { actor: 'The system', action: 'pulls out useful words and visuals, then shapes brand guidance from them' },
            { actor: 'The team', action: 'uses that guidance to make new work' },
            { actor: 'The reviewer', action: 'checks a preview, asks for changes, and saves the finished result' },
            { actor: 'The planner', action: 'sets a run of future items and reviews them one by one' },
            { actor: 'The publisher', action: 'checks the final preview and places it on the calendar' },
            { actor: 'The analyst', action: 'reads what happened afterwards' },
            { actor: 'The team lead', action: 'decides what to do next' },
            { actor: 'Everyone', action: 'starts again on Monday' }
          ]
        },
        narration: 'Nine steps where the director was told to send four, and a line of narration far longer than the band the caption is given, because narration is now written across the whole film rather than dropped on the occasional title card, and a caption that grew upward over the picture would be the same defect in a different place.',
        sourceRefs: []
      },
      {
        template: 'arch-layers',
        durationMs: 6000,
        slots: {
          heading: 'Six layers of five components',
          layers: [
            {
              name: 'IDE surface and every entry point it owns',
              components: [
                { name: 'MyToolWindowFactory', tech: 'JCEF' },
                { name: 'ReelToolWindowFactory', tech: 'JCEF' },
                { name: 'ReelAction', tech: 'AnAction' },
                { name: 'ReelSettings', tech: 'State' },
                { name: 'ReelNotifications', tech: 'Balloon' }
              ]
            },
            {
              name: 'Analysis',
              components: [
                { name: 'ProjectFlowAnalyzer', tech: 'VirtualFile' },
                { name: 'EvidenceHarvester', tech: 'Kotlin' },
                { name: 'PaletteHarvester', tech: 'CSS' },
                { name: 'DependencyReader', tech: 'Gradle' },
                { name: 'TestCounter', tech: 'Index' }
              ]
            },
            {
              name: 'Understanding',
              components: [
                { name: 'ProductModelBuilder', tech: 'LLM' },
                { name: 'ToolLoop', tech: 'OkHttp' },
                { name: 'ModelCache', tech: 'JSON' },
                { name: 'Validator', tech: 'Rules' },
                { name: 'JargonCheck', tech: 'Lexicon' }
              ]
            },
            {
              name: 'Direction',
              components: [
                { name: 'TechnicalDirector', tech: 'LLM' },
                { name: 'StakeholderDirector', tech: 'LLM' },
                { name: 'StoryboardWriter', tech: 'Gson' },
                { name: 'DurationBalancer', tech: 'Kotlin' },
                { name: 'NarrationPlanner', tech: 'Kotlin' }
              ]
            },
            {
              name: 'Voice',
              components: [
                { name: 'SpeechSynthesiser', tech: 'TTS' },
                { name: 'AudioCache', tech: 'sha256' },
                { name: 'Mixer', tech: 'ffmpeg' },
                { name: 'Timing', tech: 'Kotlin' },
                { name: 'Fallback', tech: 'Silence' }
              ]
            },
            {
              name: 'Playback',
              components: [
                { name: 'timeline.js', tech: 'GSAP' },
                { name: 'scenes.js', tech: 'DOM' },
                { name: 'player.js', tech: 'transport' },
                { name: 'audio.js', tech: 'WebAudio' },
                { name: 'Exporter', tech: 'HTML' }
              ]
            }
          ]
        },
        narration: 'Six layers of five components each, in a stack the stylesheet sizes for five.',
        sourceRefs: [{ file: 'src/main/resources/yasin-reel/runtime/scenes.js', line: 1 }]
      },
      {
        template: 'flow-trace',
        durationMs: 6000,
        slots: {
          name: 'Six steps with labels nobody would write on purpose',
          steps: [
            { label: 'The tool window is opened by the developer', detail: 'createToolWindowContent, on the EDT', file: 'src/main/kotlin/MyToolWindowFactory.kt', line: 91 },
            { label: 'Facts are harvested with no model in the loop at all', detail: 'EvidenceHarvester.harvest(project)' },
            { label: 'The model is asked what the product is', detail: 'a real tool-calling loop, about a hundred seconds' },
            { label: 'A storyboard is directed per audience', detail: 'StakeholderDirector.direct(model)' },
            { label: 'Speech is synthesised and cached by content hash', detail: 'sha256 of the narration text' },
            { label: 'The film plays in a JCEF browser inside the IDE', detail: 'one paused GSAP timeline' }
          ]
        },
        narration: 'Six steps across a rail drawn for five, every label longer than the column it sits in.',
        sourceRefs: []
      },
      {
        template: 'stat-grid',
        durationMs: 6000,
        slots: {
          heading: 'Three across, mixing a number with a sentence',
          stats: [
            { label: 'Main work areas', value: '8' },
            { label: 'What the sentence-valued stat looks like beside a real number', value: 'Both screen-side and service-side work are substantial' },
            { label: 'Interaction surface', value: '200 screen-to-service actions detected' }
          ]
        },
        narration: 'The mixed case, because a grid where one tile is prose and the others are digits has to read as one grid.',
        sourceRefs: []
      },
      {
        template: 'outro',
        durationMs: 6000,
        slots: {
          cta: 'If nothing in this fixture is cut off at any second of its six, then the templates hold, and the film can be handed data nobody checked first.',
          repoUrl: 'github.com/virufy/nexus/tree/yasin/full-codebase-video',
          generatedAt: STAMP
        },
        narration: 'Every scene of this fixture was seeked into and looked at. That is the whole point of keeping it in the repository.',
        sourceRefs: []
      }
    ]
  };

  /*
   * bridge.js reads ?fixture= only to choose an audience, so the harness selects itself
   * here rather than needing a change in a file this work does not own. Still derived
   * from nothing but the URL, so the same address always produces the same film.
   */
  function asked() {
    try {
      return new URLSearchParams(window.location.search).get('fixture') || '';
    } catch (error) {
      return '';
    }
  }

  return {
    technical: technical,
    stakeholder: stakeholder,
    stress: stress,
    pick: function (audience) {
      if (asked() === 'stress') return stress;
      return audience === 'stakeholder' ? stakeholder : technical;
    }
  };
})();
