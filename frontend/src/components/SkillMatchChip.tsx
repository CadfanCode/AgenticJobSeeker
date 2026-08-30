interface Props {
  matched: number | null
  names: string | null
}

/**
 * A count of facts, not a score. The names are on the title attribute so the number is
 * always checkable — that is the whole point of counting rather than scoring.
 *
 * `matched === null` means this posting has not been ranked yet. `matched === 0` means it
 * was ranked and matched nothing — a different state, not to be collapsed into the first.
 * The backend joins matched skill names with `String.join`, so `names` is `""`, not `null`,
 * when nothing matched — an emptiness check is required, not just a null check.
 */
export function SkillMatchChip({ matched, names }: Props) {
  if (matched === null) {
    return <span className="text-xs text-slate-400">not ranked</span>
  }
  const strong = matched >= 3
  return (
    <span
      title={names && names.length > 0 ? names : 'No skills from your profile appear in this ad'}
      className={`rounded-full px-2 py-0.5 text-xs font-medium ${
        strong ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-700'
      }`}
    >
      {matched} skill{matched === 1 ? '' : 's'}
    </span>
  )
}
