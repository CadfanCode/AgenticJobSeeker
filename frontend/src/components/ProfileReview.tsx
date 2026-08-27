import type { Experience, Profile } from '../profileTypes'

interface Props {
  profile: Profile
  sourceText: string
  onChange: (next: Profile) => void
}

function Field({ label, value, onChange }: {
  label: string
  value: string | null
  onChange: (v: string) => void
}) {
  return (
    <label className="block">
      <span className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</span>
      <input
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
      />
    </label>
  )
}

export function ProfileReview({ profile, sourceText, onChange }: Props) {
  const setExperience = (index: number, patch: Partial<Experience>) => {
    const experiences = profile.experiences.map((e, i) => (i === index ? { ...e, ...patch } : e))
    onChange({ ...profile, experiences })
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Text read from your PDF
        </h2>
        <pre className="max-h-[70vh] overflow-auto whitespace-pre-wrap rounded-md bg-slate-50 p-3 font-mono text-xs leading-relaxed text-slate-700">
          {sourceText || 'No source text available.'}
        </pre>
      </section>

      <section className="space-y-6">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Full name" value={profile.fullName} onChange={(v) => onChange({ ...profile, fullName: v })} />
          <Field label="Headline" value={profile.headline} onChange={(v) => onChange({ ...profile, headline: v })} />
          <Field label="Email" value={profile.email} onChange={(v) => onChange({ ...profile, email: v })} />
          <Field label="Phone" value={profile.phone} onChange={(v) => onChange({ ...profile, phone: v })} />
          <Field label="Location" value={profile.location} onChange={(v) => onChange({ ...profile, location: v })} />
          <Field label="Language" value={profile.language} onChange={(v) => onChange({ ...profile, language: v })} />
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Experience</h3>
          <div className="space-y-4">
            {profile.experiences.map((experience, index) => (
              <div
                key={experience.id ?? index}
                className={`rounded-md border p-3 ${
                  experience.verified ? 'border-slate-200' : 'border-amber-400 bg-amber-50'
                }`}
              >
                {!experience.verified && experience.verificationNotes && (
                  <p className="mb-2 text-xs font-medium text-amber-800">
                    {experience.verificationNotes}
                  </p>
                )}
                <div className="grid gap-2 sm:grid-cols-2">
                  <Field label="Employer" value={experience.employer}
                         onChange={(v) => setExperience(index, { employer: v })} />
                  <Field label="Title" value={experience.title}
                         onChange={(v) => setExperience(index, { title: v })} />
                  <Field label="From" value={experience.startDate}
                         onChange={(v) => setExperience(index, { startDate: v })} />
                  <Field label="To" value={experience.endDate}
                         onChange={(v) => setExperience(index, { endDate: v })} />
                </div>

                <div className="mt-3 space-y-2">
                  {experience.bullets.map((bullet, bulletIndex) => (
                    <div key={bullet.id ?? bulletIndex} className="flex gap-2">
                      <textarea
                        value={bullet.text}
                        rows={2}
                        onChange={(e) =>
                          setExperience(index, {
                            bullets: experience.bullets.map((b, i) =>
                              i === bulletIndex ? { ...b, text: e.target.value } : b,
                            ),
                          })
                        }
                        className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
                      />
                      <button
                        onClick={() =>
                          setExperience(index, {
                            bullets: experience.bullets
                              .filter((_, i) => i !== bulletIndex)
                              .map((b, i) => ({ ...b, ordinal: i })),
                          })
                        }
                        className="shrink-0 rounded-md border border-slate-300 px-2 text-xs text-slate-600 hover:bg-slate-50"
                        aria-label="Remove bullet"
                      >
                        Remove
                      </button>
                    </div>
                  ))}
                  <button
                    onClick={() =>
                      setExperience(index, {
                        bullets: [...experience.bullets, { text: '', ordinal: experience.bullets.length }],
                      })
                    }
                    className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50"
                  >
                    Add bullet
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Education</h3>
          <div className="space-y-3">
            {profile.education.map((entry, index) => (
              <div
                key={entry.id ?? index}
                className={`rounded-md border p-3 ${
                  entry.verified ? 'border-slate-200' : 'border-amber-400 bg-amber-50'
                }`}
              >
                {!entry.verified && entry.verificationNotes && (
                  <p className="mb-2 text-xs font-medium text-amber-800">{entry.verificationNotes}</p>
                )}
                <div className="grid gap-2 sm:grid-cols-2">
                  <Field label="Institution" value={entry.institution}
                         onChange={(v) => onChange({
                           ...profile,
                           education: profile.education.map((x, i) =>
                             i === index ? { ...x, institution: v } : x),
                         })} />
                  <Field label="Degree" value={entry.degree}
                         onChange={(v) => onChange({
                           ...profile,
                           education: profile.education.map((x, i) =>
                             i === index ? { ...x, degree: v } : x),
                         })} />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold text-slate-900">Skills</h3>
          <input
            value={profile.skills.map((s) => s.name).join(', ')}
            onChange={(e) =>
              onChange({
                ...profile,
                skills: e.target.value
                  .split(',')
                  .map((s) => s.trim())
                  .filter(Boolean)
                  .map((name, i) => ({ name, category: null, ordinal: i })),
              })
            }
            className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-slate-900"
            placeholder="Comma-separated"
          />
        </div>
      </section>
    </div>
  )
}
