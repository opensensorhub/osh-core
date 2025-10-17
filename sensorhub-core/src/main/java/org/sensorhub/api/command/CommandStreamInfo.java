/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import org.sensorhub.api.feature.FeatureId;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import org.vast.util.TimeExtent;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Immutable object containing information about a command stream/interface.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 10, 2021
 */
public class CommandStreamInfo implements ICommandStreamInfo
{
    protected String name;
    protected String description;
    protected FeatureId systemID;
    protected TimeExtent validTime;
    protected DataComponent recordStruct;
    protected DataEncoding recordEncoding;
    protected DataComponent resultStruct;
    protected DataEncoding resultEncoding;
    protected DataComponent feasibilityResultStruct;
    protected DataEncoding feasibilityResultEncoding;
    
    
    @Override
    public FeatureId getSystemID()
    {
        return systemID;
    }


    @Override
    public String getControlInputName()
    {
        return recordStruct.getName();
    }


    @Override
    public String getName()
    {
        return name;
    }
    
    
    @Override
    public String getDescription()
    {
        return recordStruct.getDescription();
    }


    @Override
    public DataComponent getRecordStructure()
    {
        return recordStruct;
    }


    @Override
    public DataEncoding getRecordEncoding()
    {
        return recordEncoding;
    }


    @Override
    public TimeExtent getValidTime()
    {
        return validTime;
    }


    @Override
    public TimeExtent getExecutionTimeRange()
    {
        return null;
    }


    @Override
    public TimeExtent getIssueTimeRange()
    {
        return null;
    }


    @Override
    public DataComponent getResultStructure()
    {
        return resultStruct;
    }


    @Override
    public DataEncoding getResultEncoding()
    {
        return resultEncoding;
    }

    @Override
    public DataComponent getFeasibilityResultStructure() {
        return feasibilityResultStruct;
    }

    public DataEncoding getFeasibilityResultEncoding() {
        return feasibilityResultEncoding;
    }

    @Override
    public boolean hasResult()
    {
        return resultStruct != null;
    }


    /*
     * Builder
     */
    public static class Builder extends CommandStreamInfoBuilder<Builder, CommandStreamInfo>
    {
        public Builder()
        {
            this.instance = new CommandStreamInfo();
        }

        public static Builder from(ICommandStreamInfo base)
        {
            return new Builder().copyFrom(base);
        }
    }


    @SuppressWarnings("unchecked")
    public abstract static class CommandStreamInfoBuilder<B extends CommandStreamInfoBuilder<B, T>, T extends CommandStreamInfo>
        extends BaseBuilder<T>
    {
        protected CommandStreamInfoBuilder()
        {
        }


        protected B copyFrom(ICommandStreamInfo base)
        {
            instance.name = base.getName();
            instance.description = base.getDescription();
            instance.systemID = base.getSystemID();
            instance.validTime = base.getValidTime();
            instance.recordStruct = base.getRecordStructure();
            instance.recordEncoding = base.getRecordEncoding();
            instance.resultStruct = base.getResultStructure();
            instance.resultEncoding = base.getResultEncoding();
            return (B)this;
        }
        
        
        public B withName(String name)
        {
            instance.name = name;
            return (B)this;
        }
        
        
        public B withDescription(String desc)
        {
            instance.description = desc;
            return (B)this;
        }


        public B withSystem(FeatureId sysID)
        {
            instance.systemID = sysID;
            return (B)this;
        }


        public B withRecordDescription(DataComponent recordStruct)
        {
            instance.recordStruct = recordStruct;
            return (B)this;
        }


        public B withRecordEncoding(DataEncoding recordEncoding)
        {
            instance.recordEncoding = recordEncoding;
            return (B)this;
        }


        public B withResultDescription(DataComponent resultStruct)
        {
            instance.resultStruct = resultStruct;
            return (B)this;
        }


        public B withResultEncoding(DataEncoding resultEncoding)
        {
            instance.resultEncoding = resultEncoding;
            return (B)this;
        }


        public B withValidTime(TimeExtent validTime)
        {
            instance.validTime = validTime;
            return (B)this;
        }

        public B withFeasibilityResultDescription(DataComponent feasibilityResultStruct) {
            instance.feasibilityResultStruct = feasibilityResultStruct;
            return (B)this;
        }

        public B withFeasibilityResultEncoding(DataEncoding feasibilityResultEncoding) {
            instance.feasibilityResultEncoding = feasibilityResultEncoding;
            return (B)this;
        }


        @Override
        public T build()
        {
            Asserts.checkNotNullOrEmpty(instance.name, "name");
            Asserts.checkNotNull(instance.systemID, "systemID");
            Asserts.checkNotNull(instance.recordStruct, "recordStruct");
            Asserts.checkNotNull(instance.getControlInputName(), "controlInputName");
            Asserts.checkNotNull(instance.recordEncoding, "recordEncoding");
            return super.build();
        }
    }
}
