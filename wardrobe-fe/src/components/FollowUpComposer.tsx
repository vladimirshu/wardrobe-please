import { SEARCH_PLACEHOLDER } from '../lib/brand'
import { useGate } from '../gate/GateContext'

export function FollowUpComposer({
  value,
  onChange,
  onSubmit,
  error,
  onNewSearch,
  onFocus,
}: {
  value: string
  onChange: (value: string) => void
  onSubmit: (value: string) => void
  error?: string | null
  onNewSearch: () => void
  onFocus?: () => void
}) {
  const { isHealthy } = useGate()
  return (
    <div className="border-t border-border pt-3">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (isHealthy) onSubmit(value)
        }}
        noValidate
      >
        <label htmlFor="follow-up-input" className="sr-only">
          Refine your search
        </label>
        <div className="flex overflow-hidden rounded-sm border border-border bg-wash/40 focus-within:border-action">
          <input
            id="follow-up-input"
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onFocus={onFocus}
            placeholder={SEARCH_PLACEHOLDER}
            autoComplete="off"
            aria-describedby={error ? 'follow-up-hint' : undefined}
            aria-invalid={!!error}
            className="min-w-0 flex-1 bg-transparent px-3 py-2 text-[16px] text-ink outline-none placeholder:text-muted sm:text-sm"
          />
          <button
            type="submit"
            aria-disabled={!isHealthy}
            aria-describedby={!isHealthy ? 'follow-up-waiting' : undefined}
            className="shrink-0 bg-action px-3 text-sm text-paper hover:bg-action/90 focus:outline focus:outline-2 focus:outline-offset-[-2px] focus:outline-action aria-disabled:cursor-not-allowed aria-disabled:bg-wash aria-disabled:text-ink"
          >
            Find my match
          </button>
        </div>
      </form>
      {!isHealthy && (
        <p id="follow-up-waiting" className="mt-1 text-sm text-muted">You can type now and search when the shop is ready.</p>
      )}
      {error && (
        <p id="follow-up-hint" className="mt-1 text-sm text-ink" role="status">
          {error}
        </p>
      )}
      <button
        type="button"
        onClick={onNewSearch}
        className="mt-2 text-sm text-muted underline-offset-2 hover:text-action hover:underline focus:outline focus:outline-2 focus:outline-offset-2 focus:outline-action"
      >
        New search
      </button>
    </div>
  )
}
