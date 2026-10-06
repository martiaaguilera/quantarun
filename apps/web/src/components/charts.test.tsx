import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BarChart } from './charts'

describe('BarChart', () => {
  it('labels every bar that ties for best, not just the first', () => {
    render(
      <BarChart
        title="Throughput"
        format={(value) => `${String(value)} jobs/min`}
        bars={[
          { label: 'FIFO', value: 67 },
          { label: 'PRIORITY', value: 67 },
          { label: 'FAIR SHARE', value: 60 },
        ]}
      />,
    )
    expect(screen.getAllByText('67 jobs/min')).toHaveLength(2)
    expect(screen.queryByText('60 jobs/min')).not.toBeInTheDocument()
  })
})
