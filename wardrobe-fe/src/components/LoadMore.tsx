export function LoadMore({
  onClick,
  loading = false,
  disabled = false,
}: {
  onClick: () => void
  loading?: boolean
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      onClick={loading || disabled ? undefined : onClick}
      aria-disabled={loading || disabled}
      className="rounded-sm border border-action px-4 py-2 text-sm text-action hover:bg-wash/50 aria-disabled:cursor-not-allowed aria-disabled:opacity-50 focus:outline focus:outline-2 focus:outline-offset-2 focus:outline-action"
    >
      {loading ? 'Loading…' : 'Load more'}
    </button>
  )
}
