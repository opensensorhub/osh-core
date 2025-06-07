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

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.sensorhub.utils.FilterUtils;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import com.google.common.collect.ImmutableSortedSet;


/**
 * <p>
 * TODO TextFilter type description
 * </p>
 *
 * @author Alex Robin
 * @date Sep 26, 2020
 */
public class FullTextFilter implements Predicate<String>
{
    protected Set<String> keywords;
    transient Pattern pattern;
    
    
    public Set<String> getKeywords()
    {
        return keywords;
    }
    
    
    /**
     * Computes the intersection (logical AND) between this filter and another filter of the same kind
     * @param filter The other filter to AND with
     * @return The new composite filter
     * @throws EmptyFilterIntersection if the intersection doesn't exist
     */
    public FullTextFilter intersect(FullTextFilter filter) throws EmptyFilterIntersection
    {
        if (filter == null)
            return this;
        return intersect(filter, new Builder()).build();
    }


    @Override
    public boolean test(String txt)
    {
        if (txt == null)
            return false;
        
        if (pattern == null)
        {
            var regex = new StringBuilder();
            for (var w: keywords)
                regex.append(Pattern.quote(w)).append('|');
            regex.setLength(regex.length()-1);
            pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        }
        
        return pattern.matcher(txt).find();
    }
    
    
    /**
     * Deep clone this filter
     */
    public FullTextFilter clone()
    {
        return Builder.from(this).build();
    }
    
    
    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true, true); 
    }
    
    
    protected <F extends FullTextFilter, B extends TextFilterBuilder<B, F>> B intersect(F otherFilter, B builder) throws EmptyFilterIntersection
    {
        return builder
            .withKeywords(FilterUtils.intersect(this.keywords, otherFilter.keywords));
    }
    
    
    /*
     * Builder
     */
    public static class Builder extends TextFilterBuilder<Builder, FullTextFilter>
    {
        public Builder()
        {
            super(new FullTextFilter());
        }
        
        /**
         * Builds a new filter using the provided filter as a base
         * @param base Filter used as base
         * @return The new builder
         */
        public static Builder from(FullTextFilter base)
        {
            return new Builder().copyFrom(base);
        }
    }
    
    
    /*
     * Nested builder for use within another builder
     */
    public static abstract class NestedBuilder<B> extends TextFilterBuilder<NestedBuilder<B>, FullTextFilter>
    {
        B parent;
        
        public NestedBuilder(B parent)
        {
            super(new FullTextFilter());
            this.parent = parent;            
        }
                
        public abstract B done();
    }
    
    
    @SuppressWarnings("unchecked")
    public static abstract class TextFilterBuilder<
            B extends TextFilterBuilder<B, F>,
            F extends FullTextFilter> extends BaseBuilder<FullTextFilter>
    {
        
        protected TextFilterBuilder(F instance)
        {
            super(instance);
        }
        
        
        protected B copyFrom(F base)
        {
            Asserts.checkNotNull(base, TemporalFilter.class);
            instance.keywords = base.keywords;
            return (B)this;
        }
        
        
        public B withKeywords(String... keywords)
        {
            return withKeywords(Arrays.asList(keywords));
        }
        
        
        public B withKeywords(Collection<String> keywords)
        {
            instance.keywords = keywords != null ? ImmutableSortedSet.copyOf(keywords) : null;
            return (B)this;
        }
    }
}
