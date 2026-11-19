/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.datasketches;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.Arrays;

import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class StringSerDeBoundsTest {
  @DataProvider(name = "stringSerDes")
  public Object[][] stringSerDes() {
    return new Object[][] {
        {new ArrayOfStringsSerDe()},
        {new ArrayOfUtf16StringsSerDe()}
    };
  }

  @Test(dataProvider = "stringSerDes")
  public void rejectInvalidLengthsBeforeAllocation(final ArrayOfItemsSerDe<String> serDe) {
    for (final int length : new int[] {-1, Integer.MAX_VALUE, 5}) {
      final byte[] bytes = new byte[Integer.BYTES];
      WritableMemory.writableWrap(bytes).putInt(0, length);
      final Memory mem = Memory.wrap(bytes);
      expectThrows(SketchesArgumentException.class,
          () -> serDe.deserializeFromMemory(mem, 1));
    }
  }

  @Test(dataProvider = "stringSerDes")
  public void rejectImpossibleCountsBeforeAllocation(final ArrayOfItemsSerDe<String> serDe) {
    final Memory mem = Memory.wrap(new byte[Integer.BYTES]);
    expectThrows(SketchesArgumentException.class,
        () -> serDe.deserializeFromMemory(mem, Integer.MAX_VALUE));
    expectThrows(SketchesArgumentException.class,
        () -> serDe.deserializeFromMemory(mem, 2));
    final long allocated = AllocationTracker.allocatedBytes(
        () -> serDe.deserializeFromMemory(mem, 1 << 28));
    assertTrue(allocated < (1 << 20), "allocated " + allocated);
  }

  @Test(dataProvider = "stringSerDes")
  public void rejectTruncatedPrefixes(final ArrayOfItemsSerDe<String> serDe) {
    for (int len = 0; len < Integer.BYTES; len++) {
      final Memory mem = Memory.wrap(new byte[len]);
      expectThrows(SketchesArgumentException.class,
          () -> serDe.deserializeFromMemory(mem, 1));
    }
  }

  @Test(dataProvider = "stringSerDes")
  public void preserveValidOffsetUnicodeAndOwnership(final ArrayOfItemsSerDe<String> serDe) {
    final String[] expected = {"", "hello", "日本語", "😀", "a\u0000b"};
    final byte[] encoded = serDe.serializeToByteArray(expected);
    final byte[] padded = new byte[3 + encoded.length + 1];
    System.arraycopy(encoded, 0, padded, 3, encoded.length);
    final Memory mem = Memory.wrap(padded).region(3, encoded.length + 1);
    final String[] actual = serDe.deserializeFromMemory(mem, expected.length);
    assertEquals(actual, expected);
    Arrays.fill(padded, (byte) 0);
    assertEquals(actual, expected);
  }

  @Test(dataProvider = "stringSerDes")
  public void preserveEmptyCount(final ArrayOfItemsSerDe<String> serDe) {
    assertEquals(serDe.deserializeFromMemory(Memory.wrap(new byte[0]), 0).length, 0);
  }
}
