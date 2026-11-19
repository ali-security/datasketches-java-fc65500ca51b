/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.datasketches.sampling;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import org.apache.datasketches.AllocationTracker;
import org.apache.datasketches.ArrayOfLongsSerDe;
import org.apache.datasketches.ByteArrayUtil;
import org.apache.datasketches.ResizeFactor;
import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.testng.annotations.Test;

/**
 * Checks that corrupt VarOptItemsSketch images are rejected without large allocations.
 * Adapted from upstream VarOptCorruptImageTest (apache/datasketches-java 5061899e0) to the 3.1.0 Memory API.
 */
public class VarOptCorruptImageTest {
  private static final int K_INT = 4;
  private static final int H_COUNT_INT = 16;
  private final ArrayOfLongsSerDe serDe = new ArrayOfLongsSerDe();

  private byte[] image(final int k, final int n, final ResizeFactor rf) {
    final VarOptItemsSketch<Long> sk = VarOptItemsSketch.newInstance(k, rf);
    for (long i = 0; i < n; i++) { sk.update(i, 1.0 + (i % 7)); }
    return sk.toByteArray(serDe);
  }

  private void checkRejected(final byte[] bytes) {
    final long allocated = AllocationTracker.allocatedBytes(
        () -> VarOptItemsSketch.heapify(Memory.wrap(bytes), serDe));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
    expectThrows(SketchesArgumentException.class, () -> VarOptItemsSketch.heapify(Memory.wrap(bytes), serDe));
  }

  @Test
  public void checkValidImagesStillWork() {
    for (final ResizeFactor rf : ResizeFactor.values()) {
      for (final int n : new int[] {1, 10, 32, 50, 1000}) {
        final VarOptItemsSketch<Long> sk = VarOptItemsSketch.heapify(Memory.wrap(image(32, n, rf)), serDe);
        assertEquals(sk.getN(), n);
        for (long i = 0; i < 100; i++) { sk.update(i, 2.0); } //grows as needed
        assertEquals(sk.getN(), n + 100);
      }
    }
  }

  @Test
  public void checkLargeKInSamplingMode() {
    final byte[] bytes = image(32, 1000, ResizeFactor.X8);
    ByteArrayUtil.putIntLE(bytes, K_INT, 1 << 28);
    checkRejected(bytes);
  }

  @Test
  public void checkLargeKInWarmupMode() {
    final byte[] bytes = image(32, 10, ResizeFactor.X1); //X1 would allocate the full k at once
    ByteArrayUtil.putIntLE(bytes, K_INT, 1 << 28);
    final long allocated = AllocationTracker.allocatedBytes(
        () -> VarOptItemsSketch.heapify(Memory.wrap(bytes), serDe));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
    final VarOptItemsSketch<Long> sk = VarOptItemsSketch.heapify(Memory.wrap(bytes), serDe);
    assertEquals(sk.getK(), 1 << 28);
    assertEquals(sk.getNumSamples(), 10);
    sk.update(100L, 1.0);
    assertEquals(sk.getNumSamples(), 11);
  }

  @Test
  public void checkCountsBeyondImage() {
    final byte[] h = image(32, 10, ResizeFactor.X8);
    ByteArrayUtil.putIntLE(h, H_COUNT_INT, 1 << 28); //more than k
    checkRejected(h);
    final byte[] h2 = image(1 << 30, 10, ResizeFactor.X8); //large legal k, so only the image size limits hCount
    ByteArrayUtil.putIntLE(h2, H_COUNT_INT, 1 << 28);
    checkRejected(h2);
  }
}
