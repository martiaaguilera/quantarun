import { useId, useState } from 'react'

export interface Bar {
  label: string
  value: number
  /** Shown on hover and in the table; defaults to the value. */
  display?: string
}

/**
 * Vertical bars for one measure across a few categories (policies, resources). One series, so one hue and no legend:
 * the title names the measure. Thin bars with rounded tops on a recessive baseline; the exact value appears on hover
 * and in the always-available table.
 */
export function BarChart({
  title,
  bars,
  format,
  height = 140,
  lowerIsBetter = false,
}: {
  title: string
  bars: Bar[]
  format: (value: number) => string
  height?: number
  lowerIsBetter?: boolean
}) {
  const [hover, setHover] = useState<number | null>(null)
  const [asTable, setAsTable] = useState(false)
  const id = useId()
  const max = Math.max(...bars.map((bar) => bar.value), 0)
  const width = Math.max(240, bars.length * 64)
  const plotTop = 18
  // Two label lines fit under the baseline, so multi-word categories wrap instead of colliding with their neighbours.
  const plotBottom = height - 36
  const slot = width / Math.max(1, bars.length)
  const barWidth = Math.min(28, slot * 0.5)
  const best = bars.length === 0 ? null : bars.reduce((a, b) => ((lowerIsBetter ? b.value < a.value : b.value > a.value) ? b : a))

  return (
    <figure className="chart">
      <figcaption className="chart__title">
        <span id={id}>{title}</span>
        <button type="button" className="link-button" onClick={() => { setAsTable((current) => !current) }}>
          {asTable ? 'Show chart' : 'Show table'}
        </button>
      </figcaption>
      {asTable ? (
        <table className="data-table data-table--compact">
          <tbody>
            {bars.map((bar) => (
              <tr key={bar.label}>
                <th scope="row">{bar.label}</th>
                <td className="num">{bar.display ?? format(bar.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <svg
          className="chart__svg"
          viewBox={`0 0 ${String(width)} ${String(height)}`}
          role="img"
          aria-labelledby={id}
          onMouseLeave={() => { setHover(null) }}
        >
          <line className="chart__baseline" x1={0} x2={width} y1={plotBottom} y2={plotBottom} />
          {bars.map((bar, index) => {
            const h = max === 0 ? 0 : ((plotBottom - plotTop) * bar.value) / max
            const x = slot * index + (slot - barWidth) / 2
            const y = plotBottom - h
            const isBest = best !== null && bar === best && bars.length > 1
            return (
              <g key={bar.label} onMouseEnter={() => { setHover(index) }}>
                {/* The hit target spans the whole slot, bigger than the bar itself. */}
                <rect className="chart__hit" x={slot * index} y={0} width={slot} height={height} />
                <path
                  className={`chart__bar ${hover === index ? 'chart__bar--hover' : ''}`}
                  d={roundedTop(x, y, barWidth, h)}
                />
                {(hover === index || isBest) && (
                  <text className="chart__value" x={valueX(x + barWidth / 2, bar.display ?? format(bar.value), width)} y={Math.max(12, y - 5)} textAnchor="middle">
                    {bar.display ?? format(bar.value)}
                  </text>
                )}
                <text className="chart__axis-label" x={x + barWidth / 2} y={plotBottom + 16} textAnchor="middle">
                  {bar.label.split(' ').map((word, line) => (
                    <tspan key={word + String(line)} x={x + barWidth / 2} dy={line === 0 ? 0 : 13}>
                      {word}
                    </tspan>
                  ))}
                </text>
              </g>
            )
          })}
        </svg>
      )}
    </figure>
  )
}

/** Keeps a centred value label inside the plot; an edge bar's label would otherwise be clipped by the viewBox. */
function valueX(centre: number, text: string, width: number): number {
  const half = (text.length * 6.5) / 2
  return Math.min(Math.max(centre, half), width - half)
}

/** A bar whose top corners are rounded (4 px) and whose base sits square on the baseline. */
function roundedTop(x: number, y: number, width: number, height: number): string {
  if (height <= 0) {
    return ''
  }
  const r = Math.min(4, width / 2, height)
  return `M${String(x)},${String(y + height)} V${String(y + r)} Q${String(x)},${String(y)} ${String(x + r)},${String(y)} H${String(x + width - r)} Q${String(x + width)},${String(y)} ${String(x + width)},${String(y + r)} V${String(y + height)} Z`
}

export interface Segment {
  key: string
  label: string
  value: number
}

/**
 * Parts of a whole on one line (the active queue by state). Categorical slots in a fixed order with a 2 px surface gap
 * between segments; the legend carries every label and count, so no part depends on its color.
 */
export function StackedBar({ segments, ariaLabel }: { segments: Segment[]; ariaLabel: string }) {
  const total = segments.reduce((sum, segment) => sum + segment.value, 0)
  return (
    <div className="stack">
      <div className="stack__bar" role="img" aria-label={ariaLabel}>
        {total === 0 ? (
          <span className="stack__empty" />
        ) : (
          segments
            .filter((segment) => segment.value > 0)
            .map((segment) => (
              <span
                key={segment.key}
                className={`stack__segment stack__segment--${segment.key}`}
                style={{ flexGrow: segment.value }}
                title={`${segment.label}: ${String(segment.value)}`}
              />
            ))
        )}
      </div>
      <ul className="legend">
        {segments.map((segment) => (
          <li key={segment.key}>
            <span className={`legend__swatch stack__segment--${segment.key}`} aria-hidden="true" />
            {segment.label} <strong className="num">{segment.value.toLocaleString()}</strong>
          </li>
        ))}
      </ul>
    </div>
  )
}
