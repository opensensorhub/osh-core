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

import java.util.function.Predicate;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import com.google.common.collect.Range;


/**
 * <p>
 * Filter for ranges of values.
 * </p>
 *
 * @author Alex Robin
 * @date Apr 4, 2018
 * @param <T> Type of range bounds
 */
public class RangeFilter<T extends Comparable<T>> implements Predicate<T>
{    
    protected Range<T> range = Range.all();
    protected RangeOp op = RangeOp.INTERSECTS;
    
    
    public enum RangeOp
    {
        INTERSECTS,
        CONTAINS,
        EQUALS        
    }
    
    
    public Range<T> getRange()
    {
        return range;
    }
    
    
    public T getMin()
    {
        return range.lowerEndpoint();
    }
    
    
    public T getMax()
    {
        return range.upperEndpoint();
    }
    
    
    public RangeOp getOperator()
    {
        return op;
    }
    
    
    public boolean isSingleValue()
    {
        return range.lowerEndpoint().equals(range.upperEndpoint());
    }
    
    
    @Override
    public boolean test(T val)
    {
        return range.contains(val);
    }


    public boolean test(Range<T> other)
    {
        switch (op)
        {
            case CONTAINS:
                return range.encloses(other);
            case EQUALS:
                return range.equals(other);
            default:
                return range.isConnected(other);
        }
    }
    
    
    public RangeFilter<T> intersect(RangeFilter<T> filter) throws EmptyFilterIntersection
    {
        if (filter == null)
            return this;
        return intersect(filter, new Builder<T>()).build();
    }
    
    
    protected <B extends RangeFilterBuilder<B, RangeFilter<T>, T>> B intersect(RangeFilter<T> otherFilter, B builder) throws EmptyFilterIntersection
    {
        if (!range.isConnected(otherFilter.range))
            throw new EmptyFilterIntersection();
        return builder.withRange(range.intersection(otherFilter.range));
    }
    
    
    @Override
    public String toString()
    {
        return String.format("%s [%s - %s]", op, range.lowerEndpoint(), range.upperEndpoint()); 
    }
    
    
    /*
     * Builder
     */
    public static class Builder<T extends Comparable<T>> extends RangeFilterBuilder<Builder<T>, RangeFilter<T>, T>
    {
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public Builder()
        {
            super(new RangeFilter());
        }
        
        /**
         * Builds a new filter using the provided filter as a base
         * @param base Filter used as base
         * @return The new builder
         */
        public static <T extends Comparable<T>> Builder<T> from(RangeFilter<T> base)
        {
            return new Builder<T>().copyFrom(base);
        }
    }
    
    
    @SuppressWarnings("unchecked")
    public static abstract class RangeFilterBuilder<
            B extends RangeFilterBuilder<B, F, T>,
            F extends RangeFilter<T>,
            T extends Comparable<T>>
        extends BaseBuilder<F>
    {        
        
        protected RangeFilterBuilder(F instance)
        {
            super(instance);
        }
        
        
        protected B copyFrom(F base)
        {
            Asserts.checkNotNull(base, RangeFilter.class);
            instance.op = base.op;
            instance.range = base.range;
            return (B)this;
        }
        
        
        public B withRange(Range<T> range)
        {
            instance.range = range;
            return (B)this;
        }
        
        
        public B withRange(T min, T max)
        {
            instance.range = Range.closed(min,  max);
            return (B)this;
        }
        
        
        public B withSingleValue(T val)
        {
            instance.range = Range.singleton(val);
            return (B)this;
        }
        
        
        public B withOperator(RangeOp op)
        {
            instance.op = op;
            return (B)this;
        }
        
        
        @Override
        public F build()
        {
            Asserts.checkNotNull(instance.range, "range");
            return super.build();
        }
    }
    
}
