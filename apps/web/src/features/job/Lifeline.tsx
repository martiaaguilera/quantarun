import { useState } from 'react'
import { formatMs } from '../../format'
import type { Lifeline as LifelineData } from './story'

const LANE_HEIGHT = 26
const LABEL_WIDTH = 190
const AXIS_HEIGHT = 22
const WIDTH = 1000

function ticks(span: number): number[] {
  const steps = [100, 250, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000, 3_600_000]
  const step = steps.find((candidate) => span / candidate <= 8) ?? span / 6
  const values: number[] = []
  for (let value = 0; value <= span; value += step) {
    values.push(value)
  }
  return values
}

/**
 * The job on one time axis, from submission to its end (or now): when it waited, where each attempt ran, when it was
 * claimed, checkpoints, and the moment a lease was lost. Hover a segment for its exact timing.
 */
export function Lifeline({ data }: { data: LifelineData }) {
  const [hover, setHover] = useState<string | null>(null)
  const span = Math.max(1, data.end - data.start)
  const plot = WIDTH - LABEL_WIDTH - 10
  const x = (at: number) => LABEL_WIDTH + ((at - data.start) / span) * plot
  const height = AXIS_HEIGHT + data.lanes.length * LANE_HEIGHT + 4

  return (
    <div className="lifeline">
      <svg viewBox={`0 0 ${String(WIDTH)} ${String(height)}`} role="img" aria-label="Job lifeline: waits and attempts over time">
        {ticks(span).map((offset) => (
          <g key={offset}>
            <line className="lifeline__grid" x1={x(data.start + offset)} x2={x(data.start + offset)} y1={AXIS_HEIGHT - 4} y2={height} />
            <text className="lifeline__tick" x={x(data.start + offset)} y={12} textAnchor="middle">
              +{formatMs(offset)}
            </text>
          </g>
        ))}
        {data.lanes.map((lane, index) => {
          const y = AXIS_HEIGHT + index * LANE_HEIGHT
          return (
            <g key={lane.key}>
              <text className="lifeline__lane-label" x={0} y={y + LANE_HEIGHT / 2 + 4}>
                {lane.label}
              </text>
              {lane.segments.map((segment, segmentIndex) => {
                const id = `${lane.key}-${String(segmentIndex)}`
                const left = x(segment.from)
                const width = Math.max(2, x(segment.to) - left)
                return (
                  <g key={id} onMouseEnter={() => { setHover(id) }} onMouseLeave={() => { setHover(null) }}>
                    <rect
                      className={`lifeline__segment lifeline__segment--${segment.kind} tone--${segment.tone}`}
                      x={left}
                      y={y + (segment.kind === 'run' ? 5 : 9)}
                      width={width}
                      height={segment.kind === 'run' ? LANE_HEIGHT - 10 : LANE_HEIGHT - 18}
                      rx={2}
                    >
                      <title>{segment.label}</title>
                    </rect>
                    {hover === id && (
                      <text className="lifeline__hover" x={Math.min(left, WIDTH - 260)} y={y + 2} dy={-2}>
                        {segment.label}
                      </text>
                    )}
                  </g>
                )
              })}
              {lane.marks.map((mark) => (
                <g key={`${lane.key}-${String(mark.at)}-${mark.kind}`} className={`lifeline__mark lifeline__mark--${mark.kind}`}>
                  {mark.kind === 'checkpoint' ? (
                    <rect x={x(mark.at) - 1} y={y + 2} width={2} height={LANE_HEIGHT - 4} />
                  ) : (
                    <path
                      d={`M${String(x(mark.at) - 5)},${String(y + 8)} l10,10 m0,-10 l-10,10`}
                      strokeWidth={2}
                    />
                  )}
                  <title>{mark.label}</title>
                </g>
              ))}
            </g>
          )
        })}
      </svg>
      <ul className="legend legend--inline">
        <li>
          <span className="legend__swatch lifeline__segment--queued tone--neutral" aria-hidden="true" /> Waiting in the queue
        </li>
        <li>
          <span className="legend__swatch lifeline__segment--claim tone--neutral" aria-hidden="true" /> Assigned, not yet claimed
        </li>
        <li>
          <span className="legend__swatch tone--good" aria-hidden="true" /> Ran and succeeded
        </li>
        <li>
          <span className="legend__swatch tone--bad" aria-hidden="true" /> Ran and failed or was lost
        </li>
        <li>
          <span className="legend__swatch legend__swatch--tick" aria-hidden="true" /> Checkpoint
        </li>
        <li>
          <span className="legend__cross" aria-hidden="true">×</span> Lease expired
        </li>
      </ul>
    </div>
  )
}
