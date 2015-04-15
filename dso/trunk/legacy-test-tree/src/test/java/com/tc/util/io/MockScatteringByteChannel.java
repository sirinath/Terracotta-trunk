/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package com.tc.util.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ScatteringByteChannel;

/**
 * dev-zero implementation of a readable channel.
 */
public class MockScatteringByteChannel extends MockReadableByteChannel implements ScatteringByteChannel {

  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    throw new IOException("Not yet implemented");
  }

  @Override
  public long read(ByteBuffer[] dsts) throws IOException {
    checkOpen();
    if (dsts == null) { throw new IOException("null ByteBuffer[] passed in to read(ByteBuffer[])"); }
    checkNull(dsts);
    long bytesRead = 0;
    for (int pos = 0; pos < dsts.length && bytesRead < getMaxReadCount(); ++pos) {
      ByteBuffer buffer = dsts[pos];
      while (buffer.hasRemaining() && bytesRead < getMaxReadCount()) {
        buffer.put((byte) 0x00);
        ++bytesRead;
      }
    }
    return bytesRead;
  }

  private void checkNull(ByteBuffer[] srcs) throws IOException {
    for (int pos = 0; pos < srcs.length; ++pos) {
      if (srcs[pos] == null) { throw new IOException("Null ByteBuffer at array position[" + pos + "]"); }
    }
  }
}
