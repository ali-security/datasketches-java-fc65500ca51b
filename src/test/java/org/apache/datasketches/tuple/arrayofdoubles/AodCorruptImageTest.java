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
package org.apache.datasketches.tuple.arrayofdoubles;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import org.apache.datasketches.AllocationTracker;
import org.apache.datasketches.ByteArrayUtil;
import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;
import org.testng.annotations.Test;

/**
 * Checks that corrupt ArrayOfDoubles sketch and union images are rejected without large allocations,
 * including when the result of a wrap is used later.
 * Ported from upstream 55d9b770e (AodCorruptImageTest) to the 3.1.0 Memory API.
 */
public class AodCorruptImageTest {
  private static final int NUM_VALUES_BYTE = 5;
  private static final int COMPACT_COUNT_INT = 16;
  private static final int QS_LG_NOM_BYTE = 16;
  private static final int QS_LG_CUR_BYTE = 17;
  private static final int QS_COUNT_INT = 24;
  private static final int UNION_PREAMBLE = 16;

  private static ArrayOfDoublesUpdatableSketch sketch(final int n) {
    final ArrayOfDoublesUpdatableSketch sk = new ArrayOfDoublesUpdatableSketchBuilder().setNumberOfValues(2).build();
    for (int i = 0; i < n; i++) { sk.update(i, new double[] {1.0, 2.0}); }
    return sk;
  }

  private static byte[] unionImage() {
    final ArrayOfDoublesUnion union = new ArrayOfDoublesSetOperationBuilder().setNumberOfValues(2).buildUnion();
    union.union(sketch(10));
    return union.toByteArray();
  }

  /** A deserialization of an image, whose result is not needed. */
  private interface Deser {
    void run(WritableMemory mem);
  }

  private static void checkRejected(final byte[] bytes, final Deser deser) {
    final WritableMemory mem = WritableMemory.writableWrap(bytes);
    final long allocated = AllocationTracker.allocatedBytes(() -> deser.run(mem));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
    expectThrows(SketchesArgumentException.class, () -> deser.run(mem));
  }

  @Test
  public void checkValidImagesStillWork() {
    final ArrayOfDoublesUpdatableSketch sk = sketch(100);
    final byte[] c = sk.compact().toByteArray();
    assertEquals(ArrayOfDoublesSketches.heapifySketch(Memory.wrap(c)).getRetainedEntries(), 100);
    assertEquals(ArrayOfDoublesSketches.wrapSketch(Memory.wrap(c)).getRetainedEntries(), 100);
    final byte[] u = sk.toByteArray();
    assertEquals(ArrayOfDoublesSketches.heapifyUpdatableSketch(Memory.wrap(u)).getRetainedEntries(), 100);
    assertEquals(ArrayOfDoublesSketches.wrapUpdatableSketch(WritableMemory.writableWrap(u)).getRetainedEntries(),
        100);
    assertEquals(ArrayOfDoublesUnion.heapify(Memory.wrap(unionImage())).getResult().getRetainedEntries(), 10);
    assertEquals(ArrayOfDoublesUnion.wrap(Memory.wrap(unionImage())).getResult().getRetainedEntries(), 10);
    assertEquals(ArrayOfDoublesUnion.wrap(WritableMemory.writableWrap(unionImage())).getResult()
        .getRetainedEntries(), 10);
    final byte[] e = sketch(0).compact().toByteArray();
    assertTrue(ArrayOfDoublesSketches.heapifySketch(Memory.wrap(e)).isEmpty());
    assertTrue(ArrayOfDoublesSketches.wrapSketch(Memory.wrap(e)).isEmpty());
  }

