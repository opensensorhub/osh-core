/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.feature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;
import org.sensorhub.api.datastore.EmptyFilterIntersection;
import org.sensorhub.api.datastore.SpatialFilter;
import org.sensorhub.api.datastore.SpatialFilter.SpatialOp;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.resource.ResourceFilter;
import org.sensorhub.utils.FilterUtils;
import org.sensorhub.utils.ObjectUtils;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import org.vast.util.Bbox;
import org.vast.util.TimeExtent;
import com.google.common.collect.ImmutableSortedSet;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;


/**
 * <p>
 * Base class for feature filters.<br/>
 * There is an implicit AND between all filter parameters.
 * </p>
 *
 * @param <T> Type of resource supported by this filter
 * 
 * @author Alex Robin
 * @date Oct 4, 2020
 */
public abstract class FeatureFilterBase<T extends IFeature> extends ResourceFilter<T>
{
    protected static final Instant LATEST_VERSION = Instant.MAX;
        
    protected SortedSet<String> uniqueIDs;
    protected TemporalFilter validTime;
    protected SpatialFilter location;
    protected boolean includeMembers;
    
    protected transient SortedSet<String> uidPrefixes;
    
    
    /*
     * this class can only be instantiated using builder
     */
    protected FeatureFilterBase()
    {
    }


    public SortedSet<String> getUniqueIDs()
    {
        return uniqueIDs;
    }


    public TemporalFilter getValidTime()
    {
        return validTime;
    }


    public SpatialFilter getLocationFilter()
    {
        return location;
    }
    
    
    public boolean includeMembers()
    {
        return includeMembers;
    }


