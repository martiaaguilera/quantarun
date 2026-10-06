import { describe, expect, it } from 'vitest'
import { shortId } from './format'

describe('shortId', () => {
  it('tells apart UUIDv7 ids created in the same millisecond', () => {
    const first = '01a11190-9512-7fd5-b952-03525c5f11a0'
    const second = '01a11190-9512-7a01-8c3e-6f0b2d9e44c7'
    expect(shortId(first)).not.toBe(shortId(second))
    expect(shortId(first)).toBe('5c5f11a0')
  })

  it('shows a dash for a missing id', () => {
    expect(shortId(null)).toBe('–')
  })
})
