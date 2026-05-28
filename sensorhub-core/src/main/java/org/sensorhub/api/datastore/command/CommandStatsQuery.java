/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.command;

import java.time.Duration;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;


/**
 * <p>
 * Immutable query object for commands statistics.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 11, 2021
 */
public class CommandStatsQuery implements IQueryFilter
{
    protected CommandFilter commandFilter = new CommandFilter.Builder().build();
    protected Duration histogramBinSize;
    protected long limit = Long.MAX_VALUE;
    protected long offset = 0;
    
    
    /*
     * this class can only be instantiated using builder
     */
    protected CommandStatsQuery() {}


    public CommandFilter getCommandFilter()
    {
        return commandFilter;
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
    
    
    public static class Builder extends CommandStatsQueryBuilder<Builder, CommandStatsQuery>
    {
        public Builder()
        {
            this.instance = new CommandStatsQuery();
        }
        
        public static Builder from(CommandStatsQuery base)
        {
            return new Builder().copyFrom(base);
        }
    }


    @SuppressWarnings("unchecked")
    public static class CommandStatsQueryBuilder<
            B extends CommandStatsQueryBuilder<B, Q>,
            Q extends CommandStatsQuery>
        extends BaseBuilder<Q>
    {
        
        protected CommandStatsQueryBuilder()
        {
        }
        
        
        protected B copyFrom(CommandStatsQuery base)
        {
            instance.commandFilter = base.commandFilter;
            instance.histogramBinSize = base.histogramBinSize;
            instance.limit = base.limit;
            instance.offset = base.offset;
            return (B)this;
        }
        

        public B selectCommands(CommandFilter filter)
        {
            instance.commandFilter = filter;
            return (B)this;
        }
        
        
        public CommandFilter.NestedBuilder<B> selectCommands()
        {
            return new CommandFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    CommandStatsQueryBuilder.this.selectCommands(build());
                    return (B)CommandStatsQueryBuilder.this;
                }                
            };
        }
        
        
        public B withHistogramBinSize(Duration size)
        {
            instance.histogramBinSize = size;
            return (B)this;
        }      


        public B withLimit(int limit)
        {
            Asserts.checkArgument(limit >= 0, limit);
            instance.limit = limit;
            return (B)this;
        }
        

        public B withOffset(long offset)
        {
            instance.offset = offset;
            return (B)this;
        }
    }
}