    @Override
    public boolean test(T f)
    {
        return (super.test(f) &&
                testUniqueIDs(f) &&
                testValidTime(f) &&
                testLocation(f));
    }
    
    
    public boolean testUniqueIDs(IFeature f)
    {
        var uid = f.getUniqueIdentifier();
        
        if (uniqueIDs == null)
            return true;
        
        // Prepare for prefix comparison if some UIDs end with *
        if (uidPrefixes == null)
            computeUidPrefixes();
        
        return (uniqueIDs.contains(uid) ||
                (!uidPrefixes.isEmpty() && uidPrefixes.stream().anyMatch(prefix -> uid.startsWith(prefix))));
    }
    
    
    protected void computeUidPrefixes()
    {
        // also build prefix list if needed
        var prefixList = new ArrayList<String>();
        for (String uid: uniqueIDs)
        {
            if (uid.endsWith(FilterUtils.WILDCARD_CHAR))
                prefixList.add(uid.substring(0, uid.length()-1));
        }
        
        if (!prefixList.isEmpty())
            this.uidPrefixes = ImmutableSortedSet.copyOf(prefixList);
        else
            this.uidPrefixes = Collections.emptySortedSet();
    }
    
    
    public boolean testValidTime(IFeature f)
    {
        if (validTime == null)
            return true;
        
        // if feature doesn't have temporal validity, it means it's always valid
        // so the result depends on temporal operator!
        if (f.getValidTime() == null)
            return validTime.test(TimeExtent.ALL_TIMES);
        else
            return validTime.test(f.getValidTime());
    }
    
    
    public boolean testLocation(IFeature f)
    {
        return location == null ||
               (f.getGeometry() != null && location.test((Geometry)f.getGeometry()));
    }
    
    
    protected <B extends FeatureFilterBaseBuilder<?,T,?>> B intersect(FeatureFilterBase<T> otherFilter, B builder) throws EmptyFilterIntersection
    {
        super.intersect(otherFilter, builder);
        
        var uniqueIDs = FilterUtils.intersectWithWildcards(this.uniqueIDs, otherFilter.uniqueIDs);
        if (uniqueIDs != null)
            builder.withUniqueIDs(uniqueIDs);
        
        var validTime = this.validTime != null ? this.validTime.intersect(otherFilter.validTime) : otherFilter.validTime;
        if (validTime != null)
            builder.withValidTime(validTime);
        
        var location = this.location != null ? this.location.intersect(otherFilter.location) : otherFilter.location;
        if (location != null)
            builder.withLocation(location);
        
        var includeMembers = this.includeMembers || otherFilter.includeMembers;
        builder.includeMembers(includeMembers);
        
        return builder;
    }


    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true, true);
    }
    
    
    @SuppressWarnings("unchecked")
    public static abstract class FeatureFilterBaseBuilder<
            B extends FeatureFilterBaseBuilder<B, V, F>,
            V extends IFeature,
            F extends FeatureFilterBase<V>>
        extends ResourceFilterBuilder<B, V, F>
    {        
        
        protected FeatureFilterBaseBuilder(F instance)
        {
            super(instance);
        }
        
        
        @Override
        public B copyFrom(F base)
        {
            Asserts.checkNotNull(base, FeatureFilterBase.class);
            super.copyFrom(base);
            instance.uniqueIDs = base.getUniqueIDs();
            instance.validTime = base.getValidTime();
            instance.location = base.getLocationFilter();
            instance.includeMembers = base.includeMembers();
            return (B)this;
        }
        
        
        /**
         * Keep only features with specific unique IDs.
         * @param uids One or more unique IDs of features to select.
         * UID strings can include a trailing wildcard to select multiple at once.
         * @return This builder for chaining
         */
        public B withUniqueIDs(String... uids)
        {
            return withUniqueIDs(Arrays.asList(uids));
        }
        
        
        /**
         * Keep only features with specific unique IDs.
         * @param uids Collection of unique IDs.
         * UID strings can include a trailing wildcard to select multiple at once.
         * @return This builder for chaining
         */
        public B withUniqueIDs(Collection<String> uids)
        {
            // don't set if uid list contains '*' wildcard
            if (uids != null && !uids.contains("*"))
                instance.uniqueIDs = ImmutableSortedSet.copyOf(uids);
            return (B)this;
        }


        /**
         * Keep only features whose temporal validity matches the filter.
         * @param timeFilter Temporal filter (see {@link TemporalFilter})
         * @return This builder for chaining
         */
        public B withValidTime(TemporalFilter timeFilter)
        {
            instance.validTime = timeFilter;
            return (B)this;
        }


        /**
         * Keep only features whose temporal validity matches the filter.<br/>
         * Call done() on the nested builder to go back to main builder.
         * @return The {@link TemporalFilter} builder for chaining
         */
        public TemporalFilter.NestedBuilder<B> withValidTime()
        {
            return new TemporalFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    FeatureFilterBaseBuilder.this.withValidTime(build());
                    return (B)FeatureFilterBaseBuilder.this;
                }                
            };
        }
        
        
        /**
         * Keep only features that are valid at some point during the specified period.
         * @param begin Beginning of search period
         * @param end End of search period
         * @return This builder for chaining
         */
        public B withValidTimeDuring(Instant begin, Instant end)
        {
            instance.validTime = new TemporalFilter.Builder()
                    .withRange(begin, end)
                    .build();
            return (B)this;
        }
        
        
        /**
         * Keep only features that are valid at some point during the specified period.
         * @param timeRange Search period
         * @return This builder for chaining
         */
        public B withValidTimeDuring(TimeExtent timeRange)
        {
            instance.validTime = new TemporalFilter.Builder()
                    .fromTimeExtent(timeRange)
                    .build();
            return (B)this;
        }


        /**
         * Keep only feature representations that are valid at the specified time.
         * @param time Time instant of interest (can be set to past or future)
         * @return This builder for chaining
         */
        public B validAtTime(Instant time)
        {
            instance.validTime = new TemporalFilter.Builder()
                    .withSingleValue(time)
                    .build();
            return (B)this;
        }
        
        
        /**
         * Keep only the current version of features.
         * @return This builder for chaining
         */
        public B withCurrentVersion()
        {
            instance.validTime = new TemporalFilter.Builder()
                .withCurrentTime()
                .build();
            return (B)this;
        }
        
        
        /**
         * Keep all versions of each selected feature.
         * @return This builder for chaining
         */
        public B withAllVersions()
        {
            instance.validTime = new TemporalFilter.Builder()
                .withAllTimes()
                .build();
            return (B)this;
        }

        
        /**
         * Keep only features whose geometry matches the filter.
         * @return The {@link SpatialFilter} builder for chaining
         */
        public SpatialFilter.NestedBuilder<B> withLocation()
        {
            return new SpatialFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    FeatureFilterBaseBuilder.this.instance.location = build();
                    return (B)FeatureFilterBaseBuilder.this;
                }                
            };
        }


        /**
         * Keep only features whose geometry matches the filter.
         * @param location Spatial filter (see {@link SpatialFilter})
         * @return This builder for chaining
         */
        public B withLocation(SpatialFilter location)
        {
            instance.location = location;
            return (B)this;
        }


        /**
         * Keep only features whose geometry intersects the given ROI.
         * @param roi Region of interest expressed as a polygon
         * @return This builder for chaining
         */
        public B withLocationIntersecting(Geometry roi)
        {
            instance.location = new SpatialFilter.Builder()
                    .withRoi(roi)
                    .build();
            return (B)this;
        }


        /**
         * Keep only features whose geometry is contained within the given region.
         * @param roi Region of interest expressed as a polygon
         * @return This builder for chaining
         */
        public B withLocationWithin(Polygon roi)
        {
            instance.location = new SpatialFilter.Builder()
                    .withRoi(roi)
                    .withOperator(SpatialOp.CONTAINS)
                    .build();
            return (B)this;
        }


        /**
         * Keep only features whose geometry is contained within the given region.
         * @param bbox Region of interest expressed as a bounding box
         * @return This builder for chaining
         */
        public B withLocationWithin(Bbox bbox)
        {
            instance.location = new SpatialFilter.Builder()
                    .withBbox(bbox)
                    .build();
            return (B)this;
        }


        /**
         * Keep only features whose geometry is contained within the given region,
         * expressed as a circle (i.e. a distance from a given point).
         * @param center Center of the circular region of interest
         * @param dist Distance from the center = circle radius (in meters)
         * @return This builder for chaining
         */
        public B withLocationWithin(Point center, double dist)
        {
            instance.location = new SpatialFilter.Builder()
                    .withDistanceToPoint(center, dist)
                    .build();
            return (B)this;
        }
        
        
        /**
         * Also retrieve features that are member of a selected parent group
         * @param include Set to true to include members
         * @return This builder for chaining
         */
        public B includeMembers(boolean include)
        {
            instance.includeMembers = include;
            return (B)this;
        }
    }
}
