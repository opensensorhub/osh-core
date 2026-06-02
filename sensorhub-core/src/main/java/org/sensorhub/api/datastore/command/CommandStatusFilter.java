/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.command;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.SortedSet;
import java.util.function.Predicate;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandStatus.CommandStatusCode;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.EmptyFilterIntersection;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.utils.FilterUtils;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import com.google.common.collect.ImmutableSortedSet;


/**
 * <p>
 * Immutable filter object for command status messages.<br/>
 * There is an implicit AND between all filter parameters.
 * </p>
 *
 * @author Alex Robin
 * @date Dec 20, 2021
 */
public class CommandStatusFilter implements IQueryFilter, Predicate<ICommandStatus>
{
    protected CommandFilter commandFilter;
    protected TemporalFilter reportTime;
    protected TemporalFilter executionTime;
    protected SortedSet<CommandStatusCode> statusCodes;
    protected Predicate<ICommandStatus> valuePredicate;
    protected long limit = Long.MAX_VALUE;
    protected long offset = 0;
    
    
    /*
     * this class can only be instantiated using builder
     */
    protected CommandStatusFilter() {}


    public CommandFilter getCommandFilter()
    {
        return commandFilter;
    }


    public TemporalFilter getReportTime()
    {
        return reportTime;
    }


    public TemporalFilter getExecutionTime()
    {
        return executionTime;
    }
    
    
    public SortedSet<CommandStatusCode> getStatusCodes()
    {
        return statusCodes;
    }


