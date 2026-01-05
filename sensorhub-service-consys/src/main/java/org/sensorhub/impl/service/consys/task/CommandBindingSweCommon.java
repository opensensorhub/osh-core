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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.resource.PropertyFilter;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.task.CommandHandler.CommandHandlerContextData;
import org.sensorhub.utils.SWEDataUtils;
import org.vast.cdm.common.DataStreamParser;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.SWEHelper;
import org.vast.swe.ScalarIndexer;


public class CommandBindingSweCommon extends ResourceBinding<BigId, ICommandData>
{       
    CommandHandlerContextData contextData;
    DataStreamParser paramsReader;
    DataStreamWriter paramsWriter;
    ScalarIndexer timeStampIndexer;
    String userID;

    
    public CommandBindingSweCommon(RequestContext ctx, IdEncoders idEncoders, boolean forReading, ICommandStore obsStore) throws IOException
    {
        super(ctx, idEncoders);
        this.contextData = (CommandHandlerContextData)ctx.getData();
        
        var dsInfo = contextData.csInfo;
        if (forReading)
        {
            var is = ctx.getInputStream();
            paramsReader = getSweCommonParser(dsInfo, is, ctx.getFormat());
            timeStampIndexer = SWEHelper.getTimeStampIndexer(contextData.csInfo.getRecordStructure());
            
            var user = ctx.getSecurityHandler().getCurrentUser();
            this.userID = user != null ? user.getId() : "api";
        }
        else
        {
            var os = ctx.getOutputStream();
            paramsWriter = getSweCommonWriter(dsInfo, os, ctx.getPropertyFilter(), ctx.getFormat());
            
            // if request is coming from a browser, use well-known mime type
            // so browser can display the response
            if (ctx.isBrowserHtmlRequest())
            {
                if (ctx.getFormat().equals(ResourceFormat.SWE_TEXT))
                    ctx.setResponseContentType(ResourceFormat.TEXT_PLAIN.getMimeType());
                else if (ctx.getFormat().equals(ResourceFormat.SWE_XML))
                    ctx.setResponseContentType(ResourceFormat.APPLI_XML.getMimeType());
                else
                    ctx.setResponseContentType(ctx.getFormat().getMimeType());
            }
            else
                ctx.setResponseContentType(ctx.getFormat().getMimeType());
        }
    }
    
    
    @Override
    public ICommandData deserialize() throws IOException
    {
        try
        {
            var rec = paramsReader.parseNextBlock();
            if (rec == null)
                return null;
            
            // get time stamp
            double time;
            if (timeStampIndexer != null)
                time = timeStampIndexer.getDoubleValue(rec);
            else
                time = System.currentTimeMillis() / 1000.;
            
            // create obs object
            return new CommandData.Builder()
                .withCommandStream(contextData.streamID)
                .withSender(userID)
                //.withFoi(contextData.foiId)
                .withIssueTime(SWEDataUtils.toInstant(time))
                .withParams(rec)
                .build();
        }
        catch (IOException e)
        {
            throw new ResourceParseException(e.getMessage());
        }
    }


    @Override
    public void serialize(BigId key, ICommandData cmd, boolean showLinks) throws IOException
    {
        paramsWriter.write(cmd.getParams());
        paramsWriter.flush();
    }
    
    
    protected DataStreamParser getSweCommonParser(ICommandStreamInfo dsInfo, InputStream is, ResourceFormat format) throws IOException
    {
        var dataParser = SWECommonUtils.getParser(dsInfo.getRecordStructure(), dsInfo.getRecordEncoding(), format);
        dataParser.setInput(is);
        return dataParser;
    }
    
    
    protected DataStreamWriter getSweCommonWriter(ICommandStreamInfo dsInfo, OutputStream os, PropertyFilter propFilter, ResourceFormat format) throws IOException
    {
        var dataWriter = SWECommonUtils.getWriter(dsInfo.getRecordStructure(), dsInfo.getRecordEncoding(), format);
        dataWriter.setOutput(os);
        return dataWriter;
    }


    @Override
    public void startCollection() throws IOException
    {
        paramsWriter.startStream(true);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        paramsWriter.endStream();
        paramsWriter.flush();
    }
}
