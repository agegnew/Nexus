import taskflowReady from './taskflow.ready.json'
import minimalReady from './minimal.ready.json'
import emptyGraph from './empty.json'
import errorGraph from './error.json'
import harborReady from './harbor.ready.json'

// Fixtures mirror the ProjectGraph payload that ProjectFlowAnalyzer.kt serialises
// into window.__CODE_VISUALIZER_GRAPH__, so the UI can be built without the IDE.
export const DEMO_FIXTURES = [
  { id: 'taskflow', label: 'TaskFlow', graph: taskflowReady },
  { id: 'minimal', label: 'Minimal', graph: minimalReady },
  { id: 'empty', label: 'Empty', graph: emptyGraph },
  { id: 'error', label: 'Error', graph: errorGraph },
  // The graph the analyzer produces for demo/nexus-demo-app, which the Swarm tab tests.
  { id: 'harbor', label: 'Harbor', graph: harborReady },
]

export const DEFAULT_DEMO_ID = DEMO_FIXTURES[0].id

export function demoGraph(demoId) {
  const fixture = DEMO_FIXTURES.find((item) => item.id === demoId)
  return (fixture ?? DEMO_FIXTURES[0]).graph
}

// Demo data is inert outside `vite dev`, and it stays out of the way when the tool
// window launched us with a real project attached.
export function demoIdFromParams(params) {
  if (!import.meta.env.DEV) return null

  const requested = params.get('demo')
  if (requested === 'off') return null
  if (requested) return DEMO_FIXTURES.some((item) => item.id === requested) ? requested : DEFAULT_DEMO_ID

  const launchedByPlugin = params.has('projectPath') || params.has('projectName')
  return launchedByPlugin ? null : DEFAULT_DEMO_ID
}