    public Predicate<ICommandStatus> getValuePredicate()
    {
        return valuePredicate;
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
    public boolean test(ICommandStatus status)
    {
        return (testReportTime(status) &&
                testExecutionTime(status) &&
                testStatus(status) &&
                testValuePredicate(status));
    }
    
    
    public boolean testReportTime(ICommandStatus status)
    {
        return (reportTime == null ||
                reportTime.test(status.getReportTime()));
    }
    
    
    public boolean testExecutionTime(ICommandStatus status)
    {
        return (executionTime == null ||
                (status.getExecutionTime() != null && executionTime.test(status.getExecutionTime())));
    }
    
    
    public boolean testStatus(ICommandStatus status)
    {
        return (statusCodes == null ||
                (status.getStatusCode() != null && statusCodes.contains(status.getStatusCode())));
    }
    
    
    public boolean testValuePredicate(ICommandStatus status)
    {
        return (valuePredicate == null ||
                valuePredicate.test(status));
    }
    
    
    /**
     * Computes the intersection (logical AND) between this filter and another filter of the same kind
     * @param filter The other filter to AND with
     * @return The new composite filter
     * @throws EmptyFilterIntersection if the intersection doesn't exist
     */
    public CommandStatusFilter intersect(CommandStatusFilter filter) throws EmptyFilterIntersection
    {
        if (filter == null)
            return this;
        
        var builder = new Builder();
        
        var commandFilter = this.commandFilter != null ? this.commandFilter.intersect(filter.commandFilter) : filter.commandFilter;
        if (commandFilter != null)
            builder.withCommands(commandFilter);
        
        var reportTime = this.reportTime != null ? this.reportTime.intersect(filter.reportTime) : filter.reportTime;
        if (reportTime != null)
            builder.withReportTime(reportTime);
        
        var execTime = this.executionTime != null ? this.executionTime.intersect(filter.executionTime) : filter.executionTime;
        if (execTime != null)
            builder.withExecutionTime(execTime);
        
        var statusCodes = FilterUtils.intersect(this.statusCodes, filter.statusCodes);
        if (statusCodes != null)
            builder.withStatus(statusCodes);
        
        var valuePredicate = this.valuePredicate != null ? this.valuePredicate.and(filter.valuePredicate) : filter.valuePredicate;
        if (valuePredicate != null)
            builder.withValuePredicate(valuePredicate);
        
        var offset = Math.max(this.offset, filter.offset);
        builder.withOffset(offset);
        
        var limit = Math.min(this.limit, filter.limit);
        builder.withLimit(limit);
        
        return builder.build();
    }
    
    
    /**
     * Deep clone this filter
     */
    public CommandStatusFilter clone()
    {
        return Builder.from(this).build();
    }


    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true, true);
    }
    
    
    /*
     * Builder
     */
    public static class Builder extends CommandStatusFilterBuilder<Builder, CommandStatusFilter>
    {
        public Builder()
        {
            this.instance = new CommandStatusFilter();
        }
        
        /**
         * Builds a new filter using the provided filter as a base
         * @param base Filter used as base
         * @return The new builder
         */
        public static Builder from(CommandStatusFilter base)
        {
            return new Builder().copyFrom(base);
        }
    }
    
    
    /*
     * Nested builder for use within another builder
     */
    public static abstract class NestedBuilder<B> extends CommandStatusFilterBuilder<NestedBuilder<B>, CommandStatusFilter>
    {
        B parent;
        
        public NestedBuilder(B parent)
        {
            this.parent = parent;
            this.instance = new CommandStatusFilter();
        }
                
        public abstract B done();
    }


    @SuppressWarnings("unchecked")
    public static abstract class CommandStatusFilterBuilder<
            B extends CommandStatusFilterBuilder<B, T>,
            T extends CommandStatusFilter>
        extends BaseBuilder<T>
    {
        
        protected CommandStatusFilterBuilder()
        {
        }
        
        
        /**
         * Init this builder with settings from the provided filter
         * @param base
         * @return This builder for chaining
         */
        public B copyFrom(CommandStatusFilter base)
        {
            instance.commandFilter = base.commandFilter;
            instance.reportTime = base.reportTime;
            instance.executionTime = base.executionTime;
            instance.statusCodes = base.statusCodes;
            instance.valuePredicate = base.valuePredicate;
            instance.limit = base.limit;
            instance.offset = base.offset;
            return (B)this;
        }


        /**
         * Keep only status messages whose report time matches the temporal filter.
         * @param filter Temporal filtering options
         * @return This builder for chaining
         */
        public B withReportTime(TemporalFilter filter)
        {
            instance.reportTime = filter;
            return (B)this;
        }


        /**
         * Keep only status messages whose report time is within the given period.
         * @param begin Beginning of desired period
         * @param end End of desired period
         * @return This builder for chaining
         */
        public B withReportTimeDuring(Instant begin, Instant end)
        {
            return withReportTime(new TemporalFilter.Builder()
                    .withRange(begin, end)
                    .build());
        }
        
        
        /**
         * Keep only the latest status report of each selected command
         * @return This builder for chaining
         */
        public B latestReport()
        {
            return withReportTime(new TemporalFilter.Builder()
                .withLatestTime().build());
        }


        /**
         * Keep only status of commands whose execution time matches the temporal filter.
         * @param filter Temporal filtering options
         * @return This builder for chaining
         */
        public B withExecutionTime(TemporalFilter filter)
        {
            instance.executionTime = filter;
            return (B)this;
        }


        /**
         * Keep only status of commands whose execution time is within the given period.
         * @param begin Beginning of desired period
         * @param end End of desired period
         * @return This builder for chaining
         */
        public B withExecutionTimeDuring(Instant begin, Instant end)
        {
            return withExecutionTime(new TemporalFilter.Builder()
                    .withRange(begin, end)
                    .build());
        }
        
        
        /**
         * Keep only status reports with specific status codes.
         * @param statusCodes One or more status codes
         * @return This builder for chaining
         */
        public B withStatus(CommandStatusCode... statusCodes)
        {
            return withStatus(Arrays.asList(statusCodes));
        }
        
        
        /**
         * Keep only status reports with specific status codes.
         * @param statusCodes One or more status codes
         * @return This builder for chaining
         */
        public B withStatus(Collection<CommandStatusCode> statusCodes)
        {
            instance.statusCodes = ImmutableSortedSet.copyOf(statusCodes);
            return (B)this;
        }


        /**
         * Keep only status from commands matching the filter.
         * @param filter Filter to select desired command streams
         * @return This builder for chaining
         */
        public B withCommands(CommandFilter filter)
        {
            instance.commandFilter = filter;
            return (B)this;
        }
        
        
        /**
         * Keep only status from commands matching the filter.<br/>
         * Call done() on the nested builder to go back to main builder.
         * @return The {@link CommandFilter} builder for chaining
         */
        public CommandFilter.NestedBuilder<B> withCommands()
        {
            return new CommandFilter.NestedBuilder<B>((B)this) {
                @Override
                public B done()
                {
                    CommandStatusFilterBuilder.this.withCommands(build());
                    return (B)CommandStatusFilterBuilder.this;
                }
            };
        }


        /**
         * Keep only status from commands with the given IDs.
         * @param ids Internal IDs of one or more commands
         * @return This builder for chaining
         */
        public B withCommands(BigId... ids)
        {
            return withCommands(Arrays.asList(ids));
        }


        /**
         * Keep only status from commands with the given IDs.
         * @param ids Collection of status report internal IDs 
         * @return This builder for chaining
         */
        public B withCommands(Collection<BigId> ids)
        {
            return withCommands(new CommandFilter.Builder()
                .withInternalIDs(ids)
                .build());
        }


        /**
         * Keep only the status messages that matches the predicate
         * @param valuePredicate The predicate to test the status data
         * @return This builder for chaining
         */
        public B withValuePredicate(Predicate<ICommandStatus> valuePredicate)
        {
            instance.valuePredicate = valuePredicate;
            return (B)this;
        }


        /**
         * Sets the maximum number of status reports to retrieve
         * @param limit Max status report count
         * @return This builder for chaining
         */
        public B withLimit(long limit)
        {
            Asserts.checkArgument(limit >= 0, limit);
            instance.limit = limit;
            return (B)this;
        }


        /**
         * Sets the offset of the first status report to retrieve, among the ones matching the filter
         * @param offset Offset of first status report
         * @return This builder for chaining
         */
        public B withOffset(long offset)
        {
            Asserts.checkArgument(offset >= 0, offset);
            instance.offset = offset;
            return (B)this;
        }
    }
}
