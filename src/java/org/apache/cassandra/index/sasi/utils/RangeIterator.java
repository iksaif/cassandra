/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.index.sasi.utils;

import java.io.Closeable;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.google.common.annotations.VisibleForTesting;

public abstract class RangeIterator<K extends Comparable<K>, T extends CombinedValue<K>> extends AbstractIterator<T> implements Closeable
{
    private final K min, max;
    private final long count;
    private K current;

    protected RangeIterator(Builder.Statistics<K, T> statistics)
    {
        this(statistics.min, statistics.max, statistics.tokenCount);
    }

    public RangeIterator(RangeIterator<K, T> range)
    {
        this(range.min, range.max, range.count);
    }

    public RangeIterator(K min, K max, long count)
    {
        this.min = min;
        this.current = min;
        this.max = max;
        this.count = count;
    }

    public RangeIterator() {
        // And empty range.
        this.min = this.max = this.current = null;
        this.count = 0;
    }

    public final K getMinimum()
    {
        return min;
    }

    public final K getCurrent()
    {
        return current;
    }

    public final K getMaximum()
    {
        return max;
    }

    public final long getCount()
    {
        return count;
    }

    public final boolean isEmpty() { return count == 0; }

    /**
     * When called, this iterators current position should
     * be skipped forwards until finding either:
     *   1) an element equal to or bigger than next
     *   2) the end of the iterator
     *
     * @param nextToken value to skip the iterator forward until matching
     *
     * @return The next current token after the skip was performed
     */
    public final T skipTo(K nextToken)
    {
        if (min == null || max == null)
            return endOfData();

        if (current.compareTo(nextToken) >= 0)
            return next == null ? recomputeNext() : next;

        if (max.compareTo(nextToken) < 0)
            return endOfData();

        performSkipTo(nextToken);
        return recomputeNext();
    }

    protected abstract void performSkipTo(K nextToken);

    protected T recomputeNext()
    {
        return tryToComputeNext() ? peek() : endOfData();
    }

    protected boolean tryToComputeNext()
    {
        boolean hasNext = super.tryToComputeNext();
        current = hasNext ? next.get() : getMaximum();
        return hasNext;
    }

    public static abstract class Builder<K extends Comparable<K>, D extends CombinedValue<K>>
    {
        public enum IteratorType
        {
            UNION, INTERSECTION
        }

        @VisibleForTesting
        protected final Statistics<K, D> statistics;

        @VisibleForTesting
        protected final PriorityQueue<RangeIterator<K, D>> ranges;

        public Builder(IteratorType type)
        {
            statistics = new Statistics<>(type);
            ranges = new PriorityQueue<>(16,
                    // A comparator that works with empty ranges.
                    (a, b) -> (
                            a.isEmpty() && b.isEmpty() ? 0 :
                            a.isEmpty() ? Integer.MIN_VALUE :
                            b.isEmpty() ? Integer.MAX_VALUE :
                            a.getCurrent().compareTo(b.getCurrent()))
            );
        }

        public K getMinimum()
        {
            return statistics.min;
        }

        public K getMaximum()
        {
            return statistics.max;
        }

        public long getTokenCount()
        {
            return statistics.tokenCount;
        }

        public int rangeCount()
        {
            return ranges.size();
        }

        public Builder<K, D> add(RangeIterator<K, D> range)
        {
            if (range == null)
                return this;

            ranges.add(range);
            statistics.update(range);

            return this;
        }

        public Builder<K, D> add(List<RangeIterator<K, D>> ranges)
        {
            if (ranges == null)
                return this;

            ranges.forEach(this::add);
            return this;
        }

        public final RangeIterator<K, D> build()
        {
            switch (rangeCount())
            {
                case 0:
                    return new EmptyRangeIterator();

                case 1:
                    return ranges.poll();

                default:
                    return buildIterator();
            }
        }

        private class EmptyRangeIterator<K extends Comparable<K>, D extends CombinedValue<K>> extends RangeIterator<K, D>
        {
            EmptyRangeIterator() { }
            public D computeNext() { return endOfData(); }
            protected void performSkipTo(K nextToken) { }
            public void close() { }
        }

        protected abstract RangeIterator<K, D> buildIterator();

