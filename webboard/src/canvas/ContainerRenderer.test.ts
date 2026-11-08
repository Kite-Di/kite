import { describe, expect, it } from 'vitest';
import { CONTAINER_FOCUS_DIM, containerStyle } from './ContainerRenderer';

// A module box must read as a box — in the board and in a screenshot scaled down
// to a docs column. At a 0.34 border it was a faint outline, and with a card
// focused (dim 0.45) it all but vanished.
describe('containerStyle', () => {
  it('draws a clearly visible border around every module', () => {
    expect(containerStyle(false, 1).stroke).toBeGreaterThanOrEqual(0.6);
  });

  it('keeps the modules visible while a card is focused', () => {
    expect(containerStyle(false, CONTAINER_FOCUS_DIM).stroke).toBeGreaterThanOrEqual(0.5);
  });

  it('still sets the selected module apart from the rest', () => {
    expect(containerStyle(true, 1).stroke).toBeGreaterThan(containerStyle(false, 1).stroke);
    expect(containerStyle(true, 1).fill).toBeGreaterThan(containerStyle(false, 1).fill);
  });
});
