/*
 * Copyright 2011 LMAX Ltd.
 *
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
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


/**
 * 线程安全
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.</p>
 *
 * <p> * Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.</p>
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final Unsafe UNSAFE = Util.getUnsafe();
    //int[]数组对象第一个元素内存偏移量
    private static final long BASE = UNSAFE.arrayBaseOffset(int[].class);
    //int[]数组对象一个元素内存字节大小
    private static final long SCALE = UNSAFE.arrayIndexScale(int[].class);
    //消费者集合最低的已消费的Sequence
    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    //可用性buffer数组用于ringbuffer状态跟踪
    private final int[] availableBuffer;
    //=bufferSize - 1
    private final int indexMask;
    //=Util.log2(bufferSize)
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        indexMask = bufferSize - 1;
        indexShift = Util.log2(bufferSize);
        initialiseAvailableBuffer();
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    private boolean hasAvailableCapacity(Sequence[] gatingSequences, final int requiredCapacity, long cursorValue)
    {
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
            gatingSequenceCache.set(minSequence);

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        cursor.set(sequence);
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;
            //最高可发布sequence值阈值A<Min(消费者集合已消费的最低sequence值)+bufferSize，不能套娃。nextSequence表示阈值A
            long wrapPoint = next - bufferSize;
            long cachedGatingSequence = gatingSequenceCache.get();
            //第二个条件'cachedGatingSequence > current'基本不可能命中
            if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
            {
                long gatingSequence = Util.getMinimumSequence(gatingSequences, current);

                if (wrapPoint > gatingSequence)
                {
                    LockSupport.parkNanos(1); // TODO, should we spin based on the wait strategy?
                    continue;
                }
                //更新消费者最低已消费sequence
                gatingSequenceCache.set(gatingSequence);
            }
            //在next方法内部就已经更新cursor,考虑这样子的场景:cursor已更新，但是事件还未覆盖填充，就被消费者消费了，应该是有机制防止这种场景 yes
            //消费者端获取getHighestPublishedSequence()中就用availableBuffer数组isAvailable方法做了判断，防止未填充完成的事件被提前消费
            //CAS原子更新，因为可能多个生产者线程并发修改cursor
            else if (cursor.compareAndSet(current, next))
            {
                break;
            }
        }
        while (true);

        return next;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        //最低已消费sequence值
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        //已发布sequence值
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    private void initialiseAvailableBuffer()
    {
        for (int i = availableBuffer.length - 1; i != 0; i--)
        {
            setAvailableBufferValue(i, -1);
        }

        setAvailableBufferValue(0, -1);
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * The below methods work on the availableBuffer flag.
     * <p>
     * The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     * <p>
     * --  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     */
    private void setAvailable(final long sequence)
    {
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    /**
     * 设置指定数组buffer下标为flag值
     */
    private void setAvailableBufferValue(int index, int flag)
    {
        long bufferAddress = (index * SCALE) + BASE;
        UNSAFE.putOrderedInt(availableBuffer, bufferAddress, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        //buffer数组下标
        int index = calculateIndex(sequence);
        //计算sequence对应的flag值
        int flag = calculateAvailabilityFlag(sequence);
        //buffer数组下标偏移量
        long bufferAddress = (index * SCALE) + BASE;
        return UNSAFE.getIntVolatile(availableBuffer, bufferAddress) == flag;
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }

        return availableSequence;
    }

    private int calculateAvailabilityFlag(final long sequence)
    {
        //无符号右移
        return (int) (sequence >>> indexShift);
    }

    private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }
}
