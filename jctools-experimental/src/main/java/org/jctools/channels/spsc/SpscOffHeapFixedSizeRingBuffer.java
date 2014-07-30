/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.channels.spsc;

import static org.jctools.util.UnsafeAccess.UNSAFE;
import static org.jctools.util.UnsafeDirectByteBuffer.CACHE_LINE_SIZE;
import static org.jctools.util.UnsafeDirectByteBuffer.alignedSlice;
import static org.jctools.util.UnsafeDirectByteBuffer.allocateAlignedByteBuffer;

import java.nio.ByteBuffer;

import org.jctools.util.Pow2;
import org.jctools.util.UnsafeDirectByteBuffer;

/**
 * Channel protocol:
 * - Fixed message size
 * - 'null' indicator in message preceding byte (potentially use same for type mapping in future)
 * - Use FF algorithm relying on indicator to support in place detection of next element existence
 * @author nitsanw
 *
 */
public class SpscOffHeapFixedSizeRingBuffer {
	private static final byte NULL_MESSAGE_INDICATOR = 0;
    public final static byte PRODUCER = 1;
	public final static byte CONSUMER = 2;
	public final static byte INIT = 4;
    public static final long EOF = 0;
    private static final Integer MAX_LOOK_AHEAD_STEP = Integer.getInteger("jctoolts.spsc.max.lookahead.step", 4096);
    
	protected final int lookAheadStep;
	// 24b,8b,8b,24b | pad | 24b,8b,8b,24b | pad
	private final ByteBuffer buffy;
	private final long consumerIndexAddress;
	private final long producerIndexAddress;
	private final long producerLookAheadCacheAddress;

	private final int capacity;
	private final int mask;
	private final long arrayBase;
	private final int messageSize;

	public static int getRequiredBufferSize(final int capacity, final int messageSize) {
        return 4 * CACHE_LINE_SIZE + (Pow2.roundToPowerOfTwo(capacity) * (messageSize+1));
    }

	public SpscOffHeapFixedSizeRingBuffer(final int capacity, final int messageSize) {
		this(allocateAlignedByteBuffer(
		        getRequiredBufferSize(capacity, messageSize),
		        CACHE_LINE_SIZE),
		        Pow2.roundToPowerOfTwo(capacity),(byte)(PRODUCER | CONSUMER | INIT), messageSize);
	}
	
	/**
	 * This is to be used for an IPC queue with the direct buffer used being a memory
	 * mapped file.
	 * 
	 * @param buff
	 * @param capacity
	 * @param viewMask 
	 */
	private SpscOffHeapFixedSizeRingBuffer(final ByteBuffer buff, 
			final int capacity, final byte viewMask, final int messageSize) {
		this.capacity = Pow2.roundToPowerOfTwo(capacity);
		this.messageSize = messageSize +1;
		buffy = alignedSlice(4 * CACHE_LINE_SIZE + (this.capacity * (this.messageSize)), 
									CACHE_LINE_SIZE, buff);

		long alignedAddress = UnsafeDirectByteBuffer.getAddress(buffy);
		lookAheadStep = Math.min(this.capacity/4, MAX_LOOK_AHEAD_STEP);
		consumerIndexAddress = alignedAddress;
		producerIndexAddress = consumerIndexAddress + 2 * CACHE_LINE_SIZE;
		producerLookAheadCacheAddress = producerIndexAddress + 8;
		arrayBase = alignedAddress + 4 * CACHE_LINE_SIZE;
		// producer owns tail and headCache
		if((viewMask & (PRODUCER | INIT)) == (PRODUCER | INIT)){
    		spLookAheadCache(0);
    		soProducerIndex(0);
		}
		// consumer owns head and tailCache 
		if((viewMask & (CONSUMER | INIT)) == (CONSUMER| INIT)){
			soConsumerIndex(0);
		}
		mask = this.capacity - 1;
        if ((viewMask & INIT) == INIT) {
        	// mark all messages as null
            for (int i=0;i< this.capacity;i++) {
                final long offset = offsetForIdx(i);
                UNSAFE.putByte(offset, NULL_MESSAGE_INDICATOR);
            }
        }
	}

	protected final long writeAcquire() {
		final long producerIndex = lpProducerIndex();
		final long producerLookAhead = lpLookAheadCache();
		// verify next lookAheadStep messages are clear to write
		if(producerIndex >= producerLookAhead) {
			final long nextLookAhead = producerIndex + lookAheadStep;
			if(UNSAFE.getByteVolatile(null, offsetForIdx(nextLookAhead)) != NULL_MESSAGE_INDICATOR) {
	            return EOF;
	        }
			spLookAheadCache(nextLookAhead);
		}
		// return offset for current producer index
        return offsetForIdx(producerIndex);
	}
	protected final void writeRelease(long offset) {
        final long currentProducerIndex = lpProducerIndex();
        // ideally we would have used the byte write as a barrier, but there's no ordered write for byte
        // we could consider using an integer indicator instead of a byte which would also improve likelihood
        // of aligned writes.
        UNSAFE.putByte(offset, (byte)1);
        soProducerIndex(currentProducerIndex + 1); // StoreStore
    }
	
	protected final long readAcquire() {
		final long currentHead = lpConsumerIndex();
		final long offset = offsetForIdx(currentHead);
		if(UNSAFE.getByteVolatile(null, offset) == NULL_MESSAGE_INDICATOR) {
		    return EOF;
		}
		return offset;
    }
	
	protected final void readRelease(long offset) {
	    final long currentHead = lpConsumerIndex();
	    UNSAFE.putByte(offset, NULL_MESSAGE_INDICATOR);
	    soConsumerIndex(currentHead + 1); // StoreStore
	}
    private long offsetForIdx(final long currentHead) {
        return arrayBase + ((currentHead & mask) * messageSize);
    }

	public final int size() {
		return (int) (lvProducerIndex() - lvConsumerIndex());
	}

	public final boolean isEmpty() {
		return lvProducerIndex() == lvConsumerIndex();
	}
	
	private long lpConsumerIndex() {
		return UNSAFE.getLong(null, consumerIndexAddress);
	}
	private long lvConsumerIndex() {
		return UNSAFE.getLongVolatile(null, consumerIndexAddress);
	}

	private void soConsumerIndex(final long value) {
		UNSAFE.putOrderedLong(null, consumerIndexAddress, value);
	}

	private long lpProducerIndex() {
		return UNSAFE.getLong(null, producerIndexAddress);
	}
	private long lvProducerIndex() {
		return UNSAFE.getLongVolatile(null, producerIndexAddress);
	}

	private void soProducerIndex(final long value) {
		UNSAFE.putOrderedLong(null, producerIndexAddress, value);
	}

	private long lpLookAheadCache() {
		return UNSAFE.getLong(null, producerLookAheadCacheAddress);
	}

	private void spLookAheadCache(final long value) {
		UNSAFE.putLong(producerLookAheadCacheAddress, value);
	}
}