/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.obs;

import java.time.Duration;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;


/**
 * <p>
 * Immutable query object for observation statistics.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 13, 2019
 */
public class ObsStatsQuery implements IQueryFilter
{
    protected ObsFilter obsFilter = new ObsFilter.Builder().build();
    protected boolean aggregateFois = false;
    protected Duration histogramBinSize;
    protected long limit = Long.MAX_VALUE;
    protected long offset = 0;
    
    
    /*
     * this class can only be instantiated using builder
     */
    protected ObsStatsQuery() {}


    public ObsFilter getObsFilter()
    {
        return obsFilter;
    }


    public boolean isAggregateFois()
    {
        return aggregateFois;
    }


    public Duration getHistogramBinSize()
    {
        return histogramBinSize;
    }


    @Override
    public long getLimit()
    {
        return limit;
    }


    @Override
    public long getOffset()
    {
        return offset;
    }


    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true, true);
    }
    
    
    public static class Builder extends ObsStatsQueryBuilder<Builder, ObsStatsQuery>
    {
        public Builder()
        {
            this.instance = new ObsStatsQuery();
        }
        
        public static Builder from(ObsStatsQuery base)
        {
            return new Builder().copyFrom(base);
        }
    }


    @SuppressWarnings("unchecked")
    public static class ObsStatsQueryBuilder<
            B extends ObsStatsQueryBuilder<B, Q>,
            Q extends ObsStatsQuery>
        extends BaseBuilder<Q>
    {
        
        protected ObsStatsQueryBuilder()
        {
        }
        
        
        protected B copyFrom(ObsStatsQuery base)
        {
            instance.obsFilter = base.obsFilter;
            instance.aggregateFois = base.aggregateFois;
            instance.histogramBinSize = base.histogramBinSize;
            instance.limit = base.limit;
            instance.offset = base.offset;
            return (B)this;
        }
        

        public B selectObservations(ObsFilter filter)
        {
            instance.obsFilter = filter;
            return (B)this;
        }
        
        
        public ObsFilter.NestedBuilder<B> selectObservations()
        {
            return new ObsFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    ObsStatsQueryBuilder.this.selectObservations(build());
                    return (B)ObsStatsQueryBuilder.this;
                }                
            };
        }
        
        
        public B aggregateFois(boolean aggregate)
        {
            instance.aggregateFois = aggregate;
            return (B)this;
        }
        
        
        public B withHistogramBinSize(Duration size)
        {
            instance.histogramBinSize = size;
            return (B)this;
        }      


        public B withLimit(long limit)
        {
            Asserts.checkArgument(limit >= 0, limit);
            instance.limit = limit;
            return (B)this;
        }


        public B withOffset(long offset)
        {
            Asserts.checkArgument(offset >= 0, offset);
            instance.offset = offset;
            return (B)this;
        }
    }
}
