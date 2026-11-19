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

package org.apache.datasketches.quantiles;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.Comparator;

import org.apache.datasketches.AllocationTracker;
import org.apache.datasketches.ArrayOfLongsSerDe;
import org.apache.datasketches.ByteArrayUtil;
import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.testng.annotations.Test;

/**
 * Checks that a corrupt quantiles ItemsSketch image is rejected.
 * Adapted from upstream QuantilesItemsCorruptImageTest (apache/datasketches-java 4e024585d) to the 3.1.0
 * Memory API and class names (upstream QuantilesItemsSketch is ItemsSketch in 3.1.0).
 */
public class QuantilesItemsCorruptImageTest {
  private static final int N_LONG = 8;
  private final ArrayOfLongsSerDe serDe = new ArrayOfLongsSerDe();

  private byte[] image(final int n) {
    final ItemsSketch<Long> sk = ItemsSketch.getInstance(32768, Comparator.naturalOrder());
    for (long i = 0; i < n; i++) { sk.update(i); }
    return sk.toByteArray(serDe);
  }

  @Test
  public void checkLargeN() {
    final byte[] bytes = image(1);
    ByteArrayUtil.putLongLE(bytes, N_LONG, 1L << 60);
    final Memory mem = Memory.wrap(bytes);
    final long allocated = AllocationTracker.allocatedBytes(
        () -> ItemsSketch.getInstance(mem, Comparator.naturalOrder(), serDe));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
    expectThrows(SketchesArgumentException.class,
        () -> ItemsSketch.getInstance(mem, Comparator.naturalOrder(), serDe));
    final byte[] negative = image(1);
    ByteArrayUtil.putLongLE(negative, N_LONG, -1L);
    expectThrows(SketchesArgumentException.class,
        () -> ItemsSketch.getInstance(Memory.wrap(negative), Comparator.naturalOrder(), serDe));
  }

  @Test
  public void checkValidImagesStillWork() {
    for (final int n : new int[] {1, 100, 100_000}) {
      final ItemsSketch<Long> sk =
          ItemsSketch.getInstance(Memory.wrap(image(n)), Comparator.<Long>naturalOrder(), serDe);
      assertEquals(sk.getN(), n);
    }
  }
}
