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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandStatus.CommandStatusCode;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.task.CommandStatusHandler.CommandStatusHandlerContextData;
import org.vast.cdm.common.DataStreamParser;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.fast.JsonDataWriterGson;
import org.vast.util.ReaderException;
import org.vast.util.TimeExtent;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;


public class CommandStatusBindingJson extends ResourceBindingJson<BigId, ICommandStatus>
{
    CommandStatusHandlerContextData contextData;
    ICommandStatusStore statusStore;
    DataStreamParser resultReader;
    DataStreamWriter resultWriter;

    
    public CommandStatusBindingJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading, ICommandStatusStore cmdStore) throws IOException
    {
        super(ctx, idEncoders, forReading);
        this.contextData = (CommandStatusHandlerContextData)ctx.getData();
        this.statusStore = cmdStore;
    }
    
    
    @Override
    public ICommandStatus deserialize(JsonReader reader) throws IOException
    {
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
        
        var status = new CommandStatus.Builder();
        
        try
        {
            reader.beginObject();
            
            while (reader.hasNext())
            {
                var propName = reader.nextName();
                
                if ("command@id".equals(propName))
                {
                    var cmdId = idEncoders.getCommandIdEncoder().decodeID(reader.nextString());
                    status.withCommand(cmdId);
                }
                else if ("reportTime".equals(propName))
                {
                    var ts = OffsetDateTime.parse(reader.nextString()).toInstant();
                    status.withReportTime(ts);
                }
                else if ("executionTime".equals(propName))
                {
                    var te = TimeExtent.parse(reader.nextString());
                    status.withExecutionTime(te);
                }
                else if ("statusCode".equals(propName))
                {
                    var code = CommandStatusCode.valueOf(reader.nextString());
                    status.withStatusCode(code);
                }
                else if ("progress".equals(propName))
                {
                    var percent = reader.nextInt();
                    status.withProgress(percent);
                }
                else if ("message".equals(propName))
                {
                    var msg = reader.nextString().trim();
                    status.withMessage(msg);
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
        catch (IllegalArgumentException | IllegalStateException | ReaderException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
        }
        
        return status.build();
    }


    @Override
    public void serialize(BigId key, ICommandStatus status, boolean showLinks, JsonWriter writer) throws IOException
    {
        serialize(key, status, showLinks, false, writer);
    }
    

    public void serialize(BigId key, ICommandStatus status, boolean showLinks, boolean inlineResult, JsonWriter writer) throws IOException
    {
        var cmdId = idEncoders.getCommandIdEncoder().encodeID(status.getCommandID());
        
        writer.beginObject();
        
        if (key != null)
        {
            var statusId = idEncoders.getCommandIdEncoder().encodeID(key);
            writer.name("id").value(statusId);
        }
        
        writer.name("command@id").value(cmdId);
        writer.name("reportTime").value(status.getReportTime().toString());
        writer.name("statusCode").value(status.getStatusCode().toString());
        
        if (status.getProgress() >= 0)
            writer.name("percentCompletion").value(status.getProgress());
                
        if (status.getExecutionTime() != null)
        {
            writer.name("executionTime").beginArray()
                .value(status.getExecutionTime().begin().toString())
                .value(status.getExecutionTime().end().toString())
                .endArray();
        }
        
        if (status.getMessage() != null) 
            writer.name("message").value(status.getMessage());
        
        int resultNum = 1; // temporary ID for now
        if (status.getResult() != null)
        {
            var result = status.getResult();
            writer.name("results").beginArray();
            
            // datastream references
            if (result.getDataStreamIDs() != null)
            {
                for (var dsId: result.getDataStreamIDs()) {
                    writer.beginObject();
                    writer.name("id").value(Integer.toString(resultNum++));
                    var id = idEncoders.getDataStreamIdEncoder().encodeID(dsId);
                    writer.name("datastream@id").value(id);
                    writer.name("datastream@link").value(ctx.getApiRootURL() + "/datastreams/" + id);
                    writer.endObject();
                }
            }
            
            // observation references
            else if (result.getObservationIDs() != null)
            {
                for (var obsId: result.getObservationIDs())
                {
                    writer.beginObject();
                    writer.name("id").value(Integer.toString(resultNum++));
                    var id = idEncoders.getObsIdEncoder().encodeID(obsId);
                    writer.name("observation@id").value(id);
                    writer.name("observation@link").value(ctx.getApiRootURL() + "/observations/" + id);
                    writer.endObject();
                }
            }
            
            // inline data
            else if (status.getResult().getInlineRecords() != null)
            {
                var resultWriter = new JsonDataWriterGson(writer);
                resultWriter.setDataComponents(contextData.csInfo.getResultStructure());
                
                for (var rec: result.getInlineRecords())
                {
                    writer.beginObject();
                    writer.name("id").value(Integer.toString(resultNum++));
                    writer.name("data");
                    resultWriter.write(rec);
                    writer.endObject();
                }
            }
            
            writer.endArray();
        }
        
        if (status.getMessage() != null)
            writer.name("message").value(status.getMessage());
        
        writer.endObject();
        writer.flush();
    }


    @Override
    public void startCollection() throws IOException
    {
        startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
