import { describe, expect, it } from 'vitest';
import type { GraphNode } from '../model/graph';
import { makeVNode, Scene } from './Scene';

function node(id: string): GraphNode {
  return { id, type: id, displayName: id, kind: 'injectable', boundTo: [] };
}

/** A 100×50 card at (x, y), optionally owned by a lane. */
function place(scene: Scene, id: string, x: number, y: number, lane: string | null = null): void {
  const vn = makeVNode(node(id), 100, 50);
  vn.x = x;
  vn.y = y;
  vn.lane = lane;
  scene.addNode(vn);
}

describe('Scene selection', () => {
  it('the rectangle catches every card it touches, and only those', () => {
    const scene = new Scene();
    place(scene, 'inside', 0, 0);
    place(scene, 'touching', 90, 40); // overlaps the rect's corner
    place(scene, 'outside', 600, 600);

    const caught = scene.idsIn({ x: -10, y: -10, w: 110, h: 60 }).sort();
    expect(caught).toEqual(['inside', 'touching']);
  });

  it('filtered-out cards are not caught — the rectangle picks what you can see', () => {
    const scene = new Scene();
    place(scene, 'shown', 0, 0);
    place(scene, 'filtered', 0, 100);
    scene.setFilterVisible(new Set(['shown']));

    expect(scene.idsIn({ x: -50, y: -50, w: 400, h: 400 })).toEqual(['shown']);
  });

  it('a module selection carries its lane; a rectangle selection does not', () => {
    const scene = new Scene();
    place(scene, 'a', 0, 0, ':core');
    place(scene, 'b', 0, 100, ':core');

    scene.select(['a', 'b'], ':core');
    expect(scene.selectedLane).toBe(':core');
    expect([...scene.selection].sort()).toEqual(['a', 'b']);

    scene.select(['a']);
    expect(scene.selectedLane).toBeNull();
    expect([...scene.selection]).toEqual(['a']);
  });

  it('selecting nothing leaves no lane behind — an empty sweep cannot arm a module drag', () => {
    const scene = new Scene();
    place(scene, 'a', 0, 0, ':core');
    scene.select(['a'], ':core');

    scene.select([], ':core');
    expect(scene.selection.size).toBe(0);
    expect(scene.selectedLane).toBeNull();
  });

  it('unknown ids are dropped rather than selected', () => {
    const scene = new Scene();
    place(scene, 'a', 0, 0);

    scene.select(['a', 'ghost']);
    expect([...scene.selection]).toEqual(['a']);
  });

  it('a removed card leaves the selection, and the last one disarms the lane', () => {
    const scene = new Scene();
    place(scene, 'a', 0, 0, ':core');
    place(scene, 'b', 0, 100, ':core');
    scene.select(['a', 'b'], ':core');

    scene.removeNode('a');
    expect([...scene.selection]).toEqual(['b']);
    expect(scene.selectedLane).toBe(':core');

    scene.removeNode('b');
    expect(scene.selectedLane).toBeNull();
  });

  it('the selection ring covers both the focused card and the picked ones', () => {
    const scene = new Scene();
    place(scene, 'focused', 0, 0);
    place(scene, 'picked', 0, 100);
    place(scene, 'neither', 0, 200);

    scene.setFocus('focused');
    scene.select(['picked']);

    expect(scene.isSelected('focused')).toBe(true);
    expect(scene.isSelected('picked')).toBe(true);
    expect(scene.isSelected('neither')).toBe(false);
  });

  it('clearing the selection leaves the focus alone — they answer different questions', () => {
    const scene = new Scene();
    place(scene, 'a', 0, 0);
    place(scene, 'b', 0, 100);
    scene.setFocus('a');
    scene.select(['b']);

    scene.clearSelection();
    expect(scene.selection.size).toBe(0);
    expect(scene.selectedId).toBe('a');
  });
});
