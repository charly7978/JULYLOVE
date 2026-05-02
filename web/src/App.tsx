import { useMonitor } from './hooks/useMonitor'
import { MonitorScreen } from './ui/MonitorScreen'

export default function App() {
  const monitor = useMonitor()
  return <MonitorScreen monitor={monitor} />
}
