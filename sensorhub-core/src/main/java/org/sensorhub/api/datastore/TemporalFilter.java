/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore;

import java.time.Duration;
import java.time.Instant;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import com.google.common.collect.Range;


/**
 * <p>
 * Special case of range filter for temporal values.<br/>
 * Filtering is possible by time range, time instant, or special cases of
 * 'current time' and 'latest time'.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 29, 2019
 */
public class TemporalFilter extends RangeFilter<Instant>
{
    public static final Duration CURRENT_TIME_TOLERANCE_DEFAULT = Duration.ofMillis(1000);
    private static final Range<Instant> CURRENT_TIME_MARKER = Range.singleton(Instant.MIN);
    
    protected boolean timeRangeBeginsNow; // now = current time at the time of query evaluation
    protected boolean timeRangeEndsNow; // now = current time at the time of query evaluation
    protected boolean latestTime; // latest available time (can be in future)
    protected boolean descendingOrder;
    
    protected TemporalFilter() {
        this.range = Range.open(Instant.MIN, Instant.MAX);
    }


    public boolean isCurrentTime()
    {
        return timeRangeBeginsNow && timeRangeEndsNow;
    }
    
    
    public boolean isLatestTime()
    {
        return latestTime;
    }
    
    
    public boolean isAllTimes()
    {
        return getMin() == Instant.MIN && getMax() == Instant.MAX;
    }
    
    
    public boolean beginsNow()
    {
        return timeRangeBeginsNow;
    }
    
    
    public boolean endsNow()
    {
        return timeRangeEndsNow;
    }
    
    
    public boolean isSingleValue()
    {
        if (timeRangeBeginsNow ^ timeRangeEndsNow)
            return false;
        
        return isCurrentTime() || isLatestTime() || super.isSingleValue();
    }


    public boolean descendingOrder()
    {
        return descendingOrder;
    }

    
    @Override
    public Range<Instant> getRange()
    {
        // handle cases of ranges relative to current time
        if (isCurrentTime() && range == CURRENT_TIME_MARKER)
        {
            var now = Instant.now();
            range = Range.singleton(now);
        }        
        else if (timeRangeBeginsNow && range.lowerEndpoint() == Instant.MIN)
        {
            var now = Instant.now();
            range = Range.closed(now, range.upperEndpoint());
        }        
        else if (timeRangeEndsNow && range.upperEndpoint() == Instant.MAX)
        {
            var now = Instant.now();
            range = Range.closed(range.lowerEndpoint(), now);
        }
        
        return range;
    }
    
    
    @Override
    public Instant getMin()
    {
        return getRange().lowerEndpoint();
    }
    
    
    @Override
    public Instant getMax()
    {
        return getRange().upperEndpoint();
    }
    
    
    public TimeExtent asTimeExtent()
    {
        if (latestTime)
            throw new IllegalStateException("Cannot convert 'latestTime' to a TimeExtent");
        
        var begin = getMin();
        var end = getMax();
        
        if (beginsNow() && endsNow())
            return TimeExtent.now();
        else if (beginsNow())
            return TimeExtent.beginNow(end);
        else if (endsNow())
            return TimeExtent.endNow(begin);
        else
            return TimeExtent.period(begin, end);
    }
    
    
    @Override
    public boolean test(Instant val)
    {
        // we always return true if set to latestTime or currentTime since
        // the outcome depends on other elements in the time series for which
        // we cannot maintain state here. It means that the caller must
        // enforce the latestTime/currentTime filter contract via other means
        if (latestTime || isCurrentTime())
            return true;
        
        var range = getRange();               
        switch (op)
        {
            case EQUALS:
                return range.lowerEndpoint().equals(val) &&
                       range.upperEndpoint().equals(val);
            default:
                return range.contains(val);
        }
    }
    
    
    public boolean test(TimeExtent te)
    {
        // we always return true if set to Time since the outcome depends on
        // other elements in the time series and we cannot maintain state here.
        // It means that the caller must enforce the latestTime filter
        // contract via other means
        if (latestTime)
            return true;
        
        var range = getRange();               
        switch (op)
        {
            case CONTAINS:
                return range.lowerEndpoint().compareTo(te.begin()) <= 0 &&
                       range.upperEndpoint().compareTo(te.end()) >= 0;
            case EQUALS:
                return range.lowerEndpoint().equals(te.begin()) &&
                       range.upperEndpoint().equals(te.end());
            default:
                return range.lowerEndpoint().compareTo(te.end()) <= 0 &&
                       range.upperEndpoint().compareTo(te.begin()) >= 0;
        }
    }
    
    
    /**
     * Computes the intersection (logical AND) between this filter and another filter of the same kind
     * @param filter The other filter to AND with
     * @return The new composite filter
     * @throws EmptyFilterIntersection if the intersection doesn't exist
     */
    public TemporalFilter intersect(TemporalFilter filter) throws EmptyFilterIntersection
    {
        if (filter == null)
            return this;
        return intersect(filter, new Builder()).build();
    }
    
    
    protected <B extends TimeFilterBuilder<B, TemporalFilter>> B intersect(TemporalFilter otherFilter, B builder) throws EmptyFilterIntersection
    {
        // handle latest time special case
        if (otherFilter.isLatestTime() && isLatestTime())
            return builder.withLatestTime();
        else if (otherFilter.isLatestTime() && isAllTimes())
            return builder.withLatestTime();
        else if (otherFilter.isAllTimes() && isLatestTime())
            return builder.withLatestTime();

        var descendingOrder = this.descendingOrder || otherFilter.descendingOrder;
        builder.descendingOrder(descendingOrder);

        // otherwise compute time extent intersection
        var thisTe = asTimeExtent();
        var otherTe = otherFilter.asTimeExtent();
        var xTe = TimeExtent.intersection(thisTe, otherTe);
        if (xTe == null)
            throw new EmptyFilterIntersection();
        return builder.fromTimeExtent(xTe);
    }
    
    
    @Override
    public String toString()
    {
        if (isCurrentTime())
            return "current";
        else if (isLatestTime())
            return "latest";
        else
            return super.toString();
    }
    
    
    /*
     * Builder
     */
    public static class Builder extends TimeFilterBuilder<Builder, TemporalFilter>
    {
        public Builder()
        {
            super(new TemporalFilter());
        }
        
