/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.database.system;

import org.sensorhub.api.database.IObsSystemDbAutoPurgePolicy;
import org.sensorhub.api.datastore.RangeFilter.RangeOp;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.slf4j.Logger;
import org.vast.util.DateTimeFormat;


/**
 * <p>
 * Implementation of purging policy removing records when they reach a 
 * certain age
 * </p>
 *
 * @author Alex Robin
 * @since Oct 29, 2019
 */
public class MaxAgeAutoPurgePolicy implements IObsSystemDbAutoPurgePolicy
{
    MaxAgeAutoPurgeConfig config;
    DateTimeFormat df = new DateTimeFormat();
    
    
    MaxAgeAutoPurgePolicy(MaxAgeAutoPurgeConfig config)
    {
        this.config = config;
    }
    
    
    @Override
    public void trimStorage(IObsSystemDatabase db, Logger log, Collection<String> systemUIDs)
    {
        // remove all systems, datastreams, commandstreams and fois whose validity time period
        // ended before (now - max age)        
        var oldestRecordTime = Instant.now().minusSeconds((long)config.maxRecordAge);

        long numSysRemoved = db.getSystemDescStore().removeEntries(new SystemFilter.Builder()
            .withValidTime(new TemporalFilter.Builder()
                .withOperator(RangeOp.CONTAINS)
                .withRange(Instant.MIN, oldestRecordTime)
                .build())
            .withParents(new SystemFilter.Builder()
                .withUniqueIDs(systemUIDs)
                .includeMembers(true)
                .build())
            .build());
        
        long numFoisRemoved = db.getFoiStore().removeEntries(new FoiFilter.Builder()
            .withValidTime(new TemporalFilter.Builder()
                .withOperator(RangeOp.CONTAINS)
                .withRange(Instant.MIN, oldestRecordTime)
                .build())
            .withParents(new SystemFilter.Builder()
                .withUniqueIDs(systemUIDs)
                .includeMembers(true)
                .build())
            .build());
        
        long numDsRemoved = db.getDataStreamStore().removeEntries(new DataStreamFilter.Builder()
            .withValidTime(new TemporalFilter.Builder()
                .withOperator(RangeOp.CONTAINS)
                .withRange(Instant.MIN, oldestRecordTime)
                .build())
            .withSystems(new SystemFilter.Builder()
                .withUniqueIDs(systemUIDs)
                .includeMembers(true)
                .build())
            .build());
        
        long numCsRemoved = db.getCommandStreamStore().removeEntries(new CommandStreamFilter.Builder()
            .withValidTime(new TemporalFilter.Builder()
                .withOperator(RangeOp.CONTAINS)
                .withRange(Instant.MIN, oldestRecordTime)
                .build())
            .withSystems(new SystemFilter.Builder()
                .withUniqueIDs(systemUIDs)
                .includeMembers(true)
                .build())
            .build());
        
        // for each remaining datastream, remove all obs with a timestamp older than
        // the latest result time minus the max age
        long numObsRemoved = 0;
        var dataStreams = db.getDataStreamStore()
                .selectEntries(new DataStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder()
                        .withUniqueIDs(systemUIDs).includeMembers(true).build()).build()).iterator();
        while (dataStreams.hasNext())
        {
            var dsEntry = dataStreams.next();
            var dsID = dsEntry.getKey().getInternalID();
            var resultTimeRange = dsEntry.getValue().getResultTimeRange();
            
            if (resultTimeRange != null)
            {
                var oldestResultTime = resultTimeRange.end().minusSeconds((long)config.maxRecordAge);
                if (oldestResultTime.isAfter(oldestRecordTime))
                    oldestResultTime = oldestRecordTime;
                
                numObsRemoved += db.getObservationStore().removeEntries(new ObsFilter.Builder()
                    .withDataStreams(dsID)
                    .withResultTimeDuring(Instant.MIN, oldestResultTime)
                    .build());
            }
        }
        
        // for each remaining command stream, remove all commands and status with a timestamp older than
        // the latest issue time minus the max age
        long numCmdRemoved = 0;
        var cmdStreams = db.getCommandStreamStore().selectEntries(
                new CommandStreamFilter.Builder()
                        .withSystems(new SystemFilter.Builder()
                                .withUniqueIDs(systemUIDs).includeMembers(true).build()).build()).iterator();
        while (cmdStreams.hasNext())
        {
            var dsEntry = cmdStreams.next();
            var dsID = dsEntry.getKey().getInternalID();
            var issueTimeRange = dsEntry.getValue().getIssueTimeRange();
            
            if (issueTimeRange != null)
            {
                var oldestIssueTime = issueTimeRange.end().minusSeconds((long)config.maxRecordAge);
                numCmdRemoved += db.getCommandStore().removeEntries(new CommandFilter.Builder()
                    .withCommandStreams(dsID)
                    .withIssueTimeDuring(Instant.MIN, oldestIssueTime)
                    .build());
            }
        }
                
        if (log.isInfoEnabled())
        {
            log.info("Purging data until {}. Removed records: {} systems, {} fois, {} datastreams, {} observations, {} command streams, {} commands",
                oldestRecordTime.truncatedTo(ChronoUnit.SECONDS),
                numSysRemoved, numFoisRemoved, numDsRemoved, numObsRemoved, numCsRemoved, numCmdRemoved);
        }
    }

}