        public static class Statistics<K extends Comparable<K>, D extends CombinedValue<K>>
        {
            protected final IteratorType iteratorType;

            protected K min, max;
            protected long tokenCount;

            // iterator with the least number of items
            protected RangeIterator<K, D> minRange;
            // iterator with the most number of items
            protected RangeIterator<K, D> maxRange;

            // tracks if all of the added ranges overlap, which is useful in case of intersection,
            // as it gives direct answer as to such iterator is going to produce any results.
            protected boolean isOverlapping = true;

            protected int rangesCount;

            public Statistics(IteratorType iteratorType)
            {
                this.iteratorType = iteratorType;
                this.rangesCount = 0;
            }

            /**
             * Update statistics information with the given range.
             *
             * Updates min/max of the combined range, token count and
             * tracks range with the least/most number of tokens.
             *
             * @param range The range to update statistics with.
             */
            public void update(RangeIterator<K, D> range)
            {
                // The first update is easy and special. This makes the rest of the code simpler.
                if (rangesCount == 0) {
                    min = range.getMinimum();
                    max = range.getMaximum();
                    minRange = maxRange = range;
                    tokenCount = range.getCount();
                    rangesCount = 1;
                    isOverlapping = !range.isEmpty();
                    return;
                }

                switch (iteratorType)
                {
                    case UNION:
                        if (!range.isEmpty()) {
                            min = min == null || min.compareTo(range.getMinimum()) > 0 ? range.getMinimum() : min;
                            max = max == null || max.compareTo(range.getMaximum()) < 0 ? range.getMaximum() : max;
                        }
                        break;

                    case INTERSECTION:
                        if (!range.isEmpty()) {
                            // Note: in case of non-overlapping intersection these are irrelevant.
                            // minimum of the intersection is the biggest minimum of individual iterators
                            min = min == null || min.compareTo(range.getMinimum()) < 0 ? range.getMinimum() : min;
                            // maximum of the intersection is the smallest maximum of individual iterators
                            max = max == null || max.compareTo(range.getMaximum()) > 0 ? range.getMaximum() : max;
                        }
                        break;

                    default:
                        throw new IllegalStateException("Unknown iterator type: " + iteratorType);
                }

                // check if new range is disjoint with already added ranges, which means that this intersection
                // is not going to produce any results, so we can cleanup range storage and never added anything to it.
                isOverlapping &= isOverlapping(min, max, range);

                minRange = min(minRange, range);
                maxRange = max(maxRange, range);

                if (!isOverlapping && iteratorType == IteratorType.INTERSECTION)
                    tokenCount = 0;
                else
                    tokenCount += range.getCount();

                rangesCount += 1;
            }

            private RangeIterator<K, D> min(RangeIterator<K, D> a, RangeIterator<K, D> b)
            {
                if (a.isEmpty()) return a;
                if (b.isEmpty()) return b;
                return a.getCount() > b.getCount() ? b : a;
            }

            private RangeIterator<K, D> max(RangeIterator<K, D> a, RangeIterator<K, D> b)
            {
                if (a.isEmpty()) return b;
                if (b.isEmpty()) return a;
                return a.getCount() > b.getCount() ? a : b;
            }

            public boolean isDisjoint()
            {
                return !isOverlapping;
            }

            public double sizeRatio()
            {
                double minCount = (minRange == null ? 0 : minRange.getCount()) * 1d;
                if (maxRange == null)
                    return 0;
                return minCount / maxRange.getCount();
            }
        }
    }

    @VisibleForTesting
    protected static <K extends Comparable<K>, D extends CombinedValue<K>> boolean isOverlapping(RangeIterator<K, D> a, RangeIterator<K, D> b)
    {
        if (a.isEmpty() || b.isEmpty())
            return false;
        return isOverlapping(a.getCurrent(), a.getMaximum(), b);
    }

    @VisibleForTesting
    protected static <K extends Comparable<K>, D extends CombinedValue<K>> boolean isOverlapping(K min, K max, RangeIterator<K, D> b)
    {
        if ((min == null && max == null) || b.isEmpty())
            return false;
        return min.compareTo(b.getMaximum()) <= 0 && b.getCurrent().compareTo(max) <= 0;
    }
}
