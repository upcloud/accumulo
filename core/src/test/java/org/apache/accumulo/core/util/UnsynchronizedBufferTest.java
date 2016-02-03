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
package org.apache.accumulo.core.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UnsynchronizedBufferTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testByteBufferConstructor() {
    byte[] test = "0123456789".getBytes(UTF_8);

    ByteBuffer bb1 = ByteBuffer.wrap(test);
    UnsynchronizedBuffer.Reader ub = new UnsynchronizedBuffer.Reader(bb1);
    byte[] buf = new byte[10];
    ub.readBytes(buf);
    Assert.assertEquals("0123456789", new String(buf, UTF_8));

    ByteBuffer bb2 = ByteBuffer.wrap(test, 3, 5);

    ub = new UnsynchronizedBuffer.Reader(bb2);
    buf = new byte[5];
    // should read data from offset 3 where the byte buffer starts
    ub.readBytes(buf);
    Assert.assertEquals("34567", new String(buf, UTF_8));

    buf = new byte[6];
    // the byte buffer has the extra byte, but should not be able to read it...
    thrown.expect(ArrayIndexOutOfBoundsException.class);
    ub.readBytes(buf);
  }
}