  @Test
  public void checkSmallNominalEntriesStillWork() {
    //for nominal entries below 16 the table starts at the minimum size, which is more than twice nominal.
    //The set operation builder, unlike the sketch builder, accepts nominal entries below 16.
    for (final int k : new int[] {1, 2, 4, 8, 16, 32, 100}) {
      for (final int n : new int[] {0, 1, 3, 100}) {
        final ArrayOfDoublesSetOperationBuilder bldr =
            new ArrayOfDoublesSetOperationBuilder().setNominalEntries(k).setNumberOfValues(2);
        final ArrayOfDoublesUnion heapUnion = bldr.buildUnion();
        final ArrayOfDoublesUnion directUnion = bldr.buildUnion(WritableMemory.writableWrap(new byte[1 << 16]));
        heapUnion.union(sketch(n));
        directUnion.union(sketch(n));
        //a direct union with nominal entries that are not a power of 2 (k = 100) keeps fewer entries than a
        //heap union in 3.1.0, so each image is compared with the union it came from
        for (final ArrayOfDoublesUnion union : new ArrayOfDoublesUnion[] {heapUnion, directUnion}) {
          final int expected = union.getResult().getRetainedEntries();
          final byte[] u = union.toByteArray();
          assertEquals(ArrayOfDoublesUnion.heapify(Memory.wrap(u)).getResult().getRetainedEntries(), expected);
          assertEquals(ArrayOfDoublesUnion.wrap(Memory.wrap(u)).getResult().getRetainedEntries(), expected);
        }
      }
    }
  }

  @Test
  public void checkCompactCountBeyondImage() {
    final byte[] bytes = sketch(10).compact().toByteArray();
    ByteArrayUtil.putIntLE(bytes, COMPACT_COUNT_INT, 1 << 26);
    checkRejected(bytes, ArrayOfDoublesSketches::heapifySketch);
    checkRejected(bytes, mem -> ArrayOfDoublesSketches.wrapSketch(mem).getValues());
    final byte[] negative = sketch(10).compact().toByteArray();
    ByteArrayUtil.putIntLE(negative, COMPACT_COUNT_INT, -1);
    checkRejected(negative, ArrayOfDoublesSketches::heapifySketch);
  }

  @Test
  public void checkCompactNumValues() {
    final byte[] bytes = sketch(10).compact().toByteArray();
    bytes[NUM_VALUES_BYTE] = (byte) 127; //more values than the image holds
    checkRejected(bytes, ArrayOfDoublesSketches::heapifySketch);
    bytes[NUM_VALUES_BYTE] = (byte) -1;
    checkRejected(bytes, ArrayOfDoublesSketches::heapifySketch);
    checkRejected(bytes, ArrayOfDoublesSketches::wrapSketch);
  }

  @Test
  public void checkUpdatableLargeTable() {
    final byte[] bytes = sketch(10).toByteArray();
    bytes[QS_LG_NOM_BYTE] = 26;
    bytes[QS_LG_CUR_BYTE] = 27; //the image does not hold this table
    checkRejected(bytes, ArrayOfDoublesSketches::heapifyUpdatableSketch);
    checkRejected(bytes, mem -> ArrayOfDoublesSketches.wrapUpdatableSketch(mem).getValues());
    final byte[] bytes2 = sketch(10).toByteArray();
    bytes2[QS_LG_CUR_BYTE] = (byte) (bytes2[QS_LG_NOM_BYTE] + 2);
    checkRejected(bytes2, ArrayOfDoublesSketches::heapifyUpdatableSketch);
  }

  @Test
  public void checkUpdatableCount() {
    final byte[] bytes = sketch(10).toByteArray();
    ByteArrayUtil.putIntLE(bytes, QS_COUNT_INT, 1 << 26); //more than the table holds
    checkRejected(bytes, ArrayOfDoublesSketches::heapifyUpdatableSketch);
    checkRejected(bytes, mem -> ArrayOfDoublesSketches.wrapUpdatableSketch(mem).getValues());
  }

  @Test
  public void checkUnion() {
    final byte[] bytes = unionImage();
    bytes[UNION_PREAMBLE + QS_LG_NOM_BYTE] = 26;
    bytes[UNION_PREAMBLE + QS_LG_CUR_BYTE] = 27;
    checkRejected(bytes, ArrayOfDoublesUnion::heapify);
    checkRejected(bytes, mem -> ArrayOfDoublesUnion.wrap(mem).getResult());
    final byte[] bytes2 = unionImage();
    ByteArrayUtil.putIntLE(bytes2, UNION_PREAMBLE + QS_COUNT_INT, 1 << 26);
    checkRejected(bytes2, ArrayOfDoublesUnion::heapify);
    checkRejected(bytes2, mem -> ArrayOfDoublesUnion.wrap(mem).getResult());
  }
}
