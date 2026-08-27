import { useEffect, useState } from 'react'
import { fetchStats, triggerIngest } from '../api/client'
import type { Stats } from '../types'

export function StatsHeader({ onIngested }: { onIngested: () => void }) {
  const [stats, setStats] = useState<Stats | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    fetchStats()
      .then((s) => {
        setStats(s)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }

  useEffect(load, [])

  const runIngest = async () => {
    setRunning(true)
    setError(null)
    try {
      await triggerIngest()
      load()
      onIngested()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setRunning(false)
    }
  }

  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Job Discovery</h1>
        <p className="mt-1 text-sm text-slate-600">
          {stats
            ? `${stats.totalJobs} jobs · ${stats.activeTenants} active ATS tenants`
            : error
              ? 'Backend unreachable'
              : 'Loading…'}
        </p>
        {stats?.lastRun && (
          <p className="mt-1 text-xs text-slate-500">
            Last run: {stats.lastRun.source} — {stats.lastRun.status} (fetched{' '}
            {stats.lastRun.fetched}, new {stats.lastRun.created}, merged {stats.lastRun.merged})
          </p>
        )}
        {stats && Object.keys(stats.jobsBySource).length > 0 && (
          <p className="mt-1 text-xs text-slate-500">
            {Object.entries(stats.jobsBySource)
              .filter(([, count]) => count > 0)
              .map(([source, count]) => `${source} ${count}`)
              .join(' · ') || 'No sightings recorded yet'}
          </p>
        )}
      </div>
      <button
        onClick={runIngest}
        disabled={running}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-50"
      >
        {running ? 'Running…' : 'Run ingest'}
      </button>
    </header>
  )
}
