/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.task;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.PropertyFilter;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.task.CommandHandler.CommandHandlerContextData;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.BinaryDataWriter;
import org.vast.swe.IComponentFilter;
import org.vast.swe.SWEConstants;
import org.vast.swe.fast.JsonDataParserGson;
import org.vast.swe.fast.JsonDataWriterGson;
import org.vast.util.ReaderException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.opengis.swe.v20.BinaryBlock;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataComponent;


public class CommandBindingJson extends ResourceBindingJson<BigId, ICommandData>
{
    CommandHandlerContextData contextData;
    ICommandStore cmdStore;
    JsonDataParserGson paramsReader;
    Map<BigId, DataStreamWriter> paramsWriters;
    String userID;

    
    public CommandBindingJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading, ICommandStore cmdStore) throws IOException
    {
        super(ctx, idEncoders, forReading);
        this.contextData = (CommandHandlerContextData)ctx.getData();
        this.cmdStore = cmdStore;
        
        if (forReading)
        {
            this.paramsReader = getSweCommonParser(contextData.csInfo, reader);
            
            var user = ctx.getSecurityHandler().getCurrentUser();
            this.userID = user != null ? user.getId() : "api";
        }
        else
        {
            this.paramsWriters = new HashMap<>();
            
            // init params writer only in case of single command stream
            // otherwise we'll do it later
            if (contextData.csInfo != null)
            {
                var resultWriter = getSweCommonWriter(contextData.csInfo, writer, ctx.getPropertyFilter());
                paramsWriters.put(ctx.getParentID(), resultWriter);
            }
        }
    }
    
    
    @Override
    public ICommandData deserialize(JsonReader reader) throws IOException
    {
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
        
        var cmd = new CommandData.Builder()
            .withCommandStream(contextData.streamID)
            .withSender(userID);
        
        try
        {
            reader.beginObject();
            
            while (reader.hasNext())
            {
                var propName = reader.nextName();
                
                if ("issueTime".equals(propName))
                    cmd.withIssueTime(OffsetDateTime.parse(reader.nextString()).toInstant());
                //else if ("foi".equals(propName))
                //    obs.withFoi(id)
                else if ("params".equals(propName))
                {
                    var result = paramsReader.parseNextBlock();
                    cmd.withParams(result);
                }
                else
                    reader.skipValue();
            }
            
            reader.endObject();
        }
        catch (DateTimeParseException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + "Invalid ISO8601 date/time at " + reader.getPath());
        }
        catch (IllegalStateException | ReaderException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
        }
        
        if (contextData.foiId != null)
            cmd.withFoi(contextData.foiId);
        
        // also set timestamp
        return cmd.build();
    }


    @Override
    public void serialize(BigId key, ICommandData cmd, boolean showLinks, JsonWriter writer) throws IOException
    {
        var controlId = idEncoders.getCommandStreamIdEncoder().encodeID(cmd.getCommandStreamID());
            
        writer.beginObject();
        
        if (key != null)
        {
            var cmdId = idEncoders.getCommandIdEncoder().encodeID(key);
            writer.name("id").value(cmdId);
        }
        
        writer.name("controlstream@id").value(controlId);
        
        if (cmd.hasFoi())
        {
            var foiId = idEncoders.getFoiIdEncoder().encodeID(cmd.getFoiID());
            writer.name("samplingFeature@id").value(foiId);
        }

        // TODO: needs "procedure@link" referencing the associated procdure
        
        writer.name("issueTime").value(cmd.getIssueTime().toString());
        writer.name("sender").value(cmd.getSenderID());
        
        // print out current status
        if (key != null)
        {
            var status = cmdStore.getStatusReports().select(new CommandStatusFilter.Builder()
                    .withCommands(key)
                    .latestReport()
                    .build())
                .findFirst().orElse(null);
            if (status != null)
                writer.name("currentStatus").value(status.getStatusCode().toString());
        }
        
        // create or reuse existing params writer and write param data
        writer.name("parameters");
        var paramWriter = paramsWriters.computeIfAbsent(cmd.getCommandStreamID(),
            k -> getSweCommonWriter(k, writer, ctx.getPropertyFilter()) );
        
        // write if JSON is supported, otherwise print warning message
        if (paramWriter instanceof JsonDataWriterGson)
            paramWriter.write(cmd.getParams());
        else
            writer.value("Compressed binary result not shown in JSON");
        
        writer.endObject();
        writer.flush();
    }
    
    
    protected JsonWriter getJsonWriter(OutputStream os, PropertyFilter propFilter) throws IOException
    {
        var writer = super.getJsonWriter(os, propFilter);
        writer.setSerializeNulls(true);
        return writer;
    }
    
    
    protected DataStreamWriter getSweCommonWriter(BigId dsID, JsonWriter writer, PropertyFilter propFilter)
    {
        var dsInfo = cmdStore.getCommandStreams().get(new CommandStreamKey(dsID));
        return getSweCommonWriter(dsInfo, writer, propFilter);
    }
    
    
    protected DataStreamWriter getSweCommonWriter(ICommandStreamInfo dsInfo, JsonWriter writer, PropertyFilter propFilter)
    {        
        if (!allowNonBinaryFormat(dsInfo))
            return new BinaryDataWriter();
        
        // create JSON SWE writer
        var sweWriter = new JsonDataWriterGson(writer);
        sweWriter.setDataComponents(dsInfo.getRecordStructure());
        
        // filter out time component since it's already included in O&M
        sweWriter.setDataComponentFilter(getTimeStampFilter());
        return sweWriter;
    }
    
    
    protected JsonDataParserGson getSweCommonParser(ICommandStreamInfo dsInfo, JsonReader reader)
    {
        // create JSON SWE parser
        var sweParser = new JsonDataParserGson(reader);
        sweParser.setDataComponents(dsInfo.getRecordStructure());
        
        // filter out time component since it's already included in O&M
        sweParser.setDataComponentFilter(getTimeStampFilter());
        return sweParser;
    }
    
    
    protected IComponentFilter getTimeStampFilter()
    {
        return new IComponentFilter() {
            @Override
            public boolean accept(DataComponent comp)
            {
                if (comp.getParent() == null ||
                    SWEConstants.DEF_PHENOMENON_TIME.equals(comp.getDefinition()) ||
                    SWEConstants.DEF_SAMPLING_TIME.equals(comp.getDefinition()))
                    return false;
                else
                    return true;
            }
        };
    }
    
    
    protected boolean allowNonBinaryFormat(ICommandStreamInfo dsInfo)
    {
        if (dsInfo.getRecordEncoding() instanceof BinaryEncoding)
        {
            var enc = (BinaryEncoding)dsInfo.getRecordEncoding();
            for (var member: enc.getMemberList())
            {
                if (member instanceof BinaryBlock)
                    return false;
            }
        }
        
        return true;
    }


    @Override
    public void startCollection() throws IOException
    {
        if (reader != null)
            startJsonCollection(reader);
        else
            startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
