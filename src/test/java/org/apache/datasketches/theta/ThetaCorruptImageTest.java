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

package org.apache.datasketches.theta;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.apache.datasketches.AllocationTracker;
import org.apache.datasketches.ByteArrayUtil;
import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;
import org.testng.annotations.Test;

/**
 * Checks that corrupt theta sketch, union and intersection images are rejected without large
 * allocations, including when the result of a wrap is used later.
 */
public class ThetaCorruptImageTest {
  private static final int SER_VER_BYTE = 1;
  private static final int LG_ARR_BYTE = 4;
  private static final int CUR_COUNT_INT = 8;

  /** Deserializes, and possibly uses, an image. */
  private interface Deser {
    Object apply(WritableMemory mem);
  }

  private static UpdateSketch sketch(final int n) {
    final UpdateSketch sk = UpdateSketch.builder().build();
    for (int i = 0; i < n; i++) { sk.update(i); }
    return sk;
  }

  private static void checkRejected(final byte[] bytes, final Deser deser) {
    final long allocated = AllocationTracker.allocatedBytes(() -> deser.apply(WritableMemory.writableWrap(bytes)));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
    try {
      deser.apply(WritableMemory.writableWrap(bytes));
      fail("expected SketchesArgumentException");
    } catch (final SketchesArgumentException e) {
      //expected
    }
  }

  @Test
  public void checkValidImagesStillWork() {
    final UpdateSketch sk = sketch(1000);
    final byte[] c = sk.compact().toByteArray();
    assertEquals(CompactSketch.heapify(Memory.wrap(c)).getRetainedEntries(), 1000);
    assertEquals(CompactSketch.wrap(Memory.wrap(c)).getRetainedEntries(), 1000);
    assertEquals(CompactSketch.wrap(Memory.wrap(c)).compact().getRetainedEntries(), 1000);
    assertEquals(UpdateSketch.heapify(Memory.wrap(sk.toByteArray())).getRetainedEntries(), 1000);
    assertEquals(UpdateSketch.wrap(WritableMemory.writableWrap(sk.toByteArray())).getRetainedEntries(), 1000);
    final Union union = SetOperation.builder().buildUnion();
    union.union(sk);
    assertEquals(((Union) SetOperation.heapify(Memory.wrap(union.toByteArray())))
        .getResult().getRetainedEntries(), 1000);
    assertEquals(((Union) SetOperation.wrap(Memory.wrap(union.toByteArray())))
        .getResult().getRetainedEntries(), 1000);
    final Intersection inter = SetOperation.builder().buildIntersection();
    inter.intersect(sk);
    assertEquals(((Intersection) SetOperation.heapify(Memory.wrap(inter.toByteArray())))
        .getResult().getRetainedEntries(), 1000);
    assertEquals(((Intersection) SetOperation.wrap(Memory.wrap(inter.toByteArray())))
        .getResult().getRetainedEntries(), 1000);
    final Intersection fresh = SetOperation.builder().buildIntersection();
    assertTrue(SetOperation.heapify(Memory.wrap(fresh.toByteArray())) instanceof Intersection);
  }

  @Test
  public void checkCompactCountBeyondImage() {
    final byte[] bytes = sketch(100).compact().toByteArray();
    ByteArrayUtil.putIntLE(bytes, CUR_COUNT_INT, 1 << 28);
    checkRejected(bytes, CompactSketch::heapify);
    checkRejected(bytes, Sketch::heapify);
    checkRejected(bytes, m -> CompactSketch.wrap(m).getCache());
    checkRejected(bytes, m -> Sketch.wrap(m).compact());
    final byte[] negative = sketch(100).compact().toByteArray();
    ByteArrayUtil.putIntLE(negative, CUR_COUNT_INT, -1);
    checkRejected(negative, CompactSketch::heapify);
    checkRejected(negative, CompactSketch::wrap);
  }

  @Test
  public void checkSerVer2CountBeyondImage() {
    //SerVer 2 images are read by ForwardCompatibility; (curCount + preLongs) << 3 overflowed int
    final byte[] bytes = sketch(100).compact().toByteArray();
    bytes[SER_VER_BYTE] = 2;
    ByteArrayUtil.putIntLE(bytes, CUR_COUNT_INT, 0x20000000);
    checkRejected(bytes, CompactSketch::heapify);
    checkRejected(bytes, CompactSketch::wrap);
    final byte[] negative = sketch(100).compact().toByteArray();
    negative[SER_VER_BYTE] = 2;
    ByteArrayUtil.putIntLE(negative, CUR_COUNT_INT, -1);
    checkRejected(negative, CompactSketch::heapify);
  }

  @Test
  public void checkUpdatableLargeTable() {
    final byte[] bytes = sketch(100).toByteArray();
    bytes[LG_ARR_BYTE] = 30; //(8 << 30) overflows int
    checkRejected(bytes, UpdateSketch::heapify);
    checkRejected(bytes, Sketch::heapify);
    checkRejected(bytes, UpdateSketch::wrap);
    checkRejected(bytes, Sketch::wrap);
    final byte[] count = sketch(100).toByteArray();
    ByteArrayUtil.putIntLE(count, CUR_COUNT_INT, 1 << 28);
    checkRejected(count, UpdateSketch::heapify);
    checkRejected(count, UpdateSketch::wrap);
  }

  @Test
  public void checkUnionLargeTable() {
    final Union union = SetOperation.builder().buildUnion();
    union.union(sketch(100));
    final byte[] bytes = union.toByteArray();
    bytes[LG_ARR_BYTE] = 30;
    checkRejected(bytes, SetOperation::heapify);
    checkRejected(bytes, m -> ((Union) SetOperation.wrap((Memory) m)).getResult());
    checkRejected(bytes, m -> ((Union) SetOperation.wrap(m)).getResult());
  }

  @Test
  public void checkIntersectionLargeTable() {
    final Intersection inter = SetOperation.builder().buildIntersection();
    inter.intersect(sketch(100));
    final byte[] bytes = inter.toByteArray();
    bytes[LG_ARR_BYTE] = 30;
    checkRejected(bytes, SetOperation::heapify);
    checkRejected(bytes, m -> ((Intersection) SetOperation.wrap((Memory) m)).getResult());
    checkRejected(bytes, m -> ((Intersection) SetOperation.wrap(m)).getResult());
    final byte[] count = inter.toByteArray();
    ByteArrayUtil.putIntLE(count, CUR_COUNT_INT, 1 << 20); //more than the table holds
    checkRejected(count, SetOperation::heapify);
  }
}
