/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.resource;

import java.util.Arrays;
import java.util.Collection;
import java.util.SortedSet;
import java.util.function.Predicate;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.EmptyFilterIntersection;
import org.sensorhub.api.datastore.FullTextFilter;
import org.sensorhub.utils.FilterUtils;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import org.vast.util.IResource;
import com.google.common.collect.ImmutableSortedSet;


/**
 * <p>
 * Immutable filter for any resource type.<br/>
 * It serves as a base for more advanced resource-specific filters.<br/>
 * There is an implicit AND between all filter parameters
 * </p>
 * 
 * @param <T> Type of resource supported by this filter
 *
 * @author Alex Robin
 * @date Oct 8, 2018
 */
public abstract class ResourceFilter<T extends IResource> implements IQueryFilter, Predicate<T>
{
    protected SortedSet<BigId> internalIDs;
    protected FullTextFilter fullText;
    protected Predicate<T> valuePredicate;
    protected long limit = Long.MAX_VALUE;
    
    
    protected ResourceFilter() {}
    
    
    public SortedSet<BigId> getInternalIDs()
    {
        return internalIDs;
    }


    public FullTextFilter getFullTextFilter()
    {
        return fullText;
    }


    public Predicate<T> getValuePredicate()
    {
        return valuePredicate;
    }


    @Override
    public long getLimit()
    {
        return limit;
    }
    
    
    public boolean testFullText(T res)
    {
        return (fullText == null ||
                fullText.test(res.getName()) ||
                fullText.test(res.getDescription()));
    }
    
    
    public boolean testValuePredicate(T res)
    {
        return (valuePredicate == null ||
                valuePredicate.test(res));
    }


    @Override
    public boolean test(T res)
    {
        return testFullText(res) &&
               testValuePredicate(res);
    }


    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true);
    }
    
    
    /**
     * Computes the intersection (logical AND) between this filter and another filter of the same kind
     * @param filter The other filter to AND with
     * @return The new composite filter
     * @throws EmptyFilterIntersection if the intersection doesn't exist
     */
    public abstract ResourceFilter<T> intersect(ResourceFilter<T> filter) throws EmptyFilterIntersection;
    
    
    protected <B extends ResourceFilterBuilder<?,T,?>> B intersect(ResourceFilter<T> otherFilter, B builder) throws EmptyFilterIntersection
    {
        var internalIDs = FilterUtils.intersect(this.internalIDs, otherFilter.internalIDs);
        if (internalIDs != null)
            builder.withInternalIDs(internalIDs);
        
        var fullTextFilter = this.fullText != null ? this.fullText.intersect(otherFilter.fullText) : otherFilter.fullText;
        if (fullTextFilter != null)
            builder.withFullText(fullTextFilter);
        
        var valuePredicate = this.valuePredicate == null ? otherFilter.valuePredicate :
                             otherFilter.valuePredicate != null ? this.valuePredicate.and(otherFilter.valuePredicate) :
                             this.valuePredicate;
        if (valuePredicate != null)
            builder.withValuePredicate(valuePredicate);
        
        var limit = Math.min(this.limit, otherFilter.limit);
        builder.withLimit(limit);
        
        return builder;
    }
    
    
    @SuppressWarnings("unchecked")
    public static class ResourceFilterBuilder<
            B extends ResourceFilterBuilder<B, V, F>,
            V extends IResource,
            F extends ResourceFilter<V>>
        extends BaseBuilder<F>
    {
        
        protected ResourceFilterBuilder(F instance)
        {
            super(instance);
        }
        
        
        /**
         * Init this builder with settings from the provided filter
         * @param base
         * @return This builder for chaining
         */
        public B copyFrom(F base)
        {
            Asserts.checkNotNull(base, ResourceFilter.class);
            instance.internalIDs = base.getInternalIDs();
            instance.fullText = base.getFullTextFilter();
            instance.valuePredicate = base.getValuePredicate();
            instance.limit = base.getLimit();
            return (B)this;
        }
        
        
        /**
         * Keep only resources with specific internal IDs.
         * @param ids One or more internal IDs of resources to select
         * @return This builder for chaining
         */
        public B withInternalIDs(BigId... ids)
        {
            return withInternalIDs(Arrays.asList(ids));
        }
        
        
        /**
         * Keep only resources with specific internal IDs.
         * @param ids Collection of internal IDs
         * @return This builder for chaining
         */
        public B withInternalIDs(Collection<BigId> ids)
        {
            instance.internalIDs = ids != null ? ImmutableSortedSet.copyOf(ids) : null;
            return (B)this;
        }


        /**
         * Keep only resources matching the given text filter
         * @param filter Full text filter
         * @return This builder for chaining
         */
        public B withFullText(FullTextFilter filter)
        {
            instance.fullText = filter;
            return (B)this;
        }

        /**
         * Keep only resources matching the nested text filter
         * @return This builder for chaining
         */
        public FullTextFilter.NestedBuilder<B> withFullText()
        {
            return new FullTextFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    ResourceFilterBuilder.this.instance.fullText = build();
                    return (B)ResourceFilterBuilder.this;
                }
            };
        }
        
        
        /**
         * Keep only resources whose textual properties contain the given keywords
         * @param keywords One or more keyword to look for
         * @return This builder for chaining
         */
        public B withKeywords(String... keywords)
        {
            return withKeywords(Arrays.asList(keywords));
        }
        
        
        /**
         * Keep only resources whose textual properties contain the given keywords
         * @param keywords Collection of keywords to look for
         * @return This builder for chaining
         */
        public B withKeywords(Collection<String> keywords)
        {
            return withFullText(new FullTextFilter.Builder()
                .withKeywords(keywords)
                .build());
        }
        

        /**
         * Keep only resources matching the provided predicate
         * @param valuePredicate
         * @return This builder for chaining
         */
        public B withValuePredicate(Predicate<V> valuePredicate)
        {
            instance.valuePredicate = valuePredicate;
            return (B)this;
        }
        
        
        /**
         * Limit the number of selected resources to the given number
         * @param limit max number of resources to retrieve
         * @return This builder for chaining
         */
        public B withLimit(long limit)
        {
            instance.limit = limit;
            return (B)this;
        }
    }
}