        /**
         * Builds a new filter using the provided filter as a base
         * @param base Filter used as base
         * @return The new builder
         */
        public static Builder from(TemporalFilter base)
        {
            return new Builder().copyFrom(base);
        }
    }
    
    
    /*
     * Nested builder for use within another builder
     */
    public static abstract class NestedBuilder<B> extends TimeFilterBuilder<NestedBuilder<B>, TemporalFilter>
    {
        B parent;
        
        public NestedBuilder(B parent)
        {
            super(new TemporalFilter());
            this.parent = parent;            
        }
                
        public abstract B done();
    }
    
    
    @SuppressWarnings("unchecked")
    public static abstract class TimeFilterBuilder<
            B extends TimeFilterBuilder<B, F>,
            F extends TemporalFilter>
        extends RangeFilterBuilder<B, F, Instant>
    {
        
        protected TimeFilterBuilder(F instance)
        {
            super(instance);
        }
        
        
        protected B copyFrom(F base)
        {
            Asserts.checkNotNull(base, TemporalFilter.class);
            super.copyFrom(base);
            instance.timeRangeBeginsNow = base.timeRangeBeginsNow;
            instance.timeRangeEndsNow = base.timeRangeEndsNow;
            instance.latestTime = base.latestTime;
            instance.descendingOrder = base.descendingOrder;
            return (B)this;
        }
        
        
        /**
         * Match the first record of each selected time series that
         * has a timestamp at or before the current time
         * @return This builder for chaining
         */
        public B withCurrentTime()
        {
            instance.timeRangeBeginsNow = instance.timeRangeEndsNow = true;
            instance.latestTime = false;
            instance.range = CURRENT_TIME_MARKER;
            return (B)this;
        }
        
        
        /**
         * Match only the latest time stamp a time series
         * @return This builder for chaining
         */
        public B withLatestTime()
        {
            instance.latestTime = true;
            instance.timeRangeBeginsNow = instance.timeRangeEndsNow = false;
            instance.range = Range.singleton(Instant.MAX);
            return (B)this;
        }
        
        
        /**
         * Match any timestamp
         * @return This builder for chaining
         */
        public B withAllTimes()
        {
            withRange(Instant.MIN, Instant.MAX);
            return (B)this;
        }
        
        
        /**
         * Match time stamps between the specified instant and the current time, both included
         * @param begin Beginning of time range
         * @return This builder for chaining
         */
        public B withRangeEndingNow(Instant begin)
        {
            instance.timeRangeEndsNow = true;
            instance.range = Range.closed(begin, Instant.MAX);
            return (B)this;
        }
        
        
        /**
         * Match time stamps between the current time and the specified instant, both included
         * @param end End of time range
         * @return This builder for chaining
         */
        public B withRangeBeginningNow(Instant end)
        {
            instance.timeRangeBeginsNow = true;
            instance.range = Range.closed(Instant.MIN, end);
            return (B)this;
        }
        
        
        /**
         * Build the filter using the same bounds as the provided time extent,
         * accounting for 'now' and unbounded edge cases.
         * @param te Time extent
         * @return This builder for chaining
         */
        public B fromTimeExtent(TimeExtent te)
        {
            if (te.isNow())
                return withCurrentTime();
            else if (te.beginsNow())
                return withRangeBeginningNow(te.end());
            else if (te.endsNow())
                return withRangeEndingNow(te.begin());
            else if (!te.hasBegin())
                return withRange(Instant.MIN, te.end());
            else if (!te.hasEnd())
                return withRange(te.begin(), Instant.MAX);
            else
                return withRange(te.begin(), te.end());
        }

        /**
         * Specify descending or ascending (default) chronological order.
         * @param descending order
         * @return This builder for chaining
         */
        public B descendingOrder(boolean descending)
        {
            instance.descendingOrder = descending;
            return (B)this;
        }
    }
}
